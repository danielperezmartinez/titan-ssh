package io.github.danielperezmartinez.titanssh.ssh

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Lifecycle state of an SSH connection, surfaced per tab by the terminal UI. */
enum class SshConnectionState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
}

/**
 * An interactive remote shell over a PTY. [output] streams the raw bytes the
 * server emits (stdout+stderr); [send] writes user input. Terminal decoding and
 * scrollback live in the UI/terminal layer, not here.
 */
interface SshShell {
    /** Raw bytes from the remote shell. Completes when the channel closes. */
    val output: Flow<ByteArray>

    /** Sends raw input bytes to the remote shell. */
    suspend fun send(data: ByteArray)

    /** Notifies the server of a new terminal size, best-effort. */
    suspend fun resize(columns: Int, rows: Int)

    /** Closes the shell channel. */
    suspend fun close()
}

/**
 * A non-interactive `exec` channel: runs one remote command and exposes its raw
 * stdio. Unlike [SshShell] there is **no PTY** — the bytes are the command's own
 * stdout ([output]) and stdin ([send]), untouched by any line discipline.
 *
 * This is the transport for the resilience level-3 agent (ADR-0008,
 * [[Resiliencia nivel 3 agente propio en el destino]]): the client `exec`s
 * `titan-agent` and speaks the binary framed protocol ([AgentProtocol]) over
 * this channel's stdout/stdin. The agent creates its own PTY on the destination,
 * so this channel must stay PTY-free. [errors] carries the command's stderr for
 * diagnostics.
 */
interface SshExecChannel {
    /** Raw stdout bytes from the remote command. Completes when the channel closes. */
    val output: Flow<ByteArray>

    /** Raw stderr bytes from the remote command (agent diagnostics). */
    val errors: Flow<ByteArray>

    /** Writes raw bytes to the command's stdin. */
    suspend fun send(data: ByteArray)

    /** Closes the channel. Returns the command's exit status if the server sent one. */
    suspend fun close(): Int?
}

/**
 * A minimal SFTP client over one session: what the level-3 agent installer needs
 * (ADR-0009 §7) to place a binary on any destination, Windows included, without
 * relying on the remote shell's tools. Paths use SFTP syntax: `/`-separated,
 * and on Windows a drive letter behind a leading slash (`/C:/Users/u`). SFTP does
 * not expand `~`; resolve the home with [canonicalize] (`"."`).
 */
interface SshSftp {
    /** Resolves [path] to an absolute path on the server (`"."` is the login directory). */
    suspend fun canonicalize(path: String): String

    /** Size in bytes of the file at [path], or null if nothing exists there. */
    suspend fun size(path: String): Long?

    /** Names (not paths) of the entries in [directory], without `.` and `..`. */
    suspend fun list(directory: String): List<String>

    /** Creates [directory] and any missing parents. */
    suspend fun mkdirs(directory: String)

    /** Creates or truncates the file at [path] and writes [bytes] to it. */
    suspend fun write(path: String, bytes: ByteArray)

    /** Reads the whole file at [path]. */
    suspend fun read(path: String): ByteArray

    /** Sets the Unix permission bits of [path] (e.g. `0x1C0` for `0700`). */
    suspend fun chmod(path: String, mode: Int)

    /** Renames [from] to [to]; [to] must not exist (SFTP v3 semantics). */
    suspend fun rename(from: String, to: String)

    /** Deletes the file at [path]. */
    suspend fun remove(path: String)

    /** Closes the SFTP channel. The session stays open. */
    suspend fun close()
}

/**
 * A live SSH session to one endpoint. Owns the transport; can open a shell and
 * exposes [state] so resiliency (level 1) and the UI can react to drops.
 */
interface SshSession {
    /** Observable connection state. */
    val state: StateFlow<SshConnectionState>

    /** Opens an interactive shell with an initial terminal size. */
    suspend fun openShell(columns: Int = 80, rows: Int = 24): SshShell

    /**
     * Runs [command] on a non-interactive `exec` channel (no PTY) and returns it.
     * The transport for the level-3 agent (ADR-0008). Implementations that do not
     * support it (e.g. test fakes) may leave the default, which throws.
     */
    suspend fun exec(command: String): SshExecChannel =
        throw NotImplementedError("exec channel not supported by this session")

    /**
     * Opens an SFTP channel, or returns null when the server offers no `sftp`
     * subsystem (the level-3 installer then falls back to `exec` on Unix). The
     * caller closes it. Test fakes may leave the default.
     */
    suspend fun openSftp(): SshSftp? = null

    /**
     * Opens [forward] over this session ([[Ejecutar los túneles de las
     * sesiones]]) and returns it once it is listening. A connection through it
     * that cannot be completed does not close it: it is reported to [onProblem].
     * Test fakes may leave the default, which throws.
     *
     * @throws SshForwardFailed if the forward cannot be opened.
     */
    suspend fun openForward(forward: PortForward, onProblem: (ForwardProblem) -> Unit = {}): SshForward =
        throw NotImplementedError("port forwarding not supported by this session")

