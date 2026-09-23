package im.gar.titanssh.ssh

import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordUtils

/** sshj-backed [SshConnector] (ADR-0004). Pure-Java, shared by Android and desktop. */
internal class SshjConnector : SshConnector {

    override suspend fun connect(
        endpoint: SshEndpoint,
        credentials: SshCredentials,
        hostKeyVerifier: HostKeyVerifier,
        keepAliveSeconds: Int,
    ): SshSession = withContext(Dispatchers.IO) {
        val config = DefaultConfig().apply {
            if (keepAliveSeconds > 0) keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
        }
        val ssh = SSHClient(config)
        ssh.addHostKeyVerifier(
            object : net.schmizz.sshj.transport.verification.HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                    val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
                    val info = HostKeyInfo(
                        host = hostname,
                        port = port,
                        keyType = KeyType.fromKey(key).toString(),
                        fingerprintSha256 = sshFingerprintSha256(blob),
                        publicKeyBase64 = Base64.getEncoder().encodeToString(blob),
                    )
                    if (!runBlocking { hostKeyVerifier.verify(info) }) {
                        throw HostKeyRejectedSignal(info)
                    }
                    return true
                }

                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
                    emptyList()
            },
        )

        try {
            ssh.connect(endpoint.host, endpoint.port)
        } catch (e: Throwable) {
            runCatching { ssh.disconnect() }
            rejectedHostKey(e)?.let {
                throw SshHostKeyRejected(
                    "Host key for ${endpoint.host}:${endpoint.port} was rejected (${it.fingerprintSha256})",
                    e,
                )
            }
            throw SshConnectFailed("Could not connect to ${endpoint.host}:${endpoint.port}", e)
        }

        if (keepAliveSeconds > 0) {
            ssh.connection.keepAlive.keepAliveInterval = keepAliveSeconds
        }

        try {
            authenticate(ssh, endpoint.username, credentials)
        } catch (e: SshHardwareKeyUnavailable) {
            runCatching { ssh.disconnect() }
            throw e // let the caller fall back to a software key
        } catch (e: UserAuthException) {
            runCatching { ssh.disconnect() }
            throw SshAuthFailed("Authentication failed for ${endpoint.username}@${endpoint.host}", e)
        } catch (e: Throwable) {
            runCatching { ssh.disconnect() }
            throw SshAuthFailed("Authentication failed for ${endpoint.username}@${endpoint.host}", e)
        }

        SshjSession(ssh)
    }

    private fun authenticate(ssh: SSHClient, username: String, credentials: SshCredentials) {
        when (credentials) {
            is SshCredentials.Password ->
                ssh.authPassword(username, String(credentials.password))

            is SshCredentials.PrivateKey -> {
                val keys: KeyProvider =
                    if (credentials.passphrase != null) {
                        ssh.loadKeys(
                            String(credentials.privateKeyPem),
                            null,
                            PasswordUtils.createOneOff(credentials.passphrase),
                        )
                    } else {
                        ssh.loadKeys(String(credentials.privateKeyPem), null, null)
                    }
                ssh.authPublickey(username, keys)
            }

            is SshCredentials.HardwareKey -> {
                val material = HardwareKeyRegistry.resolve(credentials.keyAlias)
                    ?: throw SshHardwareKeyUnavailable(
                        "No hardware key for alias '${credentials.keyAlias}' on this platform",
                    )
                ssh.authPublickey(username, DelegatedKeyProvider(material))
            }
        }
    }

    private companion object {
        /** Walks the cause chain for a host-key rejection raised inside the verifier. */
        fun rejectedHostKey(t: Throwable): HostKeyInfo? {
            var cur: Throwable? = t
            while (cur != null) {
                if (cur is HostKeyRejectedSignal) return cur.info
                cur = cur.cause
            }
            return null
        }
    }
}

/** The SSH wire-format blob of a public key (the bytes an OpenSSH line base64s). */
internal fun sshPublicKeyBlob(key: PublicKey): ByteArray =
    Buffer.PlainBuffer().putPublicKey(key).compactData

/** Standard `SHA256:<base64-no-pad>` fingerprint from a public key blob. */
internal fun sshFingerprintSha256(blob: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(blob)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}

/** Standard `SHA256:<base64-no-pad>` fingerprint of an SSH public key. */
internal fun sshFingerprintSha256(key: PublicKey): String =
    sshFingerprintSha256(sshPublicKeyBlob(key))

/** Internal marker thrown from the verifier so [SshjConnector] can map it precisely. */
private class HostKeyRejectedSignal(val info: HostKeyInfo) : RuntimeException()

