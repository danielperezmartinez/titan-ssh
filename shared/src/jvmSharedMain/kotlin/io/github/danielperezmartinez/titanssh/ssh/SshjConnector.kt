package io.github.danielperezmartinez.titanssh.ssh

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.EnumSet
import javax.net.SocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.connection.channel.OpenFailException
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.password.PasswordUtils

/** sshj-backed [SshConnector] (ADR-0004). Pure-Java, shared by Android and desktop. */
internal class SshjConnector : SshConnector {

    override suspend fun connect(
        endpoint: SshEndpoint,
        credentials: SshCredentials,
        hostKeyVerifier: HostKeyVerifier,
        keepAliveSeconds: Int,
        via: List<SshHop>,
    ): SshSession = withContext(Dispatchers.IO) {
        // Each hop is reached through the one before it; the destination through
        // the last. Whatever fails closes the hops already open.
        val hops = mutableListOf<SSHClient>()
        try {
            for (hop in via) {
                hops += open(hop.endpoint, hop.credentials, hop.hostKeyVerifier, hop.keepAliveSeconds, hops.lastOrNull())
            }
            SshjSession(open(endpoint, credentials, hostKeyVerifier, keepAliveSeconds, hops.lastOrNull()), hops)
        } catch (e: Throwable) {
            hops.asReversed().forEach { runCatching { it.disconnect() } }
            throw e
        }
    }

    /**
     * Connects and authenticates one SSH client to [endpoint]: directly, or
     * inside a `direct-tcpip` channel of [through] when it is a jump host's.
     */
    private fun open(
        endpoint: SshEndpoint,
        credentials: SshCredentials,
        hostKeyVerifier: HostKeyVerifier,
        keepAliveSeconds: Int,
        through: SSHClient?,
    ): SSHClient {
        val ssh = SSHClient(sshjConfig(keepAlive = keepAliveSeconds > 0))
        ssh.socketFactory = NoDelaySocketFactory
        // Without it an unreachable host takes the OS SYN timeout (about 2 min on
        // Linux and Android), stalling the reconnect loop past the network's return.
        ssh.connectTimeout = CONNECT_TIMEOUT_MILLIS
        ssh.addHostKeyVerifier(sshjVerifier(hostKeyVerifier))

        try {
            if (through == null) {
                ssh.connect(endpoint.host, endpoint.port)
            } else {
                ssh.connectVia(through.newDirectConnection(endpoint.host, endpoint.port))
            }
        } catch (e: Throwable) {
            runCatching { ssh.disconnect() }
            rejectedHostKey(e)?.let {
                throw SshHostKeyRejected(
                    "Host key for ${endpoint.host}:${endpoint.port} was rejected (${it.fingerprintSha256})",
                    e,
                )
            }
            unnegotiatedAlgorithm(e)?.let {
                throw SshNoCommonAlgorithm(
                    endpoint.host,
                    it,
                    "No $it algorithm in common with ${endpoint.host}:${endpoint.port}",
                    e,
                )
            }
            val how = if (through == null) "" else " through the jump host"
            throw SshConnectFailed("Could not connect to ${endpoint.host}:${endpoint.port}$how", e, host = endpoint.host)
        }

        if (keepAliveSeconds > 0) {
            ssh.connection.keepAlive.keepAliveInterval = keepAliveSeconds
        }

        val passwordAnswers = PasswordAnswers()
        try {
            authenticate(ssh, endpoint.username, credentials, passwordAnswers)
        } catch (e: SshHardwareKeyUnavailable) {
            runCatching { ssh.disconnect() }
            throw e // let the caller fall back to a software key
        } catch (e: Throwable) {
            // What the server said while refusing, read before the disconnect drops it.
            val allowed = runCatching { ssh.userAuth.allowedMethods.toList() }.getOrDefault(emptyList())
            val partial = runCatching { ssh.userAuth.hadPartialSuccess() }.getOrDefault(false)
            runCatching { ssh.disconnect() }
            throw SshAuthFailed(
                "Authentication failed for ${endpoint.username}@${endpoint.host}",
                e,
                host = endpoint.host,
                allowedMethods = allowed,
                partialSuccess = partial,
                unansweredPrompts = passwordAnswers.unanswered.toList(),
                passwordChangeRequested = passwordAnswers.changeRequested,
            )
        }
        return ssh
    }