    /** Closes the session and its transport. */
    suspend fun close()
}

/**
 * A port forward over one SSH session. The listen side of a [Local] or
 * [DynamicSocks] forward is on this device; that of a [Remote] one is on the
 * server, which forwards each connection back to a destination this device
 * reaches.
 */
sealed interface PortForward {
    val listenHost: String
    val listenPort: Int

    /** Listens here and connects, through the server, to the destination. */
    data class Local(
        override val listenHost: String,
        override val listenPort: Int,
        val destinationHost: String,
        val destinationPort: Int,
    ) : PortForward

    /** The server listens and each connection is forwarded to the destination from here. */
    data class Remote(
        override val listenHost: String,
        override val listenPort: Int,
        val destinationHost: String,
        val destinationPort: Int,
    ) : PortForward

    /** A SOCKS 4/4a/5 proxy here; each client picks its destination, reached through the server. */
    data class DynamicSocks(
        override val listenHost: String,
        override val listenPort: Int,
    ) : PortForward
}

/** An open [PortForward]. Closing it stops listening; the session stays open. */
interface SshForward {
    suspend fun close()
}

/** Why a [PortForward] could not be opened. */
enum class ForwardFailure {
    /** Another program already listens on that port. */
    PORT_IN_USE,

    /** The OS does not let this user listen there (e.g. a port below 1024). */
    PERMISSION_DENIED,

    /** The listen address is not valid or not local. */
    BAD_ADDRESS,

    /** The server refused a remote forward (forwarding disabled, port taken or privileged). */
    REFUSED_BY_SERVER,

    OTHER,
}

/** One connection through an open forward that could not be completed. */
data class ForwardProblem(val kind: Kind, val target: String) {
    enum class Kind {
        /** The server does not allow forwarding (`AllowTcpForwarding no`). */
        PROHIBITED,

        /** The destination did not accept the connection. */
        UNREACHABLE,

        /** A SOCKS client asked for something the proxy does not support. */
        UNSUPPORTED_REQUEST,

        OTHER,
    }
}

/**
 * Establishes SSH sessions. The single implementation is pure-Java (sshj) in the
 * `jvmShared` source set, shared by Android and desktop; obtain it with
 * [createSshConnector].
 */
interface SshConnector {
    /**
     * Connects and authenticates to [endpoint].
     *
     * @param credentials material to authenticate with (from the SecretStore).
     * @param hostKeyVerifier trust decision for the server's host key.
     * @param keepAliveSeconds interval for transport keepalives; the heartbeat
     *   that lets resiliency detect a drop. `0` disables it.
     * @param via jump hosts (ProxyJump), first hop first. When not empty the
     *   connection to [endpoint] runs inside a `direct-tcpip` channel of the
     *   last hop, and is never opened directly: a hop that fails fails it.
     * @throws SshHostKeyRejected if a verifier declines a host key.
     * @throws SshAuthFailed if authentication is rejected.
     * @throws SshConnectFailed if the transport cannot be established.
     */
    suspend fun connect(
        endpoint: SshEndpoint,
        credentials: SshCredentials,
        hostKeyVerifier: HostKeyVerifier,
        keepAliveSeconds: Int = 15,
        via: List<SshHop> = emptyList(),
    ): SshSession
}

/**
 * A jump host (ProxyJump) the connection passes through on its way to the
 * destination. It is a full SSH connection of its own: its own credentials,
 * host key verification and keepalive.
 */
class SshHop(
    val endpoint: SshEndpoint,
    val credentials: SshCredentials,
    val hostKeyVerifier: HostKeyVerifier,
    val keepAliveSeconds: Int,
)

/** Base type for SSH engine failures. */
sealed class SshException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** The transport could not be established (DNS, refused, timeout, protocol). */
class SshConnectFailed(message: String, cause: Throwable? = null) :
    SshException(message, cause)

/** A kind of algorithm the two ends of an SSH connection negotiate. */
enum class SshAlgorithmKind { KEY_EXCHANGE, HOST_KEY, CIPHER, MAC, COMPRESSION }

/**
 * [host] offers no algorithm of [kind] that the engine accepts. Unlike
 * [SshConnectFailed], trying again cannot help.
 */
class SshNoCommonAlgorithm(val host: String, val kind: SshAlgorithmKind, message: String, cause: Throwable? = null) :
    SshException(message, cause)

/** The host key verifier declined the server's key (possible MITM). */
class SshHostKeyRejected(message: String, cause: Throwable? = null) :
    SshException(message, cause)

/** Authentication was rejected by the server. */
class SshAuthFailed(message: String, cause: Throwable? = null) :
    SshException(message, cause)

/** A [PortForward] could not be opened, for [reason]. */
class SshForwardFailed(val reason: ForwardFailure, message: String, cause: Throwable? = null) :
    SshException(message, cause)

/**
 * A [SshCredentials.HardwareKey] could not be resolved on this platform (no
 * hardware key store, or no key under that alias). The caller should fall back
 * to a software key.
 */
class SshHardwareKeyUnavailable(message: String, cause: Throwable? = null) :
    SshException(message, cause)
