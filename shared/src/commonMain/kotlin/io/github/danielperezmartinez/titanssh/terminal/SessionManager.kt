package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.Ids
import io.github.danielperezmartinez.titanssh.config.ResolvedConnection
import io.github.danielperezmartinez.titanssh.config.SessionType
import io.github.danielperezmartinez.titanssh.rdp.RdpSupport
import io.github.danielperezmartinez.titanssh.ssh.KnownHostsStore
import io.github.danielperezmartinez.titanssh.ssh.SshConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * Owns the set of open terminal tabs and the launch flow that turns a resolved
 * configuration into a live session in a tab — the wiring the "Sesiones" launcher
 * was left waiting for by [[Panel de gestión de hosts y sesiones]].
 *
 * The ordering/active logic is delegated to the pure [TabList]; this class adds
 * the runtime [SessionTab]s and the SSH engine dependencies. Exposes an ordered
 * [tabs] list and the [activeId] for the UI to observe.
 */
class SessionManager(
    private val scope: CoroutineScope,
    private val connector: SshConnector,
    private val credentialResolver: CredentialResolver,
    private val knownHostsStore: KnownHostsStore,
    /** Start-scripts automation run on connect; no-op by default (see [SessionTab]). */
    private val automation: ShellAutomation = ShellAutomation.None,
    /** Client-side reconnection cadence for level-1 resilience (see [SessionTab]). */
    private val reconnect: ReconnectPolicy = ReconnectPolicy.Default,
    /**
     * Level-3 agent deployer (ADR-0008). Null by default, so `AGENT` sessions
     * degrade to level 2/1; provide one (built in `jvmShared` via `agentDeployer`,
     * with bundled binaries) to enable the persistent agent.
     */
    private val agentDeployer: AgentDeployer? = null,
    /** Told when the agent serves a tab (see [SessionTab]); the app passes its [AgentWatch]. */
    private val agentObserver: AgentObserver? = null,
    /**
     * Emits when the network comes back ([networkRestored] on the platform);
     * each tab then retries at once if it was waiting or had given up.
     */
    networkRestored: Flow<Unit> = emptyFlow(),
    /**
     * Whether the platform blocks the app's network ([networkBlocked]); tabs
     * do not try to connect meanwhile (ADR-0023).
     */
    networkBlocked: Flow<Boolean> = emptyFlow(),
    /** The embedded RDP client for RDP tabs (ADR-0028); null where the app has none. */
    private val rdpSupport: RdpSupport? = null,
) {
    private val blocked = MutableStateFlow(false)

    private val byId = mutableMapOf<String, SessionTab>()

    private val _list = MutableStateFlow(TabList())

    private val _tabs = MutableStateFlow<List<SessionTab>>(emptyList())
    val tabs: StateFlow<List<SessionTab>> = _tabs.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    init {
        scope.launch {
            networkRestored.collect { _tabs.value.forEach { it.onNetworkRestored() } }
        }
        scope.launch { networkBlocked.collect { blocked.value = it } }
    }

    private val _openInstances = MutableStateFlow<Map<String, Set<Int>>>(emptyMap())

    /**
     * The instances (ADR-0020) open in tabs, by saved session id. A new
     * parallel tab can still move to another number when it connects.
     */
    val openInstances: StateFlow<Map<String, Set<Int>>> = _openInstances.asStateFlow()

    /**
     * Opens [resolved] in a new tab, makes it active and starts connecting. The
     * tab shows [instance] of the saved session on the destination; at levels 2
     * and 3, two tabs of the same instance show the same terminal. A new
     * parallel instance passes [mustBeNew] ([TabInstance]).
     */
    fun open(
        resolved: ResolvedConnection,
        instance: Int = SessionInstances.PRINCIPAL,
        mustBeNew: Boolean = false,
    ): SessionTab = add(resolved, TabInstance(resolved.session.id, instance, mustBeNew) { syncInstances() })

    /**
     * Opens a file browser of [terminal]'s destination in a new tab, on that
     * tab's connection while it lasts ([SessionTab.explorerOf]), starting in
     * the terminal's current directory when it can be told
     * ([SessionTab.currentDirectory]) and in the user's folder otherwise, with
     * a note that says so.
     */
    suspend fun openExplorer(terminal: SessionTab): SessionTab {
        val directory = terminal.currentDirectory()
        val explorer = terminal.resolved.session.copy(type = SessionType.SFTP, initialDirectory = directory)
        return add(
            terminal.resolved.copy(session = explorer),
            TabInstance(explorer.id),
            explorerOf = terminal,
            sftpStartNote = if (directory == null) UNKNOWN_DIRECTORY_NOTE else null,
        ).also { it.rename(terminal.title + EXPLORER_SUFFIX) }
    }

    private fun add(
        resolved: ResolvedConnection,
        instance: TabInstance,
        explorerOf: SessionTab? = null,
        sftpStartNote: String? = null,
    ): SessionTab {
        val tabId = Ids.newId("tab")
        val tab = SessionTab(
            id = tabId,
            resolved = resolved,
            connector = connector,
            credentials = credentialResolver::resolve,
            knownHostsStore = knownHostsStore,
            scope = scope,
            automation = automation,
            reconnect = reconnect,
            agentDeployer = agentDeployer,
            agentObserver = agentObserver,
            instance = instance,
            explorerOf = explorerOf,
            sftpStartNote = sftpStartNote,
            networkBlocked = blocked,
            rdpSupport = rdpSupport,
        )
        byId[tabId] = tab
        _list.value = _list.value.add(tabId)
        sync()
        tab.start()
        return tab
    }

    /** Makes tab [id] active. */
    fun activate(id: String) {
        _list.value = _list.value.activate(id)
        sync()
    }

    /** Closes and disposes tab [id], selecting a neighbour as the new active tab. */
    fun close(id: String) {
        val tab = byId.remove(id)
        _list.value = _list.value.remove(id)
        sync()
        if (tab != null) scope.launch { tab.close() }
    }

    /**
     * Closes every tab of saved session [sessionId], or only those of
     * [instance] when given, e.g. before its agent session is terminated, so no
     * tab reconnects and starts it again.
     */
    fun closeTabsOf(sessionId: String, instance: Int? = null) {
        _tabs.value
            .filter { it.resolved.session.id == sessionId }
            .filter { instance == null || (it.isTerminal && it.instance.number.value == instance) }
            .forEach { close(it.id) }
    }

    /** The terminal tabs that show [instance] of saved session [sessionId]. */
    fun tabsOf(sessionId: String, instance: Int): List<SessionTab> =
        _tabs.value.filter { it.isTerminal && it.resolved.session.id == sessionId && it.instance.number.value == instance }

    /** Moves tab [id] one position towards the front of the strip. */
    fun moveLeft(id: String) {
        _list.value = _list.value.moveLeft(id)
        sync()
    }

    /** Moves tab [id] one position towards the back of the strip. */
    fun moveRight(id: String) {
        _list.value = _list.value.moveRight(id)
        sync()
    }

    /** Reorders the tab at [from] to index [to]. */
    fun move(from: Int, to: Int) {
        _list.value = _list.value.move(from, to)
        sync()
    }

    /** The currently active tab, or `null` when none are open. */
    fun active(): SessionTab? = _activeId.value?.let { byId[it] }

    private fun sync() {
        val list = _list.value
        _tabs.value = list.order.mapNotNull { byId[it] }
        _activeId.value = list.activeId
        syncInstances()
    }

    private fun syncInstances() {
        // A file browser opened from a terminal holds no instance of its session.
        _openInstances.value = _tabs.value
            .filter { it.explorerOf == null }
            .groupBy({ it.resolved.session.id }, { it.instance.number.value })
            .mapValues { (_, numbers) -> numbers.toSet() }
    }

    private companion object {
        /** What a file browser's tab is called after its terminal's name. */
        const val EXPLORER_SUFFIX = " · archivos"

        const val UNKNOWN_DIRECTORY_NOTE =
            "No se sabe en qué carpeta está la terminal: se muestra la carpeta de inicio"
    }
}
