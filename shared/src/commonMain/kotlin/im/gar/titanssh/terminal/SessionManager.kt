package im.gar.titanssh.terminal

import im.gar.titanssh.config.Ids
import im.gar.titanssh.config.ResolvedConnection
import im.gar.titanssh.ssh.KnownHostsStore
import im.gar.titanssh.ssh.SshConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
    private val byId = mutableMapOf<String, SessionTab>()

    private val _list = MutableStateFlow(TabList())

    private val _tabs = MutableStateFlow<List<SessionTab>>(emptyList())
    val tabs: StateFlow<List<SessionTab>> = _tabs.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    /**
     * Opens [resolved] in a new tab, makes it active and starts connecting.
     * Opening the same saved session twice yields two independent tabs.
     */
    fun open(resolved: ResolvedConnection): SessionTab {
        val tabId = Ids.newId("tab")
        val tab = SessionTab(
            id = tabId,
            resolved = resolved,
            connector = connector,
            credentials = { credentialResolver.resolve(resolved.auth) },
            knownHostsStore = knownHostsStore,
            scope = scope,
            automation = automation,
            reconnect = reconnect,
            agentDeployer = agentDeployer,
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
    }
}
