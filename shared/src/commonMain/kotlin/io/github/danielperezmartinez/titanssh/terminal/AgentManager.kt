package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.ConfigController
import io.github.danielperezmartinez.titanssh.config.ResilienceLevel
import io.github.danielperezmartinez.titanssh.config.ResolvedConnection
import io.github.danielperezmartinez.titanssh.config.Session
import io.github.danielperezmartinez.titanssh.config.SessionType
import io.github.danielperezmartinez.titanssh.config.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The user's explicit actions on agents ([[Transparencia y control del agente
 * en el destino]]), shared by the launcher, the session editor and the agent
 * panel. Terminating a session always goes through [AgentWatch]'s pending
 * closes: it runs at once if the destination answers, and otherwise on the
 * next connection to it, so a deleted session never stays orphaned.
 */
class AgentManager(
    private val scope: CoroutineScope,
    val watch: AgentWatch,
    private val access: AgentHostAccess,
    private val sessions: SessionManager,
    private val config: ConfigController,
) {
    private val _busy = MutableStateFlow<Set<String>>(emptySet())

    /** Agents ([AgentKey.id]) with a connection in progress. */
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    /** The agent of [resolved]'s destination user. */
    fun hostOf(resolved: ResolvedConnection) = AgentHost.of(resolved)

    private val _previews = MutableStateFlow<Map<String, Map<String, AgentPreview>>>(emptyMap())

    /**
     * The last preview of each agent session, by [AgentKey.id] and agent
     * session id. Only in memory: it holds what the terminals showed.
     */
    val previews: StateFlow<Map<String, Map<String, AgentPreview>>> = _previews.asStateFlow()

    /** Refreshes what the app knows about [host]'s agent. */
    fun refresh(host: AgentHost) = launchOn(host) { access.refresh(host) }

    /**
     * Refreshes [host]'s agent and fetches a preview of each live session, on
     * the same connection: what the agent panel shows.
     */
    fun refreshWithPreviews(host: AgentHost) = launchOn(host) {
        access.withAgent(host) { control ->
            val sessionIds = watch.state.value.observations[host.key.id]?.report?.sessions.orEmpty()
                .filter { !it.closed }
                .map { it.id }
            // An agent without --preview, or a session that just ended, has none.
            val fetched = sessionIds.mapNotNull { id ->
                runCatching { control.preview(id) }.getOrNull()?.let { id to it }
            }.toMap()
            _previews.update { it + (host.key.id to fetched) }
        }
    }

    /**
     * Ends saved session [session] on its destination, every instance of it
     * (ADR-0020) that the app knows: closes their tabs first, so none
     * reconnects and starts it again, then closes their agent sessions.
     */
    fun terminate(session: Session) {
        val resolved = runCatching { config.state.value.resolve(session) }.getOrNull() ?: return
        val host = hostOf(resolved)
        val instances = sessions.openInstances.value[session.id].orEmpty() +
            liveInstances(session, resolved).keys + SessionInstances.PRINCIPAL
        sessions.closeTabsOf(session.id)
        launchOn(host) {
            instances.forEach { watch.addPendingClose(host.key, SessionInstances.agentId(session.id, it)) }
            access.refresh(host)
        }
    }

    /** Ends one [instance] of saved session [session] on its destination, closing its tabs first. */
    fun terminate(session: Session, instance: Int) {
        val resolved = runCatching { config.state.value.resolve(session) }.getOrNull() ?: return
        sessions.closeTabsOf(session.id, instance)
        closeAgentSession(hostOf(resolved), SessionInstances.agentId(session.id, instance))
    }

    /**
     * Deletes saved session [session] and, for a level-3 one, terminates it on
     * its destination too: there is no "keep it alive", so it never stays
     * orphaned (the user's decision). Other levels hold nothing in an agent.
     */
    fun delete(session: Session) {
        if (session.resilienceLevel == ResilienceLevel.AGENT) terminate(session)
        config.deleteSession(session.id)
    }

    /**
     * Whether saved [session] is alive on its destination according to its
     * agent's last report, i.e. whether [delete] would also terminate it there.
     */
    fun isLive(session: Session): Boolean {
        val resolved = runCatching { config.state.value.resolve(session) }.getOrNull() ?: return false
        return liveInstances(session, resolved).isNotEmpty()
    }

    /** The instances of [session] its agent last reported alive (ADR-0020), by instance. */
    private fun liveInstances(session: Session, resolved: ResolvedConnection): Map<Int, AgentSessionReport> {
        val obs = watch.state.value.observations[AgentKey.of(resolved.endpoint).id] ?: return emptyMap()
        return AgentInsights.instancesOf(obs, session.id)
    }

    init {
        // Level-3 tabs show the name the agent keeps for their instance,
        // whichever device gave it.
        scope.launch {
            combine(watch.state, sessions.tabs) { state, tabs -> state to tabs }
                .collect { (state, tabs) -> syncNames(state, tabs) }
        }
    }

    private fun syncNames(state: AgentWatchState, tabs: List<SessionTab>) {
        for (tab in tabs) {
            val session = tab.resolved.session
            if (!tab.isTerminal || session.resilienceLevel != ResilienceLevel.AGENT) continue
            val obs = state.observations[AgentKey.of(tab.resolved.endpoint).id] ?: continue
            val report = AgentInsights.instancesOf(obs, session.id)[tab.instance.number.value] ?: continue
            tab.rename(report.name)
        }
    }

    /**
     * Gives [instance] of saved session [session] the user's [name]; blank
     * takes it away. Its tabs show it at once. At level 3 the destination's
     * agent keeps it, so the agent panel and other devices see it too: this
     * goes through the tab's own connection when one serves that instance,
     * or a short-lived one otherwise. Returns why the agent did not take it,
     * or null; on failure the tabs go back to the agent's name.
     */
    suspend fun rename(session: Session, instance: Int, name: String): String? {
        val tabs = sessions.tabsOf(session.id, instance)
        tabs.forEach { it.rename(name) }
        if (session.type != SessionType.TERMINAL || session.resilienceLevel != ResilienceLevel.AGENT) return null
        val resolved = runCatching { config.state.value.resolve(session) }.getOrNull() ?: return null
        val host = hostOf(resolved)
        val agentId = SessionInstances.agentId(session.id, instance)
        val control = tabs.firstNotNullOfOrNull { it.liveAgentControl }
        _busy.value += host.key.id
        return try {
            if (control != null) {
                try {
                    control.renameSession(agentId, name)
                } finally {
                    // The agent's name, the new one or the one it kept.
                    runCatching { watch.sync(host.key, control) }
                }
            } else {
                access.withAgent(host) { it.renameSession(agentId, name) }
            }
            null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val why = e.message ?: "No se pudo cambiar el nombre en el destino"
            watch.recordError(host.key, why)
            why
        } finally {
            _busy.value -= host.key.id
        }
    }

    /** Closes one agent session listed in [host]'s panel (e.g. an orphan). */
    fun closeAgentSession(host: AgentHost, agentSessionId: String) = launchOn(host) {
        watch.addPendingClose(host.key, agentSessionId)
        access.refresh(host)
    }

    /**
     * Stops [host]'s agent, closing every session; the next level-3 session
     * starts the app's own version. Also how an older agent is updated.
     */
    fun stopAgent(host: AgentHost) = launchOn(host) {
        config.state.value.sessions
            .filter { s -> runCatching { AgentKey.of(config.state.value.resolve(s).endpoint) == host.key }.getOrDefault(false) }
            .forEach { sessions.closeTabsOf(it.id) }
        access.withAgent(host) { it.stop() }
    }

    /**
     * Removes the mouse pad's desktop helper from [host]: stops it and deletes
     * its scheduled task (ADR-0016). The mouse pad tabs on that host close
     * first; the next one launches the helper again.
     */
    fun removeDesktop(host: AgentHost) = launchOn(host) {
        config.state.value.sessions
            .filter { it.type == SessionType.MOUSEPAD }
            .filter { s -> runCatching { AgentKey.of(config.state.value.resolve(s).endpoint) == host.key }.getOrDefault(false) }
            .forEach { sessions.closeTabsOf(it.id) }
        access.withAgent(host) { it.removeDesktop() }
    }

    private fun launchOn(host: AgentHost, work: suspend () -> Unit) {
        val id = host.key.id
        _busy.value += id
        scope.launch {
            try {
                work()
            } catch (_: Exception) {
                // Already in watch.errors for the UI; a pending close stays pending.
            } finally {
                _busy.value -= id
            }
        }
    }
}
