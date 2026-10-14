package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.HostAuth
import io.github.danielperezmartinez.titanssh.config.HostKeyPolicy
import io.github.danielperezmartinez.titanssh.config.ResilienceLevel
import io.github.danielperezmartinez.titanssh.config.ResolvedConnection
import io.github.danielperezmartinez.titanssh.config.SessionScript
import io.github.danielperezmartinez.titanssh.config.SessionType
import io.github.danielperezmartinez.titanssh.config.jumpEndpoint
import io.github.danielperezmartinez.titanssh.sftp.DownloadStore
import io.github.danielperezmartinez.titanssh.sftp.SftpBrowser
import io.github.danielperezmartinez.titanssh.sftp.createDownloadStore
import io.github.danielperezmartinez.titanssh.ssh.HostKeyInfo
import io.github.danielperezmartinez.titanssh.ssh.HostKeyRejection
import io.github.danielperezmartinez.titanssh.ssh.HostKeyVerifier
import io.github.danielperezmartinez.titanssh.ssh.KnownHostEntry
import io.github.danielperezmartinez.titanssh.ssh.KnownHostsStore
import io.github.danielperezmartinez.titanssh.ssh.KnownHostsVerifier
import io.github.danielperezmartinez.titanssh.ssh.SshAlgorithmKind
import io.github.danielperezmartinez.titanssh.ssh.SshAuthFailed
import io.github.danielperezmartinez.titanssh.ssh.SshConnectFailed
import io.github.danielperezmartinez.titanssh.ssh.SshConnectionState
import io.github.danielperezmartinez.titanssh.ssh.SshConnector
import io.github.danielperezmartinez.titanssh.ssh.SshCredentials
import io.github.danielperezmartinez.titanssh.ssh.SshException
import io.github.danielperezmartinez.titanssh.ssh.SshHop
import io.github.danielperezmartinez.titanssh.ssh.SshHostKeyRejected
import io.github.danielperezmartinez.titanssh.ssh.SshNoCommonAlgorithm
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import io.github.danielperezmartinez.titanssh.ssh.SshShell
import io.github.danielperezmartinez.titanssh.ssh.toKnownHostEntry
import io.github.danielperezmartinez.titanssh.ssh.trust
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** A tab's failure detail for a refused host key; null [why] when no reason was recorded. */
internal fun hostKeyRejectedDetail(why: HostKeyRejection?): String = when (why) {
    is HostKeyRejection.Changed -> "La clave del host ha cambiado: conexión bloqueada"
    is HostKeyRejection.NotTrusted -> "Host sin clave de confianza: la política STRICT no acepta claves nuevas"
    is HostKeyRejection.Declined, null -> "Clave de host rechazada"
}

/** Status detail for a host that offers no algorithm of some kind that titan-ssh accepts. */
internal fun noCommonAlgorithmDetail(e: SshNoCommonAlgorithm): String {
    val kind = when (e.kind) {
        SshAlgorithmKind.KEY_EXCHANGE -> "intercambio de claves"
        SshAlgorithmKind.HOST_KEY -> "clave de host"
        SshAlgorithmKind.CIPHER -> "cifrado"
        SshAlgorithmKind.MAC -> "integridad (MAC)"
        SshAlgorithmKind.COMPRESSION -> "compresión"
    }
    return "${e.host} solo ofrece algoritmos de $kind que titan-ssh no admite por antiguos o inseguros"
}

/**
 * Lifecycle phase of one terminal tab, surfaced in the tab strip. [PAUSED]: the
 * app let its connection go in the background because the tab had nothing to
 * lose (ADR-0023); it connects again when the app comes back.
 */
enum class TabPhase { CONNECTING, CONNECTED, RECONNECTING, PAUSED, DISCONNECTED, FAILED }

/**
 * A tab's status: its [phase], an optional human [detail] (e.g. a failure
 * reason) and, behind it, the technical [cause] the strip unfolds on demand.
 */
data class TabStatus(val phase: TabPhase, val detail: String? = null, val cause: String? = null)

/**
 * The technical reasons under [e], one `Type: message` line per cause, for the
 * strip's detail panel; null when there are none. [e] itself is left out unless
 * [includeSelf]: its message is usually the strip's detail already.
 */
internal fun failureCause(e: Throwable, includeSelf: Boolean = false): String? {
    val lines = mutableListOf<String>()
    val seen = mutableSetOf<Throwable>()
    var next: Throwable? = if (includeSelf) e else e.cause
    while (next != null && seen.add(next) && lines.size < MAX_CAUSES) {
        val line = (next::class.simpleName ?: "Error") + (next.message?.let { ": $it" } ?: "")
        if (line !in lines) lines += line
        next = next.cause
    }
    return lines.joinToString("\n").ifEmpty { null }
}

/** How deep [failureCause] follows a chain of causes. */
private const val MAX_CAUSES = 6

/**
 * Whether a tab would lose something if its connection went away while the app
 * is in the background, so a foreground service must keep it (ADR-0023): the
 * shell of a level-1 terminal (or one whose level is not known yet and is not
 * waiting for the agent), the tunnels other apps may be using, or a download in
 * progress. A tab with no live connection, a mouse pad, or a terminal at level 2
 * or 3 loses nothing: tmux/screen or the agent keep its PTY on the destination.
 */
