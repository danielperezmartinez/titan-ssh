package im.gar.titanssh.terminal

import im.gar.titanssh.config.ResilienceLevel
import im.gar.titanssh.config.ResolvedConnection
import im.gar.titanssh.ssh.HostKeyInfo
import im.gar.titanssh.ssh.KnownHostsStore
import im.gar.titanssh.ssh.KnownHostsVerifier
import im.gar.titanssh.ssh.SshAuthFailed
import im.gar.titanssh.ssh.SshConnectionState
import im.gar.titanssh.ssh.SshConnector
import im.gar.titanssh.ssh.SshCredentials
import im.gar.titanssh.ssh.SshException
import im.gar.titanssh.ssh.SshHostKeyRejected
import im.gar.titanssh.ssh.SshSession
import im.gar.titanssh.ssh.SshShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Lifecycle phase of one terminal tab, surfaced in the tab strip. */
enum class TabPhase { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, FAILED }

/** A tab's status: its [phase] and an optional human [detail] (e.g. a failure reason). */
data class TabStatus(val phase: TabPhase, val detail: String? = null)

/**
 * A host key awaiting the user's trust decision on first contact (TOFU,
 * ADR-0005). The UI shows [info] and calls [accept]/[reject]; the connection is
 * blocked in the verifier until then.
 */
class PendingHostKey internal constructor(
    val info: HostKeyInfo,
    private val answer: CompletableDeferred<Boolean>,
) {
    fun accept() { answer.complete(true) }
    fun reject() { answer.complete(false) }
}

/**
 * One open terminal tab: owns a single SSH connection and its interactive shell,
 * drives a [TerminalEmulator] from the shell output, and exposes observable
 * [status] and [snapshot] for the UI. Input goes back out through [sendBytes];
 * the terminal echo returns via the output pump.
 *
 * The connection is opened by [start]; failures land in [status] as
 * [TabPhase.FAILED] rather than throwing, so the tab stays visible with its
 * reason. Trust-on-first-use prompts surface through [pendingHostKey].
 *
 * ## Resilience level 1 ([[Resiliencia de sesión ante microcortes de red]])
 * Once a session has been live, a network micro-cut does not close the tab: the
 * [TerminalEmulator] (screen + scrollback) is kept as-is, [status] shows
 * [TabPhase.RECONNECTING], and the tab reconnects with backoff per
 * [ReconnectPolicy]. On success it opens a fresh shell into the *same* emulator —
 * so the scrollback is preserved for the user — and replays automation through
 * [ShellAutomation.onReconnected] (honoring the session's `ReconnectBehavior`). A
 * clean remote exit (the transport stays up) ends the tab instead of reconnecting.
 */