/** Live sshj session. */
internal class SshjSession(private val ssh: SSHClient) : SshSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SshConnectionState.CONNECTED)
    override val state = _state.asStateFlow()

    init {
        // Watch for the transport dropping (keepalive failure / peer close) so
        // resiliency level 1 can react.
        scope.launch {
            while (isActive && ssh.isConnected) {
                delay(POLL_MILLIS)
            }
            _state.compareAndSet(SshConnectionState.CONNECTED, SshConnectionState.DISCONNECTED)
        }
    }

    override suspend fun openShell(columns: Int, rows: Int): SshShell = withContext(Dispatchers.IO) {
        val session = ssh.startSession()
        session.allocatePTY("xterm-256color", columns, rows, 0, 0, emptyMap())
        val shell = session.startShell()
        SshjShell(session, shell, scope)
    }

    override suspend fun exec(command: String): SshExecChannel = withContext(Dispatchers.IO) {
        // No PTY: the level-3 agent (ADR-0008) speaks a binary framed protocol
        // over raw stdout/stdin; a PTY's line discipline would corrupt it.
        val session = ssh.startSession()
        val cmd = session.exec(command)
        SshjExecChannel(session, cmd, scope)
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        _state.value = SshConnectionState.DISCONNECTED
        scope.cancel()
        runCatching { ssh.disconnect() }
        Unit
    }

    private companion object {
        const val POLL_MILLIS = 1000L
    }
}

/** Interactive shell over an sshj PTY session. */
internal class SshjShell(
    private val session: Session,
    private val shell: Session.Shell,
    scope: CoroutineScope,
) : SshShell {

    private val outChannel = Channel<ByteArray>(Channel.BUFFERED)
    override val output = outChannel.receiveAsFlow()

    private val reader = scope.launch(Dispatchers.IO) {
        val buffer = ByteArray(READ_BUFFER)
        try {
            val input = shell.inputStream
            while (isActive) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) outChannel.send(buffer.copyOf(read))
            }
        } catch (_: Throwable) {
            // Stream closed / connection dropped; fall through to close the flow.
        } finally {
            outChannel.close()
        }
    }

    override suspend fun send(data: ByteArray) = withContext(Dispatchers.IO) {
        shell.outputStream.write(data)
        shell.outputStream.flush()
    }

    override suspend fun resize(columns: Int, rows: Int) = withContext(Dispatchers.IO) {
        runCatching { shell.changeWindowDimensions(columns, rows, 0, 0) }
        Unit
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        reader.cancel()
        runCatching { shell.close() }
        runCatching { session.close() }
        outChannel.close()
        Unit
    }

    private companion object {
        const val READ_BUFFER = 8192
    }
}

/**
 * Non-interactive `exec` channel over sshj (no PTY): raw stdout/stderr/stdin of
 * one remote command. Transport for the level-3 agent (ADR-0008,
 * [[Resiliencia nivel 3 agente propio en el destino]]).
 */
internal class SshjExecChannel(
    private val session: Session,
    private val cmd: Session.Command,
    scope: CoroutineScope,
) : SshExecChannel {

    private val outChannel = Channel<ByteArray>(Channel.BUFFERED)
    override val output = outChannel.receiveAsFlow()

    private val errChannel = Channel<ByteArray>(Channel.BUFFERED)
    override val errors = errChannel.receiveAsFlow()

    private val stdoutReader = scope.launch(Dispatchers.IO) { pump(cmd.inputStream, outChannel) }
    private val stderrReader = scope.launch(Dispatchers.IO) { pump(cmd.errorStream, errChannel) }

    private suspend fun pump(input: java.io.InputStream, out: Channel<ByteArray>) {
        val buffer = ByteArray(READ_BUFFER)
        try {
            while (currentCoroutineContext().isActive) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) out.send(buffer.copyOf(read))
            }
        } catch (_: Throwable) {
            // Stream closed / connection dropped; fall through to close the flow.
        } finally {
            out.close()
        }
    }

    override suspend fun send(data: ByteArray) = withContext(Dispatchers.IO) {
        cmd.outputStream.write(data)
        cmd.outputStream.flush()
    }

    override suspend fun close(): Int? = withContext(Dispatchers.IO) {
        stdoutReader.cancel()
        stderrReader.cancel()
        runCatching { cmd.close() }
        val status = runCatching { cmd.exitStatus }.getOrNull()
        runCatching { session.close() }
        outChannel.close()
        errChannel.close()
        status
    }

    private companion object {
        const val READ_BUFFER = 8192
    }
}
