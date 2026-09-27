package io.github.danielperezmartinez.titanssh.ssh

import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Port forwarding against a real sshd ([[Ejecutar los túneles de las
 * sesiones]]), e.g. the Docker one in `tools/test-sshd/`. Opt-in like
 * [SshjIntegrationTest]: skips unless TITAN_SSH_TEST_HOST/USER/KEY are set.
 *
 * With forwarding allowed (the default) it checks the three kinds end to end,
 * using the server's own sshd as the destination (its banner proves the bytes
 * came through). With TITAN_SSH_TEST_NO_FORWARDING=true (a server with
 * `AllowTcpForwarding no`) it checks the refusals instead.
 */
class SshjForwardingIntegrationTest {

    private val noForwarding = System.getenv("TITAN_SSH_TEST_NO_FORWARDING") == "true"

    private fun connect(): SshSession? {
        val host = System.getenv("TITAN_SSH_TEST_HOST")
        val user = System.getenv("TITAN_SSH_TEST_USER")
        val keyPath = System.getenv("TITAN_SSH_TEST_KEY")
        if (host == null || user == null || keyPath == null) {
            println("[forwarding] skipped: set TITAN_SSH_TEST_HOST/USER/KEY to run")
            return null
        }
        val port = System.getenv("TITAN_SSH_TEST_PORT")?.toIntOrNull() ?: 22
        val passphrase = System.getenv("TITAN_SSH_TEST_PASSPHRASE")?.toCharArray()
        return runBlocking {
            createSshConnector().connect(
                endpoint = SshEndpoint(host, port, user),
                credentials = SshCredentials.PrivateKey(File(keyPath).readText().toCharArray(), passphrase),
                hostKeyVerifier = { true },
                keepAliveSeconds = 0,
            )
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** Reads the first line [socket] receives: the SSH banner when it reached an sshd. */
    private fun firstLine(socket: Socket): String {
        socket.soTimeout = 10_000
        return socket.getInputStream().bufferedReader().readLine().orEmpty()
    }

    @Test
    fun local_forward_reaches_the_destination() {
        if (noForwarding) return
        val session = connect() ?: return
        runBlocking {
            val port = freePort()
            val forward = session.openForward(PortForward.Local("127.0.0.1", port, "127.0.0.1", 22))
            val banner = Socket("127.0.0.1", port).use(::firstLine)
            println("[forwarding] local: $banner")
            assertTrue(banner.startsWith("SSH-2.0-"), "the server's sshd answers through the tunnel")
            forward.close()
            // Closed: the port no longer listens and can be reused.
            assertFailsWith<java.io.IOException> { Socket("127.0.0.1", port).close() }
            session.close()
        }
    }

    @Test
    fun socks_proxy_connects_where_the_client_asks() {
        if (noForwarding) return
        val session = connect() ?: return
        runBlocking {
            val port = freePort()
            val problems = Collections.synchronizedList(mutableListOf<ForwardProblem>())
            val forward = session.openForward(PortForward.DynamicSocks("127.0.0.1", port)) { problems += it }
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))

            // The JDK client speaks SOCKS5; an unresolved address travels as a name.
            val banner = Socket(proxy).use {
                it.connect(InetSocketAddress.createUnresolved("localhost", 22), 10_000)
                firstLine(it)
            }
            println("[forwarding] socks: $banner")
            assertTrue(banner.startsWith("SSH-2.0-"))

            // Nothing listens on port 1 of the server: refused, and reported.
            assertFailsWith<java.io.IOException> {
                Socket(proxy).use { it.connect(InetSocketAddress.createUnresolved("127.0.0.1", 1), 10_000) }
            }
            withTimeout(5_000) { while (problems.isEmpty()) delay(50) }
            assertEquals(ForwardProblem(ForwardProblem.Kind.UNREACHABLE, "127.0.0.1:1"), problems.single())
            forward.close()
            session.close()
        }
    }