class SessionTab(
    val id: String,
    val resolved: ResolvedConnection,
    private val connector: SshConnector,
    private val credentials: suspend () -> SshCredentials,
    private val knownHostsStore: KnownHostsStore,
    private val scope: CoroutineScope,
    columns: Int = 80,
    rows: Int = 24,
    /**
     * Session start scripts ([[Scripts de inicio por sesión]]), run once the
     * shell is live and concurrently with painting. Defaults to a no-op so a tab
     * with no automation behaves exactly as before.
     */
    private val automation: ShellAutomation = ShellAutomation.None,
    /** Client-side reconnection cadence for level-1 resilience. */
    private val reconnect: ReconnectPolicy = ReconnectPolicy.Default,
    /**
     * Level-3 agent deployer ([[Resiliencia nivel 3 agente propio en el destino]],
     * ADR-0008). When non-null and the session's [ResilienceLevel] is `AGENT`, the
     * tab installs and drives `titan-agent` for full persistence; when null (or the
     * install degrades), an `AGENT` session falls back to the level-2/1 shell path.
     */
    private val agentDeployer: AgentDeployer? = null,
) {
    val title: String get() = resolved.session.name

    private val emulator = TerminalEmulator(columns, rows)
    private val emulatorLock = Mutex()

    private val _status = MutableStateFlow(TabStatus(TabPhase.CONNECTING))
    val status: StateFlow<TabStatus> = _status.asStateFlow()

    private val _snapshot = MutableStateFlow(emulator.snapshot())
    val snapshot: StateFlow<TerminalSnapshot> = _snapshot.asStateFlow()

    private val _pendingHostKey = MutableStateFlow<PendingHostKey?>(null)
    val pendingHostKey: StateFlow<PendingHostKey?> = _pendingHostKey.asStateFlow()

    /**
     * Broadcast, decoded tee of the shell output for [automation] to observe
     * (expect / completion sentinels). The emulator still gets every byte via
     * [pumpOutput], the sole consumer of the single-consumer [SshShell.output];
     * this only re-publishes a copy. A small replay lets automation that
     * subscribes a moment late still catch the opening banner/prompt, and
     * DROP_OLDEST keeps a slow observer from ever stalling the painter.
     *
     * Recreated per connection: a fresh tee has an empty replay buffer, so the
     * reconnect automation waits for the *new* shell's first output instead of
     * matching a chunk left over from before the drop (which would send early
     * input the new PTY has not started reading yet).
     */
    private fun newTee(): MutableSharedFlow<String> = MutableSharedFlow(
        replay = 8,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var outputTee = newTee()

    private var desiredColumns = columns
    private var desiredRows = rows

    private var session: SshSession? = null
    private var shell: SshShell? = null
    private var connectJob: Job? = null
    private var automationJob: Job? = null

    /** Active level-3 agent transport (null on the shell path). */
    private var agent: AgentTransport? = null

    /** Bytes the emulator has applied from the agent; carried across reconnects for replay. */
    private var agentOffset: Long = 0

    /** Set once by [close] so the reconnect loop stops instead of retrying. */
    private var closed = false

    /** Reason of the last establish failure, surfaced if the tab gives up. */
    private var lastFailure: String? = null

    /** Why one connection attempt ended (drives the reconnect loop). */
    private enum class AttemptResult {
        /** The shell ended while the transport was still up: the user exited. */
        CLEAN_EXIT,

        /** The transport went down under a live session: a micro-cut to ride out. */
        DROPPED,

        /** The connection could not be (re)established (transport-level). */
        ESTABLISH_FAILED,

        /** Auth or host-key rejection: never retried, the user must act. */
        FATAL,
    }

    /** Starts the connection. Idempotent: a second call is a no-op. */
    fun start() {
        if (connectJob != null) return
        connectJob = scope.launch { runSession() }
    }

    /**
     * The connect-then-reconnect lifecycle. The first connection is a plain
     * attempt; once a session has been live, drops and failed re-establishes are
     * ridden out with backoff until [ReconnectPolicy.maxAttempts] is exhausted.
     */
    private suspend fun runSession() {
        var everConnected = false
        var attempt = 0
        while (!closed) {
            if (everConnected) {
                attempt++
                if (attempt > reconnect.maxAttempts) {
                    _status.value = TabStatus(
                        TabPhase.DISCONNECTED,
                        "No se pudo reconectar tras ${reconnect.maxAttempts} intentos",
                    )
                    return
                }
                _status.value = TabStatus(
                    TabPhase.RECONNECTING,
                    "Reconectando… (intento $attempt/${reconnect.maxAttempts})",
                )
                delay(reconnect.backoffMillis(attempt))
                if (closed) return
            } else {
                _status.value = TabStatus(TabPhase.CONNECTING)
            }

            when (connectOnce(reconnecting = everConnected)) {
                AttemptResult.CLEAN_EXIT -> {
                    _status.value = TabStatus(TabPhase.DISCONNECTED, "Sesión finalizada")
                    return
                }
                AttemptResult.FATAL -> return // status is already FAILED
                AttemptResult.DROPPED -> {
                    // Was live, now lost: (re)start the reconnect loop with a
                    // fresh backoff — a healthy session resets the counter.
                    everConnected = true
                    attempt = 0
                }
                AttemptResult.ESTABLISH_FAILED -> {
                    if (!everConnected) {
                        _status.value = TabStatus(TabPhase.FAILED, lastFailure ?: "Fallo de conexión")
                        return
                    }
                    // A reconnect attempt could not reach the host yet; keep the
                    // loop going (the attempt counter already advanced above).
                }
            }
        }
    }

    /**
     * One connection attempt: connect, open a shell, run automation and pump the
     * shell output into the emulator until it ends, then classify why it ended.
     */
    private suspend fun connectOnce(reconnecting: Boolean): AttemptResult {
        val verifier = KnownHostsVerifier(knownHostsStore) { info -> promptHostKey(info) }
        val creds = try {
            credentials()
        } catch (e: Exception) {
            _status.value = TabStatus(TabPhase.FAILED, e.message ?: "Could not read credentials")
            return AttemptResult.FATAL
        }
        var opened: SshSession? = null
        try {
            opened = connector.connect(
                endpoint = resolved.endpoint,
                credentials = creds,
                hostKeyVerifier = verifier,
                keepAliveSeconds = resolved.host.keepAliveSeconds,
            )
            session = opened

            // Level-3 agent path (ADR-0008): install + drive titan-agent for full
            // persistence. If it can't be provisioned, fall through to the shell
            // path below (degrade to level 2/1).
            if (agentDeployer != null && resolved.session.resilienceLevel == ResilienceLevel.AGENT) {
                val agentPath = agentDeployer.ensureInstalled(opened)
                if (agentPath != null) {
                    _status.value = TabStatus(TabPhase.CONNECTED)
                    val transport = AgentTransport(
                        session = opened,
                        agentSessionId = resolved.session.id,
                        agentPath = agentPath,
                        onOutput = { bytes -> feedBytes(bytes) },
                        initialColumns = desiredColumns,
                        initialRows = desiredRows,
                        startOffset = agentOffset,
                    )
                    agent = transport
                    transport.run()
                    agentOffset = transport.appliedOffset
                    return if (droppedWithin(opened)) AttemptResult.DROPPED else AttemptResult.CLEAN_EXIT
                }
            }

            val newShell = opened.openShell(desiredColumns, desiredRows)
            shell = newShell
            _status.value = TabStatus(TabPhase.CONNECTED)

            // Fresh tee (empty replay) fed by pumpOutput below; the automation
            // runs concurrently with painting so it can wait on prompts/sentinels.
            val tee = newTee()
            outputTee = tee
            val io = ShellIo(newShell, tee.asSharedFlow())
            automationJob?.cancel()
            automationJob = scope.launch {
                runCatching {
                    if (reconnecting) automation.onReconnected(io, resolved)
                    else automation.onShellReady(io, resolved)
                }
            }

            pumpOutput(newShell)
            // The output flow completed. A clean remote exit leaves the transport
            // up; a drop takes it down (keepalive/heartbeat, ADR-0004).
            return if (droppedWithin(opened)) AttemptResult.DROPPED else AttemptResult.CLEAN_EXIT
        } catch (e: SshHostKeyRejected) {
            _status.value = TabStatus(TabPhase.FAILED, "Clave de host rechazada")
            return AttemptResult.FATAL
        } catch (e: SshAuthFailed) {
            _status.value = TabStatus(TabPhase.FAILED, e.message ?: "Autenticación rechazada")
            return AttemptResult.FATAL
        } catch (e: SshException) {
            lastFailure = e.message ?: "Fallo de conexión"
            return AttemptResult.ESTABLISH_FAILED
        } catch (e: Exception) {
            lastFailure = e.message ?: "Error inesperado"
            return AttemptResult.ESTABLISH_FAILED
        } finally {
            wipe(creds)
            automationJob?.cancel()
            runCatching { agent?.close() }
            runCatching { shell?.close() }
            runCatching { opened?.close() }
            agent = null
            shell = null
            session = null
        }
    }

    /**
     * Waits up to [ReconnectPolicy.dropGraceMillis] for the transport to report
     * itself down. Returns true if it did (a drop) or false if it stayed up (a
     * clean shell exit). The grace window absorbs the lag of the heartbeat-based
     * drop detection so an exit is not misread as a drop or vice versa.
     */
    private suspend fun droppedWithin(session: SshSession): Boolean =
        withTimeoutOrNull(reconnect.dropGraceMillis) {
            session.state.first {
                it == SshConnectionState.DISCONNECTED || it == SshConnectionState.FAILED
            }
            true
        } ?: false

    private suspend fun pumpOutput(shell: SshShell) {
        shell.output.collect { bytes ->
            feedBytes(bytes)
            // Re-publish a decoded copy for automation; tryEmit never suspends, so
            // painting is never blocked by a slow observer.
            outputTee.tryEmit(bytes.decodeToString())
        }
        // The flow completes when the channel closes (remote exit / drop); the
        // run loop classifies the cause and decides whether to reconnect.
    }

    /** Feeds raw output bytes into the emulator and refreshes the snapshot. Used by
     *  both the shell pump and the level-3 [AgentTransport]. */
    private suspend fun feedBytes(bytes: ByteArray) {
        emulatorLock.withLock {
            emulator.feed(bytes)
            _snapshot.value = emulator.snapshot()
        }
    }

    private suspend fun promptHostKey(info: HostKeyInfo): Boolean {
        val answer = CompletableDeferred<Boolean>()
        _pendingHostKey.value = PendingHostKey(info, answer)
        return try {
            answer.await()
        } finally {
            _pendingHostKey.value = null
        }
    }

    /** Sends raw input bytes to the shell — or, on the agent path, as INPUT frames
     *  (no-op if not connected yet). */
    suspend fun sendBytes(bytes: ByteArray) {
        val a = agent
        if (a != null) a.sendInput(bytes) else shell?.send(bytes)
    }

    /** Records a new grid size and forwards it to the emulator and the PTY. */
    suspend fun resize(columns: Int, rows: Int) {
        if (columns < 1 || rows < 1) return
        desiredColumns = columns
        desiredRows = rows
        emulatorLock.withLock {
            emulator.resize(columns, rows)
            _snapshot.value = emulator.snapshot()
        }
        val a = agent
        if (a != null) a.resize(columns, rows) else shell?.resize(columns, rows)
    }

    /** Closes the shell and the session and stops the reconnect loop. */
    suspend fun close() {
        closed = true
        automationJob?.cancel()
        connectJob?.cancel()
        runCatching { agent?.close() }
        runCatching { shell?.close() }
        runCatching { session?.close() }
        agent = null
        shell = null
        session = null
    }

    private fun wipe(credentials: SshCredentials) {
        when (credentials) {
            is SshCredentials.Password -> credentials.password.fill(' ')
            is SshCredentials.PrivateKey -> {
                credentials.privateKeyPem.fill(' ')
                credentials.passphrase?.fill(' ')
            }
            is SshCredentials.HardwareKey -> Unit
        }
    }
}
