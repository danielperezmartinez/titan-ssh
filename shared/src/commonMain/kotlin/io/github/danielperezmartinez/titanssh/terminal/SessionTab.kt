package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.ResilienceLevel
import io.github.danielperezmartinez.titanssh.config.ResolvedConnection
import io.github.danielperezmartinez.titanssh.config.SessionScript
import io.github.danielperezmartinez.titanssh.ssh.HostKeyInfo
import io.github.danielperezmartinez.titanssh.ssh.KnownHostsStore
import io.github.danielperezmartinez.titanssh.ssh.KnownHostsVerifier
import io.github.danielperezmartinez.titanssh.ssh.SshAuthFailed
import io.github.danielperezmartinez.titanssh.ssh.SshConnectionState
import io.github.danielperezmartinez.titanssh.ssh.SshConnector
import io.github.danielperezmartinez.titanssh.ssh.SshCredentials
import io.github.danielperezmartinez.titanssh.ssh.SshException
import io.github.danielperezmartinez.titanssh.ssh.SshHostKeyRejected
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import io.github.danielperezmartinez.titanssh.ssh.SshShell
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

/** The resilience level a tab really runs at, which can be below the session's setting. */
enum class EffectiveLevel { BASE, MULTIPLEXER, AGENT }

/**
 * The resilience a tab really has ([[Diagnóstico cuando el nivel 3 no está
 * disponible]]): its [level] (null until known), the [multiplexer] behind level
 * 2, and an [issue] with level 3, either why it is unavailable (the tab degraded)
 * or a warning while it runs ([AgentIssue.isWarning]).
 */