    @Test
    fun remote_forward_brings_connections_back_here() {
        if (noForwarding) return
        val session = connect() ?: return
        runBlocking {
            val here = ServerSocket(0)
            thread(isDaemon = true) {
                runCatching {
                    here.accept().use { it.getOutputStream().write("titan-remote-ok\n".toByteArray()) }
                }
            }
            val remotePort = 20000 + (System.nanoTime() % 20000).toInt()
            val forward = session.openForward(PortForward.Remote("127.0.0.1", remotePort, "127.0.0.1", here.localPort))

            // On the server, connect to the forwarded port with bash's /dev/tcp.
            val channel = session.exec("bash -c 'exec 3<>/dev/tcp/127.0.0.1/$remotePort; cat <&3'")
            val out = StringBuilder()
            val reader = launch(Dispatchers.IO) { channel.output.collect { out.append(it.decodeToString()) } }
            withTimeout(10_000) { while ("titan-remote-ok" !in out) delay(50) }
            reader.cancel()
            channel.close()
            println("[forwarding] remote: ${out.trim()}")

            forward.close()
            here.close()
            session.close()
        }
    }

    @Test
    fun a_large_transfer_arrives_whole_and_ends_with_eof() {
        if (noForwarding) return
        val session = connect() ?: return
        runBlocking {
            val size = 8 * 1024 * 1024
            val here = ServerSocket(0)
            thread(isDaemon = true) {
                runCatching {
                    here.accept().use { s ->
                        val chunk = ByteArray(64 * 1024) { it.toByte() }
                        repeat(size / chunk.size) { s.getOutputStream().write(chunk) }
                        s.shutdownOutput() // the far side's `cat` only ends on EOF
                        s.getInputStream().read()
                    }
                }
            }
            val remotePort = 20000 + (System.nanoTime() % 20000).toInt()
            val forward = session.openForward(PortForward.Remote("127.0.0.1", remotePort, "127.0.0.1", here.localPort))
            val channel = session.exec("bash -c 'exec 3<>/dev/tcp/127.0.0.1/$remotePort; cat <&3 | wc -c'")
            val out = StringBuilder()
            val reader = launch(Dispatchers.IO) { channel.output.collect { out.append(it.decodeToString()) } }
            withTimeout(60_000) { while (out.trim().isEmpty()) delay(50) }
            reader.cancel()
            channel.close()
            assertEquals(size.toString(), out.trim().toString(), "every byte arrives and EOF ends the stream")
            forward.close()
            here.close()
            session.close()
        }
    }

    @Test
    fun a_taken_local_port_is_reported() {
        val session = connect() ?: return
        runBlocking {
            ServerSocket(0).use { taken ->
                val e = assertFailsWith<SshForwardFailed> {
                    session.openForward(PortForward.Local("127.0.0.1", taken.localPort, "127.0.0.1", 22))
                }
                assertEquals(ForwardFailure.PORT_IN_USE, e.reason)
            }
            session.close()
        }
    }

    @Test
    fun the_server_refuses_a_taken_remote_port() {
        val session = connect() ?: return
        runBlocking {
            // The server's own sshd already listens on its port 22.
            val e = assertFailsWith<SshForwardFailed> {
                session.openForward(PortForward.Remote("127.0.0.1", 22, "127.0.0.1", 1))
            }
            assertEquals(ForwardFailure.REFUSED_BY_SERVER, e.reason)
            session.close()
        }
    }

    @Test
    fun forwarding_disabled_on_the_server() {
        if (!noForwarding) return
        val session = connect() ?: return
        runBlocking {
            val remote = assertFailsWith<SshForwardFailed> {
                session.openForward(PortForward.Remote("127.0.0.1", 20022, "127.0.0.1", 1))
            }
            assertEquals(ForwardFailure.REFUSED_BY_SERVER, remote.reason)

            // A local forward listens, but the server refuses each connection.
            val port = freePort()
            val problems = Collections.synchronizedList(mutableListOf<ForwardProblem>())
            val forward = session.openForward(PortForward.Local("127.0.0.1", port, "127.0.0.1", 22)) { problems += it }
            Socket("127.0.0.1", port).use { it.soTimeout = 10_000; it.getInputStream().read() }
            withTimeout(5_000) { while (problems.isEmpty()) delay(50) }
            assertEquals(ForwardProblem.Kind.PROHIBITED, problems.single().kind)
            forward.close()
            session.close()
        }
    }
}
