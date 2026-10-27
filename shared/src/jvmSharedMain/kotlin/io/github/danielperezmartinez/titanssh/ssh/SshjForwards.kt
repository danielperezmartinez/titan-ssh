package io.github.danielperezmartinez.titanssh.ssh

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.Channel
import net.schmizz.sshj.connection.channel.OpenFailException
import net.schmizz.sshj.connection.channel.forwarded.ConnectListener
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder

/*
 * Port forwarding over sshj ([[Ejecutar los túneles de las sesiones]]). sshj's
 * own LocalPortForwarder only logs a connection it cannot open, and it has no
 * SOCKS proxy, so local and SOCKS forwards share one accept loop here that
 * opens a `direct-tcpip` channel per client and reports what fails. Blocking
 * sockets on daemon threads: a tunnel carries a handful of connections.
 */

/** Opens [forward] on [ssh]. */
internal fun openSshjForward(
    ssh: SSHClient,
    forward: PortForward,
    onProblem: (ForwardProblem) -> Unit,
): SshForward = when (forward) {
    is PortForward.Local -> SshjLocalForward(
        ssh,
        bindLocal(forward.listenHost, forward.listenPort),
        fixedTarget = forward.destinationHost to forward.destinationPort,
        onProblem = onProblem,
    )
    is PortForward.DynamicSocks -> SshjLocalForward(
        ssh,
        bindLocal(forward.listenHost, forward.listenPort),
        fixedTarget = null,
        onProblem = onProblem,
    )
    is PortForward.Remote -> SshjRemoteForward.open(ssh, forward, onProblem)
}

/** Listens on [host]:[port], mapping the OS refusal to a [ForwardFailure]. */
private fun bindLocal(host: String, port: Int): ServerSocket {
    val address = try {
        InetAddress.getByName(host)
    } catch (e: UnknownHostException) {
        throw SshForwardFailed(ForwardFailure.BAD_ADDRESS, "Unknown listen address $host", e)
    }
    val server = ServerSocket()
    try {
        server.bind(InetSocketAddress(address, port))
    } catch (e: IOException) {
        runCatching { server.close() }
        val reason = if (e is BindException) bindFailure(e.message.orEmpty()) else ForwardFailure.OTHER
        throw SshForwardFailed(reason, "Could not listen on $host:$port: ${e.message}", e)
    }
    return server
}

/** Classifies a [BindException] by its message, which is all the JDK gives on every OS. */
internal fun bindFailure(message: String): ForwardFailure {
    val m = message.lowercase()
    return when {
        "in use" in m -> ForwardFailure.PORT_IN_USE
        "permission" in m || "access" in m || "forbidden" in m -> ForwardFailure.PERMISSION_DENIED
        "assign" in m || "invalid" in m -> ForwardFailure.BAD_ADDRESS
        else -> ForwardFailure.OTHER
    }
}

private fun OpenFailException.Reason.toProblemKind(): ForwardProblem.Kind = when (this) {
    OpenFailException.Reason.ADMINISTRATIVELY_PROHIBITED -> ForwardProblem.Kind.PROHIBITED
    OpenFailException.Reason.CONNECT_FAILED -> ForwardProblem.Kind.UNREACHABLE
    else -> ForwardProblem.Kind.OTHER
}

/** Clients of one forward, closed with it. */
private class Clients {
    private val open = ConcurrentHashMap.newKeySet<Closeable>()
    fun add(c: Closeable) { open += c }
    fun remove(c: Closeable) { open -= c }
    fun closeAll() { open.forEach { closeQuietly(it) }; open.clear() }
}

/**
 * A local or SOCKS forward: accepts on [server] and bridges each client to a
 * `direct-tcpip` channel, to [fixedTarget] or, without one, to what the client
 * asks for in its SOCKS handshake.
 */