data class ResilienceStatus(
    val level: EffectiveLevel? = null,
    val multiplexer: TerminalMultiplexer.Kind? = null,
    val issue: AgentIssue? = null,
    /** Why the last [SessionTab.enableLinger] did not work. */
    val lingerError: String? = null,
)

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
     * shell is live and concurrently with painting — on the level-3 path, only
     * when the agent creates a fresh PTY. Defaults to a no-op so a tab with no
     * automation behaves exactly as before.
     */
    private val automation: ShellAutomation = ShellAutomation.None,
    /** Client-side reconnection cadence for level-1 resilience. */
    private val reconnect: ReconnectPolicy = ReconnectPolicy.Default,
    /**
     * Level-3 agent deployer ([[Resiliencia nivel 3 agente propio en el destino]],
     * ADR-0008). When non-null and the session's [ResilienceLevel] is `AGENT`, the
     * tab installs and drives `titan-agent` for full persistence; when null (or the
     * install finds level 3 unavailable, which [resilience] explains), an `AGENT`
     * session falls back to the level-2/1 shell path.
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

    private val _resilience = MutableStateFlow(ResilienceStatus())

    /** The level the tab really runs at and any level-3 issue, for the status strip. */
    val resilience: StateFlow<ResilienceStatus> = _resilience.asStateFlow()

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

    /** I/O of the live shell or agent PTY, for [runScript]; null while not connected. */
    private var liveIo: ShellIo? = null

    /** Active level-3 agent transport (null on the shell path). */
    private var agent: AgentTransport? = null

    /** Bytes the emulator has applied from the agent; carried across reconnects for replay. */
    private var agentOffset: Long = 0

    /**
     * Why level 3 is unavailable on this host. Kept for the tab's life, so its
     * reconnects go straight to the shell instead of retrying the agent.
     */
    private var agentUnavailable: AgentIssue? = null

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
            // persistence. If level 3 is unavailable here, it says why and falls
            // through to the shell path below on this same connection (level 2/1).
            if (agentDeployer != null &&
                resolved.session.resilienceLevel == ResilienceLevel.AGENT &&
                agentUnavailable == null
            ) {
                runAgent(agentDeployer, opened, reconnecting)?.let { return it }
            }

            val newShell = opened.openShell(desiredColumns, desiredRows)
            shell = newShell
            _status.value = TabStatus(TabPhase.CONNECTED)
            // Level 1 unless the automation finds a multiplexer, which it reports.
            _resilience.value = _resilience.value.copy(
                level = if (resolved.session.resilienceLevel == ResilienceLevel.BASE) EffectiveLevel.BASE else null,
                multiplexer = null,
            )

            // Fresh tee (empty replay) fed by pumpOutput below; the automation
            // runs concurrently with painting so it can wait on prompts/sentinels.
            val tee = newTee()
            outputTee = tee
            val io = ShellIo(newShell, tee.asSharedFlow(), ::onMultiplexer)
            liveIo = io
            launchAutomation {
                if (reconnecting) automation.onReconnected(io, resolved)
                else automation.onShellReady(io, resolved)
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
            liveIo = null
            runCatching { agent?.close() }
            runCatching { shell?.close() }
            runCatching { opened?.close() }
            agent = null
            shell = null
            session = null
        }
    }

    /**
     * The level-3 attempt on [opened]: install the agent and drive it until its
     * channel closes. Returns null when level 3 turns out to be unavailable on
     * this host (the reason goes to [resilience]), so the caller opens a shell.
     */
    private suspend fun runAgent(deployer: AgentDeployer, opened: SshSession, reconnecting: Boolean): AttemptResult? {
        val agentLaunch = when (val deployment = deployer.ensureInstalled(opened)) {
            is AgentDeployment.Unavailable -> return degrade(deployment.issue)
            is AgentDeployment.Ready -> {
                _resilience.value = ResilienceStatus(issue = deployment.warning)
                deployment.launch
            }
        }
        _status.value = TabStatus(TabPhase.CONNECTED)
        // Same tee as the shell path, fed from the agent's DATA; the
        // automation's input goes out as INPUT frames.
        val tee = newTee()
        outputTee = tee
        val io = ShellIo({ bytes -> agent?.sendInput(bytes) }, tee.asSharedFlow())
        liveIo = io
        val transport = AgentTransport(
            session = opened,
            agentSessionId = resolved.session.id,
            agent = agentLaunch,
            onOutput = { bytes ->
                feedBytes(bytes)
                tee.tryEmit(bytes.decodeToString())
            },
            initialColumns = desiredColumns,
            initialRows = desiredRows,
            startOffset = agentOffset,
            // Start scripts run only on a fresh PTY: a re-attach replays the
            // live session as it was.
            onAttached = { fresh ->
                _resilience.value = _resilience.value.copy(level = EffectiveLevel.AGENT)
                if (fresh) launchAutomation {
                    automation.onAgentSessionCreated(io, resolved, afterDrop = reconnecting)
                }
            },
        )
        agent = transport
        transport.run()
        agentOffset = transport.appliedOffset
        transport.unavailable?.let { issue ->
            agent = null
            return degrade(issue)
        }
        return if (droppedWithin(opened)) AttemptResult.DROPPED else AttemptResult.CLEAN_EXIT
    }

    /** Records why level 3 is unavailable for the rest of the tab's life; null: open a shell. */
    private fun degrade(issue: AgentIssue): AttemptResult? {
        agentUnavailable = issue
        _resilience.value = ResilienceStatus(issue = issue)
        return null
    }

    /** The automation found [kind] on the shell path: level 2 in it, or level 1 without one. */
    private fun onMultiplexer(kind: TerminalMultiplexer.Kind) {
        val none = kind == TerminalMultiplexer.Kind.NONE
        _resilience.value = _resilience.value.copy(
            level = if (none) EffectiveLevel.BASE else EffectiveLevel.MULTIPLEXER,
            multiplexer = kind.takeUnless { none },
        )
    }

    /**
     * The fix the [AgentDiagnostics.E_SYSTEMD_KILL] warning offers: runs
     * `loginctl enable-linger` for the user on the destination, then probes
     * again. Clears the warning when linger is on; otherwise keeps it and says
     * why in [ResilienceStatus.lingerError].
     */
    suspend fun enableLinger() {
        val s = session
        val error = if (s == null) {
            "no hay conexión con el destino"
        } else {
            runCatching {
                val result = s.execCollect(AgentDiagnostics.ENABLE_LINGER)
                val stillOff = AgentDiagnostics.parseSystemdProbe(s.execCollect(AgentDiagnostics.SYSTEMD_PROBE).stdout)
                if (stillOff == null) {
                    null
                } else {
                    result.stderr.trim().ifEmpty { "linger sigue desactivado (código ${result.exitStatus ?: "?"})" }
                }
            }.getOrElse { it.message ?: "no se pudo ejecutar loginctl" }
        }
        _resilience.value = if (error == null) {
            _resilience.value.copy(issue = null, lingerError = null)
        } else {
            _resilience.value.copy(lingerError = error)
        }
    }

    /** Runs [hook] concurrently with painting, replacing any automation still running. */
    private fun launchAutomation(hook: suspend () -> Unit) {
        automationJob?.cancel()
        automationJob = scope.launch { runCatching { hook() } }
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

    /**
     * Runs [script], picked from the tab's scripts menu, over the live shell or
     * agent PTY, alongside any start scripts still running. Returns false when
     * the tab is not connected.
     */
    fun runScript(script: SessionScript): Boolean {
        val io = liveIo ?: return false
        if (_status.value.phase != TabPhase.CONNECTED) return false
        scope.launch { runCatching { automation.runOnDemand(io, script) } }
        return true
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