internal fun needsConnection(
    type: SessionType,
    phase: TabPhase,
    level: EffectiveLevel?,
    expectsAgent: Boolean,
    hasTunnels: Boolean,
    downloading: Boolean,
): Boolean {
    if (phase != TabPhase.CONNECTED && phase != TabPhase.RECONNECTING) return false
    return when (type) {
        SessionType.MOUSEPAD -> false
        SessionType.SFTP -> downloading
        SessionType.TERMINAL -> hasTunnels || when (level) {
            EffectiveLevel.BASE -> true
            null -> !expectsAgent
            EffectiveLevel.MULTIPLEXER, EffectiveLevel.AGENT -> false
        }
    }
}

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
 * Where a mouse pad tab's input stands (ADR-0016): [ready] once the destination's
 * desktop helper takes input, [blocked] while the desktop is locked or a secure
 * desktop (UAC) is in front, and the [issue] that keeps the mouse pad from
 * working there.
 */
data class MousepadStatus(
    val ready: Boolean = false,
    val blocked: Boolean = false,
    val issue: AgentIssue? = null,
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
 * The server presented a key other than the ones trusted for it (ADR-0005): the
 * connection was blocked. The UI shows [presented] next to [stored]; only the
 * user's deliberate [SessionTab.replaceHostKey] trusts the new key.
 */
data class ChangedHostKey(val presented: HostKeyInfo, val stored: List<KnownHostEntry>)

/**
 * One open terminal tab: owns a single SSH connection and its interactive shell,
 * drives a [TerminalEmulator] from the shell output, and exposes observable
 * [status] and [snapshot] for the UI. Input goes back out through [sendBytes];
 * the terminal echo returns via the output pump.
 *
 * The connection is opened by [start]; failures land in [status] as
 * [TabPhase.FAILED] rather than throwing, so the tab stays visible with its
 * reason. Trust-on-first-use prompts surface through [pendingHostKey], and a
 * key that differs from the trusted ones through [changedHostKey].
 *
 * A host with ProxyJump connects through its [ResolvedConnection.jumps], never
 * directly; each jump host's key goes through the same checks under its own
 * policy, and a refused one names it in [status].
 *
 * ## Resilience level 1 ([[Resiliencia de sesión ante microcortes de red]])
 * Once a session has been live, a network micro-cut does not close the tab: the
 * [TerminalEmulator] (screen + scrollback) is kept as-is, [status] shows
 * [TabPhase.RECONNECTING], and the tab reconnects with backoff per
 * [ReconnectPolicy]. On success it opens a fresh shell into the *same* emulator —
 * so the scrollback is preserved for the user — and replays automation through
 * [ShellAutomation.onReconnected] (honoring the session's `ReconnectBehavior`). A
 * clean remote exit (the transport stays up) ends the tab instead of reconnecting.
 *
 * The wait between attempts ends early on [reconnectNow] (the user) or
 * [onNetworkRestored] (the platform). A tab that gave up, ended or failed keeps
 * its emulator, so [reconnectNow] brings it back without losing the scrollback
 * ([[Reconexión que no se rinde tras un corte largo]]).
 *
 * ## Tunnels ([[Ejecutar los túneles de las sesiones]])
 * The session's enabled tunnels open on every connection, right after it is
 * established, and close when it ends, so a reconnect reopens them on the new
 * one. Their state is in [tunnels]; a failed tunnel never ends the session.
 *
 * ## Mouse pad (ADR-0016)
 * A [SessionType.MOUSEPAD] session uses the same connection lifecycle, but
 * instead of a shell it installs the agent and drives `titan-agent --input`
 * ([InputTransport]): [sendInput] sends the touchpad and keyboard events, and
 * [mousepad] says whether they reach the desktop. No tunnels, scripts or
 * emulator output are involved.
 *
 * ## SFTP (ADR-0021)
 * A [SessionType.SFTP] session also keeps the lifecycle, and on each connection
 * opens an SFTP channel and hands it to [sftp], the browser, until the
 * connection drops: then the browser waits, its downloads pause, and the tab
 * reconnects as usual. Nothing runs on the destination.
 *
 * ## In the background (ADR-0023)
 * [keepAlive] says whether the tab would lose something without its
 * connection ([needsConnection]); the app keeps a foreground service while any
 * tab does. Otherwise it may [pause] the tab: the connection closes cleanly
 * ([TabPhase.PAUSED]) and [resume] connects again, like after a drop. While the
 * platform blocks the app's network ([networkBlocked]) no attempt is made, and
 * that time does not count towards [ReconnectPolicy.giveUpAfterMillis].
 */
class SessionTab(
    val id: String,
    val resolved: ResolvedConnection,
    private val connector: SshConnector,
    /** Fresh credentials for an auth: the destination's, and each jump host's. */
    private val credentials: suspend (HostAuth) -> SshCredentials,
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
    /**
     * Told once the agent serves this tab, with a control handle on the same
     * connection, so the app can close pending sessions and refresh what it
     * knows about that agent ([[Transparencia y control del agente en el destino]]).
     */
    private val agentObserver: AgentObserver? = null,
    /** Clock for the reconnect time budget; tests pass their virtual one. */
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /**
     * Which of the saved session's instances on the destination this tab shows
     * (ADR-0020): its id names the agent session and the tmux/screen session.
     */
    val instance: TabInstance = TabInstance(resolved.session.id),
    /** Where an SFTP tab saves its downloads; only asked for by one. */
    downloadStore: () -> DownloadStore = ::createDownloadStore,
    /**
     * For a file browser opened from a terminal tab: that tab, whose SSH
     * connection it borrows while the tab is open (see "SFTP").
     */
    val explorerOf: SessionTab? = null,
    /** Shown with the file browser's start folder, e.g. that the terminal's directory is unknown ([SftpListing.notice]). */
    sftpStartNote: String? = null,
    /** Whether the platform blocks the app's network now ([networkBlocked]). */
    private val networkBlocked: StateFlow<Boolean> = MutableStateFlow(false),
) {
    private val _customName = MutableStateFlow<String?>(null)

    /**
     * The name the user gave the instance this tab shows, null for none. At
     * level 3 it mirrors the agent's (the app keeps it in sync); elsewhere it
     * lives only in this tab.
     */
    val customName: StateFlow<String?> = _customName.asStateFlow()

    /** Sets [customName]; blank takes it away. */
    fun rename(name: String?) {
        _customName.value = name?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** The name the user gave the instance, or the saved session's name with the instance number for a parallel one. */
    val title: String get() = SessionInstances.label(resolved.session.name, instance.number.value, _customName.value)

    /** Whether this tab is a mouse pad rather than a terminal (ADR-0016). */
    val isMousepad: Boolean get() = resolved.session.type == SessionType.MOUSEPAD

    /** Whether this tab is a terminal: not a mouse pad nor a file browser. */
    val isTerminal: Boolean get() = resolved.session.type == SessionType.TERMINAL

    /** The file browser of an SFTP tab (ADR-0021); null for the other types. */
    val sftp: SftpBrowser? =
        if (resolved.session.type == SessionType.SFTP) {
            SftpBrowser(scope, resolved.session.initialDirectory, downloadStore, startNote = sftpStartNote)
        } else {
            null
        }

    private val _mousepad = MutableStateFlow(MousepadStatus())

    /** A mouse pad tab's input state, for its view. */
    val mousepad: StateFlow<MousepadStatus> = _mousepad.asStateFlow()

    /** The live `--input` channel of a mouse pad tab; null while not connected. */
    private var input: InputTransport? = null

    /**
     * Input events in order, from the UI to the channel. Whatever is still
     * queued when a connection starts is from before it and is dropped.
     */
    private val inputQueue = Channel<AgentFrame>(Channel.UNLIMITED)

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

    private val _changedHostKey = MutableStateFlow<ChangedHostKey?>(null)

    /** Set while the last attempt was blocked by a changed host key. */
    val changedHostKey: StateFlow<ChangedHostKey?> = _changedHostKey.asStateFlow()

    private val sessionTunnels = SessionTunnels(resolved.session.tunnels)

    /** The session's enabled tunnels and whether each is open ([[Ejecutar los túneles de las sesiones]]). */
    val tunnels: StateFlow<List<TunnelStatus>> = sessionTunnels.status

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
        set(value) {
            field = value
            _liveSession.value = value
        }

    private val _liveSession = MutableStateFlow<SshSession?>(null)

    /** The tab's SSH connection while it has one; a file browser opened from it borrows it. */
    val liveSession: StateFlow<SshSession?> = _liveSession.asStateFlow()

    private val _closedState = MutableStateFlow(false)

    /** The agent's control commands over this tab's connection, while the agent serves it. */
    private var agentControl: AgentControl? = null

    private var shell: SshShell? = null
    private var connectJob: Job? = null
    private var automationJob: Job? = null
    private var tunnelRetryJob: Job? = null

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

    /** The destination's shell once probed; the host stays the same across reconnects. */
    private var remoteShell: RemoteShell? = null

    /**
     * The shell the automation types for, which decides whether its lines can be
     * erased ([concealAutomation]); null until known. Unlike [remoteShell] it also
     * holds the POSIX fallback of a failed probe, as the automation uses it.
     * Guarded by [emulatorLock].
     */
    private var concealShell: RemoteShell? = null

    /** A marker arrived while [concealShell] was unknown; hide it once the shell is known. */
    private var concealDeferred = false

    /** Set once by [close] so the reconnect loop stops instead of retrying. */
    private var closed = false

    /** Whether a session in this tab has been live, so the next connection is a reconnect. */
    private var everConnected = false

    /** Set when the reconnect loop ran out of [ReconnectPolicy]; the network coming back retries. */
    private var gaveUp = false

    /** Cuts a reconnect backoff short: [reconnectNow], [onNetworkRestored] or [pause]. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Set by [pause] until [resume]: the run loop holds the tab in [TabPhase.PAUSED]. */
    private val paused = MutableStateFlow(false)

    /**
     * Whether this tab would lose something without its connection while the
     * app is in the background ([needsConnection], ADR-0023).
     */
    val keepAlive: Flow<Boolean> = combine(
        _status,
        _resilience,
        sessionTunnels.status,
        sftp?.transfers ?: flowOf(emptyList()),
    ) { status, resilience, tunnels, transfers ->
        needsConnection(
            type = resolved.session.type,
            phase = status.phase,
            level = resilience.level,
            expectsAgent = agentDeployer != null &&
                resolved.session.resilienceLevel == ResilienceLevel.AGENT &&
                resilience.issue?.isWarning != false,
            hasTunnels = tunnels.isNotEmpty(),
            downloading = transfers.any { !it.finished },
        )
    }.distinctUntilChanged()

    /** Reason of the last establish failure, surfaced if the tab gives up. */
    private var lastFailure: String? = null

    /** The technical cause behind [lastFailure] ([failureCause]). */
    private var lastFailureCause: String? = null

    /** [lastFailure] and its cause, for the detail panel while reconnecting or once given up. */
    private fun lastFailureDetail(): String? =
        listOfNotNull(lastFailure, lastFailureCause).joinToString("\n").ifEmpty { null }

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

        /**
         * The attempt ran out while the host key prompt waited and the user then
         * trusted the key: connect again at once, the key is now known.
         */
        TRUSTED_LATE,

        /** The terminal tab a file browser borrowed from was closed: connect on its own at once. */
        SOURCE_CLOSED,
    }

    /** Starts the connection. Idempotent: a second call is a no-op. */
    fun start() {
        if (connectJob != null) return
        connectJob = scope.launch { runSession() }
    }

    /**
     * The user's "reconectar": ends a reconnect backoff at once, or connects
     * again a tab that gave up, ended or failed, in the same emulator. While an
     * attempt is in flight it only shortens the next wait.
     */
    fun reconnectNow() {
        if (closed || connectJob == null) return
        if (connectJob?.isActive == true) {
            wake.trySend(Unit)
        } else {
            gaveUp = false
            connectJob = scope.launch { runSession(immediate = true) }
        }
    }

    /**
     * The platform saw the network come back: a tab waiting to reconnect tries
     * now, and one that gave up tries again. A tab that ended cleanly or never
     * connected is left alone.
     */
    fun onNetworkRestored() {
        if (paused.value) return
        if (_status.value.phase == TabPhase.RECONNECTING || gaveUp) reconnectNow()
    }

    /**
     * Lets the connection go while the app is in the background, because the
     * tab has nothing to lose ([keepAlive] is false; ADR-0023). The connection
     * closes cleanly, so the agent and tmux/screen keep the PTY on the
     * destination, and the tab waits in [TabPhase.PAUSED] until [resume]. A tab
     * with no connection to keep (it ended, failed or gave up) is left alone.
     */
    suspend fun pause() {
        if (closed || paused.value) return
        if (_status.value.phase == TabPhase.DISCONNECTED || _status.value.phase == TabPhase.FAILED) return
        paused.value = true
        wake.trySend(Unit)
        // Say goodbye to the agent first, so it keeps the session as is.
        runCatching { agent?.close() }
        runCatching { input?.close() }
        runCatching { shell?.close() }
        runCatching { session?.close() }
    }

    /** Connects a [pause]d tab again at once, as after a drop. */
    fun resume() {
        paused.value = false
    }

    /**
     * Holds the loop while the tab is paused. Returns whether it was, so the
     * caller connects again at once with a fresh time budget.
     */
    private suspend fun awaitResume(): Boolean {
        if (!paused.value) return false
        _status.value = TabStatus(TabPhase.PAUSED, PAUSED_DETAIL)
        combine(paused, _closedState) { p, c -> !p || c }.first { it }
        return true
    }

    /**
     * Holds the loop while the platform blocks the app's network: an attempt
     * could not get out. Returns how long it waited, which the caller leaves
     * out of the time budget.
     */
    private suspend fun awaitNetwork(): Duration {
        if (!networkBlocked.value) return Duration.ZERO
        val mark = timeSource.markNow()
        if (everConnected) _status.value = TabStatus(TabPhase.RECONNECTING, NETWORK_BLOCKED_DETAIL, lastFailureDetail())
        combine(networkBlocked, paused, _closedState) { blocked, p, c -> !blocked || p || c }.first { it }
        return mark.elapsedNow()
    }

    /**
     * The connect-then-reconnect lifecycle. The first connection is a plain
     * attempt; once a session has been live, drops and failed re-establishes are
     * ridden out with backoff until [ReconnectPolicy.giveUpReason] says stop.
     * [immediate] skips the first backoff (the user asked to reconnect).
     */
    private suspend fun runSession(immediate: Boolean = false) {
        var attempt = 0
        var downSince: TimeMark? = null
        // Time spent with the network blocked since [downSince]: not tried, not counted.
        var blockedFor = Duration.ZERO
        var now = immediate
        while (!closed) {
            if (awaitResume()) {
                now = true
                attempt = 0
                downSince = null
                blockedFor = Duration.ZERO
            }
            if (closed) return
            if (everConnected && !now) {
                attempt++
                val since = downSince ?: timeSource.markNow().also { downSince = it }
                reconnect.giveUpReason(attempt, since.elapsedNow() - blockedFor)?.let { reason ->
                    gaveUp = true
                    _status.value = TabStatus(TabPhase.DISCONNECTED, reason, lastFailureDetail())
                    return
                }
                _status.value = TabStatus(TabPhase.RECONNECTING, reconnect.progress(attempt), lastFailureDetail())
                awaitBackoff(reconnect.backoffMillis(attempt))
                if (closed) return
                if (paused.value) continue
            } else {
                _status.value = TabStatus(if (everConnected) TabPhase.RECONNECTING else TabPhase.CONNECTING)
            }
            now = false
            blockedFor += awaitNetwork()
            if (closed) return
            if (paused.value) continue

            val result = connectOnce(reconnecting = everConnected)
            // A pause closes the connection under the shell or the agent, which
            // can look like the user leaving it: it is not.
            when (if (paused.value && result == AttemptResult.CLEAN_EXIT) AttemptResult.DROPPED else result) {
                AttemptResult.CLEAN_EXIT -> {
                    _status.value = TabStatus(TabPhase.DISCONNECTED, "Sesión finalizada")
                    return
                }
                AttemptResult.FATAL -> return // status is already FAILED
                AttemptResult.DROPPED -> {
                    // Was live, now lost: (re)start the reconnect loop with a
                    // fresh backoff and time budget.
                    attempt = 0
                    downSince = null
                    blockedFor = Duration.ZERO
                }
                AttemptResult.TRUSTED_LATE, AttemptResult.SOURCE_CLOSED -> now = true
                AttemptResult.ESTABLISH_FAILED -> {
                    if (!everConnected) {
                        _status.value = TabStatus(TabPhase.FAILED, lastFailure ?: "Fallo de conexión", lastFailureCause)
                        return
                    }
                    // A reconnect attempt could not reach the host yet; keep the
                    // loop going (the attempt counter already advanced above).
                }
            }
        }
    }

    /** Waits [millis] or until a [wake] sent during the wait (an older one is stale). */
    private suspend fun awaitBackoff(millis: Long) {
        wake.tryReceive()
        withTimeoutOrNull(millis) { wake.receive() }
    }

    /** The shell or agent PTY is live: later connections are reconnects. */
    private fun markConnected() {
        everConnected = true
        lastFailure = null
        lastFailureCause = null
        _status.value = TabStatus(TabPhase.CONNECTED)
    }

    /**
     * One connection attempt: connect, open a shell, run automation and pump the
     * shell output into the emulator until it ends, then classify why it ended.
     */
    private suspend fun connectOnce(reconnecting: Boolean): AttemptResult {
        val source = explorerOf
        val browser = sftp
        if (source != null && browser != null && !source._closedState.value) return borrowOnce(source, browser)
        // First contact asks the user inside the SSH handshake, and the prompt can
        // outlive it: sshj's key exchange (or the server's login grace time) runs
        // out meanwhile and sshj interrupts the verifier. So the answer lives here,
        // not in the verifier, and a key trusted late connects again. Each jump
        // host is checked like the destination, under its own policy.
        _changedHostKey.value = null
        var lastPrompted: HostTrust? = null
        val jumpTrusts = resolved.jumps.map { jump ->
            HostTrust(jump.hostKeyPolicy, jumpHost = jump.alias.ifBlank { jump.hostname }) { lastPrompted = it }
        }
        val trust = HostTrust(resolved.host.hostKeyPolicy, jumpHost = null) { lastPrompted = it }
        val trusts = jumpTrusts + trust
        val creds = mutableListOf<SshCredentials>()
        try {
            resolved.jumps.forEach { creds += credentials(it.auth) }
            creds += credentials(resolved.auth)
        } catch (e: Exception) {
            creds.forEach { it.wipe() }
            _status.value = TabStatus(TabPhase.FAILED, e.message ?: "Could not read credentials", failureCause(e))
            return AttemptResult.FATAL
        }
        var opened: SshSession? = null
        try {
            opened = try {
                connector.connect(
                    endpoint = resolved.endpoint,
                    credentials = creds.last(),
                    hostKeyVerifier = trust.verifier,
                    keepAliveSeconds = resolved.host.keepAliveSeconds,
                    via = resolved.jumps.mapIndexed { i, jump ->
                        SshHop(jump.jumpEndpoint, creds[i], jumpTrusts[i].verifier, jump.keepAliveSeconds)
                    },
                )
            } catch (e: SshConnectFailed) {
                // Hosts are checked in order: only the last one asked can still be waiting.
                val pending = lastPrompted ?: throw e
                // The prompt is still up: the user's answer decides.
                if (!pending.answer.await()) throw SshHostKeyRejected("Host key rejected after the attempt timed out", e)
                pending.verified.await() // let an uninterrupted verifier finish saving it
                knownHostsStore.trust(pending.prompted.await())
                return AttemptResult.TRUSTED_LATE
            }
            session = opened
            // Paused while it connected: [pause] found no connection to close.
            if (paused.value) return AttemptResult.DROPPED
            if (isMousepad) return runMousepad(opened)
            sftp?.let { return runSftp(it, opened) }
            // Tunnels ride the SSH connection itself, whatever carries the PTY.
            sessionTunnels.open(opened)
            tunnelRetryJob = scope.launch { sessionTunnels.retryFailed(opened) }

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
            markConnected()
            // Level 1 unless the automation finds a multiplexer, which it reports.
            _resilience.value = _resilience.value.copy(
                level = if (resolved.session.resilienceLevel == ResilienceLevel.BASE) EffectiveLevel.BASE else null,
                multiplexer = null,
            )

            // Fresh tee (empty replay) fed by pumpOutput below; the automation
            // runs concurrently with painting so it can wait on prompts/sentinels.
            val tee = newTee()
            outputTee = tee
            val io = ShellIo(newShell, tee.asSharedFlow(), ::onMultiplexer, instance) { detectShell(opened) }
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
            val refused = trusts.firstOrNull { it.rejection.isCompleted }
            val why = refused?.rejection?.await()
            if (why is HostKeyRejection.Changed) _changedHostKey.value = ChangedHostKey(why.presented, why.stored)
            val detail = hostKeyRejectedDetail(why)
            _status.value = TabStatus(TabPhase.FAILED, refused?.jumpHost?.let { "Bastión $it: $detail" } ?: detail)
            return AttemptResult.FATAL
        } catch (e: SshAuthFailed) {
            _status.value = TabStatus(TabPhase.FAILED, e.message ?: "Autenticación rechazada", failureCause(e))
            return AttemptResult.FATAL
        } catch (e: SshNoCommonAlgorithm) {
            _status.value = TabStatus(TabPhase.FAILED, noCommonAlgorithmDetail(e), failureCause(e))
            return AttemptResult.FATAL
        } catch (e: SshException) {
            lastFailure = e.message ?: "Fallo de conexión"
            lastFailureCause = failureCause(e)
            return AttemptResult.ESTABLISH_FAILED
        } catch (e: Exception) {
            lastFailure = e.message ?: "Error inesperado"
            // Not one of ours: its own type is part of the story.
            lastFailureCause = failureCause(e, includeSelf = true)
            return AttemptResult.ESTABLISH_FAILED
        } finally {
            creds.forEach { it.wipe() }
            automationJob?.cancel()
            tunnelRetryJob?.cancel()
            sessionTunnels.close()
            liveIo = null
            agentControl = null
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
        markConnected()
        // Same tee as the shell path, fed from the agent's DATA; the
        // automation's input goes out as INPUT frames.
        val tee = newTee()
        outputTee = tee
        val io = ShellIo({ bytes -> agent?.sendInput(bytes) }, tee.asSharedFlow()) { detectShell(opened) }
        liveIo = io
        var transport: AgentTransport
        while (true) {
            transport = AgentTransport(
                session = opened,
                agentSessionId = instance.id,
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
                    instance.claimed()
                    agentControl = AgentControl(opened, agentLaunch)
                    _resilience.value = _resilience.value.copy(level = EffectiveLevel.AGENT)
                    if (fresh) {
                        launchAutomation {
                            automation.onAgentSessionCreated(io, resolved, afterDrop = reconnecting)
                        }
                    } else {
                        // The replay can hold the lines of an earlier run's
                        // automation; whether they can be erased depends on the shell.
                        scope.launch { runCatching { detectShell(opened) } }
                    }
                    agentObserver?.let { observer ->
                        scope.launch { observer.onAgentReady(AgentKey.of(resolved.endpoint), AgentControl(opened, agentLaunch)) }
                    }
                },
                requireFresh = instance.mustBeNew,
            )
            agent = transport
            transport.run()
            if (!transport.occupied) break
            // A new parallel instance whose number was already alive (opened
            // from another device since the app last looked): take the next.
            agent = null
            instance.skipTaken()
        }
        agentOffset = transport.appliedOffset
        transport.unavailable?.let { issue ->
            agent = null
            return degrade(issue)
        }
        if (droppedWithin(opened)) return AttemptResult.DROPPED
        // The agent's channel closed with the connection still up: the session
        // was closed, or only the daemon went away (an update, ADR-0022) while
        // the session lives on in its host. Reattach to it in the second case.
        return if (agentSessionAlive(opened, agentLaunch)) AttemptResult.DROPPED else AttemptResult.CLEAN_EXIT
    }

    /** Whether the agent on [opened] still holds this tab's session, alive. */
    private suspend fun agentSessionAlive(opened: SshSession, launch: AgentLaunch): Boolean {
        val id = AgentTransport.sanitizeId(instance.id)
        return runCatching {
            AgentControl(opened, launch).status().sessions.any { it.id == id && !it.closed }
        }.getOrDefault(false)
    }

    /**
     * The mouse pad on [opened]: install the agent and drive `titan-agent
     * --input` until its channel closes. A destination where the mouse pad
     * cannot work fails the tab with the reason, and is not retried on its own:
     * the user reconnects once it is fixed (say, after signing in).
     */
    private suspend fun runMousepad(opened: SshSession): AttemptResult {
        val deployer = agentDeployer ?: return mousepadFailed(
            AgentIssue(AgentDiagnostics.E_NO_BINARY, "this build has no agent"),
        )
        val launch = when (val deployment = deployer.ensureInstalled(opened)) {
            is AgentDeployment.Unavailable -> return mousepadFailed(deployment.issue)
            is AgentDeployment.Ready -> deployment.launch
        }
        // Drop what was queued before this connection.
        while (inputQueue.tryReceive().isSuccess) {
            continue
        }
        val transport = InputTransport(opened, launch) { blocked ->
            if (_status.value.phase != TabPhase.CONNECTED) markConnected()
            _mousepad.value = MousepadStatus(ready = true, blocked = blocked)
        }
        input = transport
        val pump = scope.launch { for (frame in inputQueue) transport.send(frame) }
        try {
            transport.run()
        } finally {
            pump.cancel()
            input = null
        }
        transport.unavailable?.let { return mousepadFailed(it) }
        _mousepad.value = MousepadStatus()
        return if (droppedWithin(opened)) AttemptResult.DROPPED else AttemptResult.CLEAN_EXIT
    }

    /**
     * The SFTP browser on [opened]: one SFTP channel for this connection, lent
     * to [browser] until the connection drops. A server without the `sftp`
     * subsystem fails the tab; retrying would not change that.
     */
    private suspend fun runSftp(browser: SftpBrowser, opened: SshSession): AttemptResult {
        val channel = opened.openSftp() ?: run {
            _status.value = TabStatus(TabPhase.FAILED, "El destino no ofrece SFTP (subsistema sftp desactivado en su sshd)")
            return AttemptResult.FATAL
        }
        markConnected()
        // The keepalive notices a dead link late; a request that times out on it
        // tells sooner ([SftpBrowser]). Whichever comes first ends this connection.
        val lost = CompletableDeferred<Unit>()
        try {
            browser.attach(channel) { lost.complete(Unit) }
            merge<Any>(
                opened.state.filter { it == SshConnectionState.DISCONNECTED || it == SshConnectionState.FAILED },
                flow { emit(lost.await()) },
            ).first()
        } finally {
            browser.detach(channel)
            // Closing a channel on a dead link can wait for sshj's timeout:
            // do it aside so the tab says it is reconnecting right away.
            scope.launch { runCatching { channel.close() } }
        }
        if (!closed) _status.value = TabStatus(TabPhase.RECONNECTING)
        // There is no clean exit to tell apart: the connection went away.
        return AttemptResult.DROPPED
    }

    /**
     * One attempt of a file browser opened from terminal tab [source]: waits
     * for that tab's connection and opens an SFTP channel on it ([runSftp]),
     * without owning or closing the connection. When the terminal reconnects,
     * the browser follows it to the new connection; once the terminal tab is
     * closed, the browser connects on its own ([AttemptResult.SOURCE_CLOSED]).
     */
    private suspend fun borrowOnce(source: SessionTab, browser: SftpBrowser): AttemptResult {
        val borrowed = combine(source.liveSession, source._closedState) { live, closed -> live to closed }
            .first { (live, closed) -> live != null || closed }
            .first
            ?: return AttemptResult.SOURCE_CLOSED
        return try {
            runSftp(browser, borrowed)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // The terminal's connection went away under the request.
            if (everConnected) return AttemptResult.DROPPED
            lastFailure = e.message ?: "No se pudo abrir el explorador"
            lastFailureCause = failureCause(e)
            AttemptResult.ESTABLISH_FAILED
        }
    }

    private fun mousepadFailed(issue: AgentIssue): AttemptResult {
        _mousepad.value = MousepadStatus(issue = issue)
        _status.value = TabStatus(TabPhase.FAILED, AgentDiagnostics.describeMousepad(issue))
        return AttemptResult.FATAL
    }

    /**
     * Sends one mouse pad event (see [AgentFrame]'s input frames). Events keep
     * their order; while the tab is not connected they are dropped.
     */
    fun sendInput(frame: AgentFrame) {
        if (input == null) return
        inputQueue.trySend(frame)
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

    /** The agent's control commands over this tab's own connection; null unless the agent serves it now. */
    val liveAgentControl: AgentControl? get() = agentControl

    /**
     * The terminal's current directory on the destination, when it can be
     * told for sure: the agent's report of the shell (level 3; only Linux
     * reports it), tmux's active pane (level 2), or what the shell announced
     * with OSC 7. Null otherwise (screen, a shell that sends no OSC 7,
     * PowerShell on Windows…), and the caller starts from the home folder.
     */
    suspend fun currentDirectory(): String? {
        agentControl?.let { control ->
            val id = SessionInstances.agentId(instance.savedSessionId, instance.number.value)
            withTimeoutOrNull(DIRECTORY_QUERY_TIMEOUT_MILLIS) { runCatching { control.status() }.getOrNull() }
                ?.sessions?.firstOrNull { it.id == id && !it.closed }
                ?.cwd?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        val live = session
        if (live != null && _resilience.value.multiplexer == TerminalMultiplexer.Kind.TMUX) {
            val command = TerminalMultiplexer.tmuxDirectoryCommand(TerminalMultiplexer.nameFor(instance.id))
            withTimeoutOrNull(DIRECTORY_QUERY_TIMEOUT_MILLIS) { runCatching { live.execCollect(command) }.getOrNull() }
                ?.takeIf { it.exitStatus == null || it.exitStatus == 0 }
                ?.stdout?.trim()
                ?.takeIf { it.startsWith("/") }
                ?.let { return it }
        }
        return emulatorLock.withLock { emulator.workingDirectory }
    }

    /**
     * Which shell reads the tab's input, probed over an `exec` channel of
     * [session] ([ShellSyntax.PROBE]): the sshd runs `exec` commands with the
     * same shell it gives the PTY, and so does `titan-agent` on Windows. POSIX
     * when the probe fails (not cached, so the next connection asks again).
     */
    private suspend fun detectShell(session: SshSession): RemoteShell {
        val shell = remoteShell ?: probeShell(session)
        onShellKnown(shell)
        return shell
    }

    private suspend fun probeShell(session: SshSession): RemoteShell {
        val output = withTimeoutOrNull(SHELL_PROBE_TIMEOUT_MILLIS) {
            runCatching {
                val channel = session.exec(ShellSyntax.PROBE)
                try {
                    channel.output.fold(StringBuilder()) { acc, chunk -> acc.append(chunk.decodeToString()) }.toString()
                } finally {
                    runCatching { channel.close() }
                }
            }.getOrNull()
        } ?: return RemoteShell.POSIX
        return ShellSyntax.parseProbe(output).also { remoteShell = it }
    }

    /** Lets [concealAutomation] act for [shell], and hides the markers it had to leave meanwhile. */
    private suspend fun onShellKnown(shell: RemoteShell) {
        emulatorLock.withLock {
            concealShell = shell
            if (!concealDeferred) return
            concealDeferred = false
            concealPending = conceal(shell)
            _snapshot.value = emulator.snapshot()
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
        val replies = emulatorLock.withLock {
            emulator.feed(bytes)
            concealAutomation(bytes)
            _snapshot.value = emulator.snapshot()
            emulator.takeResponses()
        }
        // Status queries (cursor position, device attributes) some shells wait on.
        if (replies != null) sendBytes(replies)
    }

    /** Last bytes of the previous chunk, so a marker split across two chunks is still seen. */
    private var markerCarry = ByteArray(0)

    /** A marker line was still being written; look again on the next chunk. */
    private var concealPending = false

    /**
     * Hides the automation's own lines from the terminal: the completion
     * sentinels of [ScriptRunner] and the probes of [TerminalMultiplexer], both
     * the echoed command and what it prints. They are typed into the user's
     * shell because only the shell knows when a command ends, but they mean
     * nothing to the user. A POSIX terminal loses those lines; a Windows
     * console (ConPTY) repaints by absolute position and would not match a
     * screen with lines taken out, so there they are blanked in their place.
     * Inside tmux/screen (the alternate screen) the lines stay.
     *
     * Nothing is hidden while the shell is unknown ([concealShell]): a re-attach
     * replays an earlier run's markers before its probe answers. They are
     * hidden then, in [onShellKnown].
     */
    private fun concealAutomation(bytes: ByteArray) {
        val window = markerCarry + bytes
        markerCarry = window.copyOfRange(maxOf(0, window.size - MARKER_PREFIX.size + 1), window.size)
        if (!concealPending && !window.containsAscii(MARKER_PREFIX)) return
        val shell = concealShell
        if (shell == null) {
            concealDeferred = true
            return
        }
        concealPending = conceal(shell)
    }

    /** Hides every automation line on screen for [shell]; true while one is still being written. */
    private fun conceal(shell: RemoteShell): Boolean =
        if (shell == RemoteShell.POSIX) emulator.eraseLinesMatching(AUTOMATION_LINE)
        else emulator.blankLinesMatching(CONSOLE_AUTOMATION_LINE, UNFINISHED_MARKER)

    /**
     * The host key checks of one host in an attempt: the destination, or the
     * jump host [jumpHost] (its name). [onPrompt] is told when it asks the user.
     */
    private inner class HostTrust(
        policy: HostKeyPolicy,
        val jumpHost: String?,
        onPrompt: (HostTrust) -> Unit,
    ) {
        val prompted = CompletableDeferred<HostKeyInfo>()
        val answer = CompletableDeferred<Boolean>()
        val verified = CompletableDeferred<Unit>()
        val rejection = CompletableDeferred<HostKeyRejection>()

        private val knownHosts = KnownHostsVerifier(
            store = knownHostsStore,
            acceptNewHosts = policy == HostKeyPolicy.TOFU,
            onRejected = { rejection.complete(it) },
        ) { info ->
            prompted.complete(info)
            onPrompt(this)
            promptHostKey(info, answer)
        }

        val verifier = object : HostKeyVerifier {
            override suspend fun verify(info: HostKeyInfo): Boolean =
                try {
                    knownHosts.verify(info)
                } finally {
                    verified.complete(Unit)
                }

            override suspend fun knownKeyTypes(host: String, port: Int): List<String> =
                knownHosts.knownKeyTypes(host, port)
        }
    }

    /**
     * Shows [info] for the user to trust and waits for [answer]. The prompt stays
     * up until the user answers, even if the waiting verifier is cancelled.
     */
    private suspend fun promptHostKey(info: HostKeyInfo, answer: CompletableDeferred<Boolean>): Boolean {
        val pending = PendingHostKey(info, answer)
        _pendingHostKey.value = pending
        answer.invokeOnCompletion { _pendingHostKey.compareAndSet(pending, null) }
        return answer.await()
    }

    /**
     * Trusts the key in [changedHostKey] in place of the ones stored for its
     * host and connects again. The UI calls it only once the user has confirmed
     * it on purpose.
     */
    suspend fun replaceHostKey() {
        val changed = _changedHostKey.value ?: return
        knownHostsStore.replace(changed.presented.toKnownHostEntry())
        _changedHostKey.value = null
        reconnectNow()
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
        _closedState.value = true
        sftp?.close()
        // Unblocks sshj's reader thread if a host key prompt is still open.
        _pendingHostKey.value?.reject()
        automationJob?.cancel()
        connectJob?.cancel()
        tunnelRetryJob?.cancel()
        sessionTunnels.close()
        runCatching { input?.close() }
        runCatching { agent?.close() }
        runCatching { shell?.close() }
        runCatching { session?.close() }
        input = null
        agent = null
        shell = null
        session = null
    }
}

/** Status detail of a tab [SessionTab.pause]d in the background. */
private const val PAUSED_DETAIL = "En pausa en segundo plano: vuelve a conectar al abrir la app"

/** Status detail while the platform blocks the app's network. */
private const val NETWORK_BLOCKED_DETAIL = "El sistema no deja usar la red a la app: espera a que se desbloquee"

/** How long the shell probe may take before the automation assumes a POSIX shell. */
private const val SHELL_PROBE_TIMEOUT_MILLIS = 5_000L

/** How long [SessionTab.currentDirectory] waits for each way of asking. */
private const val DIRECTORY_QUERY_TIMEOUT_MILLIS = 5_000L

/** What every automation marker starts with: `__TITAN_…__` sentinels and `TITANMUX_…` probes. */
private val MARKER_PREFIX = "TITAN".encodeToByteArray()

/** A line the automation typed or printed: it carries one of its random tokens. */
private val AUTOMATION_LINE = Regex("__TITAN_[0-9a-f]{16}__|TITANMUX_[0-9a-f]{16}")

private fun ByteArray.containsAscii(needle: ByteArray): Boolean {
    if (needle.isEmpty() || size < needle.size) return false
    outer@ for (i in 0..size - needle.size) {
        for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
        return true
    }
    return false
}

/**
 * [AUTOMATION_LINE] on a Windows console, reaching the end of the automation's
 * line as [TerminalEmulator.blankLinesMatching] needs: a line ends in a token,
 * or in `')` after it (the PowerShell sentinel).
 */
private val CONSOLE_AUTOMATION_LINE = Regex("""__TITAN_[0-9a-f]{16}__(?:'\))?""")

/**
 * What follows a finished token on a line that may still go on: part of a
 * `__TITAN_…__` token, or nothing or `'` of the `')` that closes a PowerShell
 * sentinel.
 */
private val UNFINISHED_MARKER = Regex("^'?$|_(_(T(I(T(A(N(_[0-9a-f]{0,16}_?)?)?)?)?)?)?)?$")