internal class SshjLocalForward(
    private val ssh: SSHClient,
    private val server: ServerSocket,
    private val fixedTarget: Pair<String, Int>?,
    private val onProblem: (ForwardProblem) -> Unit,
) : SshForward {

    private val clients = Clients()

    init {
        thread(isDaemon = true, name = "titan-forward-${server.localPort}") { acceptLoop() }
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                break // closed
            }
            clients.add(socket)
            thread(isDaemon = true, name = "titan-forward-client") {
                try {
                    serve(socket)
                } finally {
                    closeQuietly(socket)
                    clients.remove(socket)
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val request: Socks.Request?
        val target: Pair<String, Int>
        if (fixedTarget != null) {
            request = null
            target = fixedTarget
        } else {
            socket.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
            request = try {
                Socks.readRequest(input, output)
            } catch (e: Socks.Unsupported) {
                onProblem(ForwardProblem(ForwardProblem.Kind.UNSUPPORTED_REQUEST, e.message.orEmpty()))
                return
            } catch (_: IOException) {
                return // the client went away mid-handshake
            }
            socket.soTimeout = 0
            target = request.host to request.port
        }

        val (host, port) = target
        val channel = try {
            ssh.newDirectConnection(host, port)
        } catch (e: OpenFailException) {
            val kind = e.reason.toProblemKind()
            request?.let { runCatching { Socks.replyFailure(output, it, kind) } }
            onProblem(ForwardProblem(kind, "$host:$port"))
            return
        } catch (_: IOException) {
            // The transport is gone: the tab reconnects and reopens its tunnels.
            request?.let { runCatching { Socks.replyFailure(output, it, ForwardProblem.Kind.OTHER) } }
            return
        }
        clients.add(channel)
        try {
            request?.let { Socks.replySuccess(output, it) }
            bridge(socket, channel)
        } catch (_: IOException) {
            // Either side closed.
        } finally {
            closeQuietly(channel)
            clients.remove(channel)
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        closeQuietly(server)
        clients.closeAll()
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MILLIS = 30_000
    }
}

/** A remote forward: the server listens and each connection is bridged to the destination from here. */
internal class SshjRemoteForward private constructor(
    private val ssh: SSHClient,
    private val clients: Clients,
) : SshForward {

    private var bound: RemotePortForwarder.Forward? = null

    override suspend fun close() = withContext(Dispatchers.IO) {
        val forward = bound
        bound = null
        if (forward != null && ssh.isConnected) runCatching { ssh.remotePortForwarder.cancel(forward) }
        clients.closeAll()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 15_000

        fun open(ssh: SSHClient, forward: PortForward.Remote, onProblem: (ForwardProblem) -> Unit): SshjRemoteForward {
            val clients = Clients()
            val result = SshjRemoteForward(ssh, clients)
            val destination = "${forward.destinationHost}:${forward.destinationPort}"
            val listener = ConnectListener { chan ->
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(forward.destinationHost, forward.destinationPort), CONNECT_TIMEOUT_MILLIS)
                } catch (e: IOException) {
                    closeQuietly(socket)
                    onProblem(ForwardProblem(ForwardProblem.Kind.UNREACHABLE, destination))
                    chan.reject(OpenFailException.Reason.CONNECT_FAILED, e.message ?: "connect failed")
                    return@ConnectListener
                }
                clients.add(socket)
                clients.add(chan)
                try {
                    chan.confirm()
                    bridge(socket, chan)
                } catch (_: IOException) {
                    // Either side closed.
                } finally {
                    closeQuietly(socket)
                    closeQuietly(chan)
                    clients.remove(socket)
                    clients.remove(chan)
                }
            }
            result.bound = try {
                ssh.remotePortForwarder.bind(RemotePortForwarder.Forward(forward.listenHost, forward.listenPort), listener)
            } catch (e: ConnectionException) {
                // The server answered the global request with a failure.
                throw SshForwardFailed(
                    ForwardFailure.REFUSED_BY_SERVER,
                    "Server refused to listen on ${forward.listenHost}:${forward.listenPort}",
                    e,
                )
            } catch (e: IOException) {
                throw SshForwardFailed(ForwardFailure.OTHER, "Remote forward failed: ${e.message}", e)
            }
            return result
        }
    }
}

/**
 * Copies both ways between [socket] and [channel] until both directions end,
 * keeping half-closes: the client's EOF becomes the channel's EOF and the
 * other way round. Returns once the connection is over (or the channel dies).
 */
internal fun bridge(socket: Socket, channel: Channel) {
    val upstream = thread(isDaemon = true, name = "titan-forward-up") {
        try {
            pump(socket.getInputStream(), channel.outputStream, channel.remoteMaxPacketSize)
            channel.outputStream.close() // sends EOF
        } catch (_: IOException) {
            // Closed below.
        }
    }
    try {
        pump(channel.inputStream, socket.getOutputStream(), channel.localMaxPacketSize)
        socket.shutdownOutput()
    } catch (_: IOException) {
        // The channel or the client went away.
    }
    // The client may still be sending; wait for it while the channel lives.
    while (upstream.isAlive && channel.isOpen) upstream.join(JOIN_POLL_MILLIS)
    closeQuietly(socket)
    upstream.join(JOIN_POLL_MILLIS)
}

private const val JOIN_POLL_MILLIS = 500L

private fun pump(input: InputStream, output: OutputStream, bufferSize: Int) {
    val buffer = ByteArray(bufferSize.coerceIn(1024, 64 * 1024))
    while (true) {
        val n = input.read(buffer)
        if (n < 0) return
        if (n > 0) {
            output.write(buffer, 0, n)
            output.flush()
        }
    }
}

private fun closeQuietly(c: Closeable?) {
    runCatching { c?.close() }
}

/**
 * The server side of SOCKS 4, 4a and 5 (CONNECT only, no authentication):
 * enough for browsers, curl and `ssh -o ProxyCommand`. RFC 1928 for version 5.
 */
