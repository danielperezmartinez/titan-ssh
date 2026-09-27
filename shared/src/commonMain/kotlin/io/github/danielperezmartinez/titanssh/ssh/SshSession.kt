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

    /** Closes the session and its transport. */
    suspend fun close()
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
     * @throws SshHostKeyRejected if [hostKeyVerifier] declines the host key.
     * @throws SshAuthFailed if authentication is rejected.
     * @throws SshConnectFailed if the transport cannot be established.
     */
    suspend fun connect(
        endpoint: SshEndpoint,
        credentials: SshCredentials,
        hostKeyVerifier: HostKeyVerifier,
        keepAliveSeconds: Int = 15,
    ): SshSession
}

/** Base type for SSH engine failures. */
sealed class SshException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** The transport could not be established (DNS, refused, timeout, protocol). */
class SshConnectFailed(message: String, cause: Throwable? = null) :
    SshException(message, cause)

/** The host key verifier declined the server's key (possible MITM). */
class SshHostKeyRejected(message: String, cause: Throwable? = null) :
    SshException(message, cause)

/** Authentication was rejected by the server. */
class SshAuthFailed(message: String, cause: Throwable? = null) :
    SshException(message, cause)

/**
 * A [SshCredentials.HardwareKey] could not be resolved on this platform (no
 * hardware key store, or no key under that alias). The caller should fall back
 * to a software key.
 */
class SshHardwareKeyUnavailable(message: String, cause: Throwable? = null) :
    SshException(message, cause)
