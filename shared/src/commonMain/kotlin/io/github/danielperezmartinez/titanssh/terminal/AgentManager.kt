package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.ConfigController
import io.github.danielperezmartinez.titanssh.config.ResilienceLevel
import io.github.danielperezmartinez.titanssh.config.ResolvedConnection
import io.github.danielperezmartinez.titanssh.config.Session
import io.github.danielperezmartinez.titanssh.config.resolve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    fun hostOf(resolved: ResolvedConnection) =
        AgentHost(resolved.endpoint, resolved.auth, resolved.host.keepAliveSeconds)

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
     * Ends saved session [session] on its destination: closes its tabs first,
     * so none reconnects and starts it again, then closes its agent session.
     */
    fun terminate(session: Session) {
        val resolved = runCatching { config.state.value.resolve(session) }.getOrNull() ?: return
        sessions.closeTabsOf(session.id)
        val host = hostOf(resolved)
        launchOn(host) {
            watch.addPendingClose(host.key, AgentTransport.sanitizeId(session.id))
            access.refresh(host)
        }
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
        val obs = watch.state.value.observations[AgentKey.of(resolved.endpoint).id] ?: return false
        return AgentInsights.sessionOf(obs, session.id) != null
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