internal object Socks {

    /** A client's CONNECT request. */
    data class Request(val version: Int, val host: String, val port: Int)

    /** The client asked for something this proxy does not do; it has been answered already. */
    class Unsupported(message: String) : IOException(message)

    /** Reads the greeting and the CONNECT request; answers the greeting itself. */
    fun readRequest(input: InputStream, output: OutputStream): Request {
        val data = DataInputStream(input)
        return when (val version = data.readUnsignedByte()) {
            5 -> readV5(data, output)
            4 -> readV4(data, output)
            else -> throw Unsupported("not a SOCKS client (first byte $version)")
        }
    }

    private fun readV5(data: DataInputStream, output: OutputStream): Request {
        val methods = ByteArray(data.readUnsignedByte()).also { data.readFully(it) }
        if (methods.none { it.toInt() == 0 }) {
            output.write(byteArrayOf(5, 0xFF.toByte()))
            output.flush()
            throw Unsupported("SOCKS5 without the no-authentication method")
        }
        output.write(byteArrayOf(5, 0))
        output.flush()

        if (data.readUnsignedByte() != 5) throw IOException("bad SOCKS5 request")
        val command = data.readUnsignedByte()
        data.readUnsignedByte() // reserved
        val host = when (val type = data.readUnsignedByte()) {
            1 -> InetAddress.getByAddress(ByteArray(4).also { data.readFully(it) }).hostAddress
            3 -> String(ByteArray(data.readUnsignedByte()).also { data.readFully(it) }, Charsets.US_ASCII)
            4 -> InetAddress.getByAddress(ByteArray(16).also { data.readFully(it) }).hostAddress
            else -> {
                reply(output, 5, V5_ADDRESS_UNSUPPORTED)
                throw Unsupported("SOCKS5 address type $type")
            }
        }
        val port = data.readUnsignedShort()
        if (command != 1) {
            reply(output, 5, V5_COMMAND_UNSUPPORTED)
            throw Unsupported("SOCKS5 command $command to $host:$port (only CONNECT)")
        }
        return Request(5, host, port)
    }

    private fun readV4(data: DataInputStream, output: OutputStream): Request {
        val command = data.readUnsignedByte()
        val port = data.readUnsignedShort()
        val ip = ByteArray(4).also { data.readFully(it) }
        readNulTerminated(data) // user id, ignored
        // 4a: 0.0.0.x (x != 0) means "the host name follows".
        val host = if (ip[0].toInt() == 0 && ip[1].toInt() == 0 && ip[2].toInt() == 0 && ip[3].toInt() != 0) {
            readNulTerminated(data)
        } else {
            InetAddress.getByAddress(ip).hostAddress
        }
        if (command != 1) {
            reply(output, 4, V4_REJECTED)
            throw Unsupported("SOCKS4 command $command to $host:$port (only CONNECT)")
        }
        return Request(4, host, port)
    }

    private fun readNulTerminated(data: DataInputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = data.readUnsignedByte()
            if (b == 0) break
            if (bytes.size() >= MAX_FIELD) throw IOException("SOCKS4 field too long")
            bytes.write(b)
        }
        return bytes.toString(Charsets.US_ASCII.name())
    }

    fun replySuccess(output: OutputStream, request: Request) =
        reply(output, request.version, if (request.version == 5) V5_SUCCEEDED else V4_GRANTED)

    fun replyFailure(output: OutputStream, request: Request, kind: ForwardProblem.Kind) {
        val code = if (request.version == 4) {
            V4_REJECTED
        } else {
            when (kind) {
                ForwardProblem.Kind.PROHIBITED -> V5_NOT_ALLOWED
                ForwardProblem.Kind.UNREACHABLE -> V5_REFUSED
                ForwardProblem.Kind.UNSUPPORTED_REQUEST -> V5_COMMAND_UNSUPPORTED
                ForwardProblem.Kind.OTHER -> V5_FAILURE
            }
        }
        reply(output, request.version, code)
    }

    /** The reply carries no bound address: clients ignore it for CONNECT. */
    private fun reply(output: OutputStream, version: Int, code: Int) {
        val bytes = if (version == 5) {
            byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)
        } else {
            byteArrayOf(0, code.toByte(), 0, 0, 0, 0, 0, 0)
        }
        output.write(bytes)
        output.flush()
    }

    private const val MAX_FIELD = 255
    const val V5_SUCCEEDED = 0x00
    const val V5_FAILURE = 0x01
    const val V5_NOT_ALLOWED = 0x02
    const val V5_REFUSED = 0x05
    const val V5_COMMAND_UNSUPPORTED = 0x07
    const val V5_ADDRESS_UNSUPPORTED = 0x08
    const val V4_GRANTED = 0x5A
    const val V4_REJECTED = 0x5B
}