    /** Adapts [verifier] to sshj, keyed by the host and port being connected. */
    private fun sshjVerifier(verifier: HostKeyVerifier) =
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
                if (!runBlocking { verifier.verify(info) }) {
                    throw HostKeyRejectedSignal(info)
                }
                return true
            }

            // sshj puts these first in its host key proposal, so a host keeps
            // presenting the key that was trusted for it.
            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
                runBlocking { verifier.knownKeyTypes(hostname, port) }
                    .flatMap(::hostKeyAlgorithmsFor)
                    .distinct()
        }

    private fun authenticate(ssh: SSHClient, username: String, credentials: SshCredentials, answers: PasswordAnswers) {
        when (credentials) {
            // `password` first; many PAM servers only take the same password
            // through `keyboard-interactive`, so that is the second try.
            is SshCredentials.Password -> {
                val finder = OneOffPassword(credentials.password)
                ssh.auth(
                    username,
                    AuthPassword(finder, answers),
                    AuthKeyboardInteractive(PasswordPrompts(credentials.password, answers)),
                )
            }

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

/**
 * The host key algorithm names that sign with a key of [keyType]. Only RSA
 * differs: one `ssh-rsa` key serves the SHA-2 signature algorithms as well.
 */
internal fun hostKeyAlgorithmsFor(keyType: String): List<String> =
    if (keyType == "ssh-rsa") listOf("rsa-sha2-512", "rsa-sha2-256", "ssh-rsa") else listOf(keyType)

/** TCP connect timeout for every SSH connection. */
private const val CONNECT_TIMEOUT_MILLIS = 15_000

/**
 * Sockets with `TCP_NODELAY`. sshj leaves Nagle on, and Nagle holds every small
 * packet until the previous one is acknowledged: the mouse pad's moves and the
 * terminal's keys then leave in bursts, one per round trip. A jump host's
 * connection is the only one with a socket; the hops inside it ride on it.
 */
internal object NoDelaySocketFactory : SocketFactory() {
    private val default = getDefault()

    override fun createSocket(): Socket = Socket().noDelay()

    override fun createSocket(host: String, port: Int): Socket = default.createSocket(host, port).noDelay()

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        default.createSocket(host, port, localHost, localPort).noDelay()

    override fun createSocket(host: InetAddress, port: Int): Socket = default.createSocket(host, port).noDelay()

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        default.createSocket(address, port, localAddress, localPort).noDelay()

    private fun Socket.noDelay(): Socket = apply { tcpNoDelay = true }
}

/** Internal marker thrown from the verifier so [SshjConnector] can map it precisely. */
private class HostKeyRejectedSignal(val info: HostKeyInfo) : RuntimeException()

/**
 * Live sshj session on [ssh], reached through the jump host connections in
 * [hops] (first hop first; empty when direct), which it owns.
 */
internal class SshjSession(
    private val ssh: SSHClient,
    private val hops: List<SSHClient> = emptyList(),
) : SshSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SshConnectionState.CONNECTED)
    override val state = _state.asStateFlow()

    init {
        // Watch for the transport dropping (keepalive failure / peer close) so
        // resiliency level 1 can react. A jump host dropping takes it down too.
        scope.launch {
            while (isActive && ssh.isConnected && hops.all { it.isConnected }) {
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

    override suspend fun openSftp(): SshSftp? = withContext(Dispatchers.IO) {
        // A server without the sftp subsystem refuses the channel request; the
        // level-3 installer then falls back to exec (ADR-0009 §7).
        runCatching { SshjSftp(ssh.newSFTPClient()) }.getOrNull()
    }

    override suspend fun openForward(forward: PortForward, onProblem: (ForwardProblem) -> Unit): SshForward =
        withContext(Dispatchers.IO) { openSshjForward(ssh, forward, onProblem) }

    override suspend fun openDirect(host: String, port: Int): SshDirectChannel = withContext(Dispatchers.IO) {
        val channel = try {
            ssh.newDirectConnection(host, port)
        } catch (e: OpenFailException) {
            throw SshDirectRefused(e.reason.toProblemKind(), "$host:$port: ${e.message}", e)
        } catch (e: IOException) {
            throw SshConnectFailed("Could not open a channel to $host:$port: ${e.message}", e)
        }
        SshjDirectChannel(channel)
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        _state.value = SshConnectionState.DISCONNECTED
        scope.cancel()
        runCatching { ssh.disconnect() }
        hops.asReversed().forEach { runCatching { it.disconnect() } }
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

/**
 * SFTP over sshj's [SFTPClient]: the level-3 agent installer's upload path
 * (ADR-0009 §7) and the SFTP session's browser and transfers (ADR-0021,
 * ADR-0024).
 */
internal class SshjSftp(private val sftp: SFTPClient) : SshSftp {

    override suspend fun canonicalize(path: String): String = io { sftp.canonicalize(path) }

    override suspend fun size(path: String): Long? = io { sftp.statExistence(path)?.size }

    override suspend fun list(directory: String): List<String> = io {
        sftp.ls(directory).map { it.name }.filter { it != "." && it != ".." }
    }

    override suspend fun listEntries(directory: String): List<SftpEntry> = io(answers = true) {
        sftp.ls(directory).filter { it.name != "." && it.name != ".." }.map { info ->
            val attrs = info.attributes
            if (attrs.type != FileMode.Type.SYMLINK) return@map entryOf(info.name, info.path, attrs)
            // A link: what it points to decides whether it browses as a folder.
            val target = runCatching { sftp.stat(info.path) }.getOrNull()
            val kind = if (target?.type == FileMode.Type.DIRECTORY) SftpEntry.Kind.LINK_TO_DIRECTORY else SftpEntry.Kind.LINK
            entryOf(info.name, info.path, target ?: attrs).copy(kind = kind)
        }
    }

    override suspend fun stat(path: String): SftpEntry? = io(answers = true) {
        sftp.statExistence(path)?.let { entryOf(path.trimEnd('/').substringAfterLast('/'), path, it) }
    }

    override suspend fun readFrom(path: String, offset: Long, onBlock: suspend (ByteArray, Int) -> Unit) =
        withContext(Dispatchers.IO) {
            serverAnswers {
                sftp.open(path, EnumSet.of(OpenMode.READ))
            }.use { file ->
                file.ReadAheadRemoteFileInputStream(MAX_IN_FLIGHT, offset).use { input ->
                    val buffer = ByteArray(READ_BLOCK)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n > 0) onBlock(buffer, n)
                    }
                }
            }
        }

    private fun entryOf(name: String, path: String, attrs: FileAttributes) = SftpEntry(
        name = name,
        path = path,
        kind = when (attrs.type) {
            FileMode.Type.DIRECTORY -> SftpEntry.Kind.DIRECTORY
            FileMode.Type.REGULAR -> SftpEntry.Kind.FILE
            FileMode.Type.SYMLINK -> SftpEntry.Kind.LINK
            else -> SftpEntry.Kind.OTHER
        },
        size = attrs.size.takeIf { attrs.has(FileAttributes.Flag.SIZE) },
        modifiedEpochSeconds = attrs.mtime.takeIf { attrs.has(FileAttributes.Flag.ACMODTIME) },
        permissions = attrs.mode?.permissionsMask?.takeIf { attrs.has(FileAttributes.Flag.MODE) },
    )

    override suspend fun writeFrom(path: String, offset: Long, nextBlock: suspend (ByteArray) -> Int) =
        withContext(Dispatchers.IO) {
            val modes = if (offset == 0L) {
                EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
            } else {
                EnumSet.of(OpenMode.WRITE)
            }
            serverAnswers {
                sftp.open(path, modes).use { file ->
                    // One packet per write, several in flight, as in [write].
                    val buffer = ByteArray(sftp.sftpEngine.subsystem.remoteMaxPacketSize - file.outgoingPacketOverhead)
                    file.RemoteFileOutputStream(offset, MAX_IN_FLIGHT).use { out ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = nextBlock(buffer)
                            if (n < 0) break
                            if (n > 0) out.write(buffer, 0, n)
                        }
                    }
                }
            }
        }

    override val writeAhead: Long
        get() = sftp.sftpEngine.subsystem.remoteMaxPacketSize.toLong() * MAX_IN_FLIGHT

    override suspend fun makeDirectory(path: String) = io(answers = true) { sftp.mkdir(path) }

    override suspend fun removeDirectory(path: String) = io(answers = true) { sftp.rmdir(path) }

    override suspend fun mkdirs(directory: String) = io { sftp.mkdirs(directory) }

    override suspend fun write(path: String, bytes: ByteArray) = io {
        sftp.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { file ->
            // One SSH_FXP_WRITE per packet (a single write of the whole buffer
            // overflows the channel window), several in flight: one round trip
            // per packet would make a multi-megabyte upload crawl on a
            // high-latency link. Same sizing as sshj's own SFTPFileTransfer.
            val chunk = sftp.sftpEngine.subsystem.remoteMaxPacketSize - file.outgoingPacketOverhead
            file.RemoteFileOutputStream(0, MAX_IN_FLIGHT).use { out ->
                var offset = 0
                while (offset < bytes.size) {
                    val n = minOf(chunk, bytes.size - offset)
                    out.write(bytes, offset, n)
                    offset += n
                }
            }
        }
    }

    override suspend fun read(path: String): ByteArray = io {
        sftp.open(path, EnumSet.of(OpenMode.READ)).use { file ->
            file.ReadAheadRemoteFileInputStream(MAX_IN_FLIGHT).use { it.readBytes() }
        }
    }

    override suspend fun chmod(path: String, mode: Int) = io { sftp.chmod(path, mode) }

    override suspend fun rename(from: String, to: String) = io(answers = true) { sftp.rename(from, to) }

    override suspend fun remove(path: String) = io(answers = true) { sftp.rm(path) }

    override suspend fun close() = io { runCatching { sftp.close() }; Unit }

    /** Runs [block] off the main thread; with [answers], the server's own errors become [SshSftpFailed]. */
    private suspend fun <T> io(answers: Boolean = false, block: () -> T): T =
        withContext(Dispatchers.IO) { if (answers) serverAnswers(block) else block() }

    /**
     * [block], with an error status the server sent (no such file, permission
     * denied…) as [SshSftpFailed]. A lost connection keeps its own exception:
     * the browser waits for the tab to reconnect instead of giving up.
     */
    private inline fun <T> serverAnswers(block: () -> T): T =
        try {
            block()
        } catch (e: SFTPException) {
            if (e.statusCode in SERVER_ANSWERS) throw SshSftpFailed(e.message ?: e.statusCode.name, e)
            throw e
        }

    private companion object {
        const val MAX_IN_FLIGHT = 16

        /** What [readFrom] hands over at a time: big enough for few calls, small enough for smooth progress. */
        const val READ_BLOCK = 64 * 1024

        /** Statuses that are the server's answer to the request, not a broken connection. */
        val SERVER_ANSWERS = setOf(
            Response.StatusCode.NO_SUCH_FILE,
            Response.StatusCode.PERMISSION_DENIED,
            Response.StatusCode.FAILURE,
            Response.StatusCode.OP_UNSUPPORTED,
        )
    }
}

/** A `direct-tcpip` channel over sshj, for [SshSession.openDirect]. */
private class SshjDirectChannel(private val channel: DirectConnection) : SshDirectChannel {
    private val input = channel.inputStream
    private val output = channel.outputStream

    override fun read(buffer: ByteArray): Int = input.read(buffer)

    override fun write(buffer: ByteArray, length: Int) {
        output.write(buffer, 0, length)
        output.flush()
    }

    override fun close() {
        runCatching { channel.close() }
    }
}
