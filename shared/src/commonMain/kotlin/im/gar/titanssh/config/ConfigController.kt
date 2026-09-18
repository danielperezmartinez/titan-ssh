package im.gar.titanssh.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * In-memory, observable owner of the [TitanConfig], backing the "Configuración"
 * UI. It loads once from the [ConfigStore], exposes the config as a [StateFlow]
 * and applies edits by producing a new immutable config, updating the state and
 * persisting it. Persistence is fire-and-forget on [scope]; a failure surfaces
 * on [lastError] without dropping the in-memory edit.
 *
 * Kept as a plain multiplatform class (no Android ViewModel) so the same holder
 * drives Android and desktop.
 */
class ConfigController(
    private val store: ConfigStore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(TitanConfig())
    val state: StateFlow<TitanConfig> = _state.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    init {
        scope.launch {
            try {
                _state.value = store.load()
            } catch (e: Exception) {
                _lastError.value = e.message ?: "Failed to load config"
            } finally {
                _loaded.value = true
            }
        }
    }

    private fun mutate(block: (TitanConfig) -> TitanConfig) {
        val next = block(_state.value)
        _state.value = next
        scope.launch {
            try {
                store.save(next)
                _lastError.value = null
            } catch (e: Exception) {
                _lastError.value = e.message ?: "Failed to save config"
            }
        }
    }

    // --- Hosts ---------------------------------------------------------------

    fun upsertHost(host: Host) = mutate { cfg ->
        cfg.copy(hosts = cfg.hosts.upsert(host) { it.id == host.id })
    }

    /**
     * Removes a host. Any ProxyJump reference to it is cleared; sessions that
     * referenced it are left in place but will fail to resolve until re-pointed
     * (the UI flags them), which is safer than silently deleting a session.
     */
    fun deleteHost(hostId: String) = mutate { cfg ->
        cfg.copy(
            hosts = cfg.hosts
                .filterNot { it.id == hostId }
                .map { if (it.proxyJumpHostId == hostId) it.copy(proxyJumpHostId = null) else it },
        )
    }

    // --- Sessions ------------------------------------------------------------

    fun upsertSession(session: Session) = mutate { cfg ->
        cfg.copy(sessions = cfg.sessions.upsert(session) { it.id == session.id })
    }

    fun deleteSession(sessionId: String) = mutate { cfg ->
        cfg.copy(sessions = cfg.sessions.filterNot { it.id == sessionId })
    }

    /**
     * Creates a copy of [sessionId] as a new session ("plantillas / duplicar
     * sesión"), with fresh ids for the session and its scripts/tunnels and a
     * "(copia)" suffix. Returns the new session, or `null` if the source is gone.
     */
    fun duplicateSession(sessionId: String): Session? {
        val source = _state.value.sessions.firstOrNull { it.id == sessionId } ?: return null
        val copy = source.copy(
            id = Ids.session(),
            name = "${source.name} (copia)",
            scripts = source.scripts.map { it.copy(id = Ids.script()) },
            tunnels = source.tunnels.map { it.copy(id = Ids.tunnel()) },
        )
        upsertSession(copy)
        return copy
    }

    // --- Groups --------------------------------------------------------------

    fun upsertGroup(group: Group) = mutate { cfg ->
        cfg.copy(groups = cfg.groups.upsert(group) { it.id == group.id })
    }

    /** Removes a group and detaches hosts/sessions that pointed at it. */
    fun deleteGroup(groupId: String) = mutate { cfg ->
        cfg.copy(
            groups = cfg.groups.filterNot { it.id == groupId },
            hosts = cfg.hosts.map { if (it.groupId == groupId) it.copy(groupId = null) else it },
            sessions = cfg.sessions.map { if (it.groupId == groupId) it.copy(groupId = null) else it },
        )
    }

    // --- Snippets ------------------------------------------------------------

    fun upsertSnippet(snippet: Snippet) = mutate { cfg ->
        cfg.copy(snippets = cfg.snippets.upsert(snippet) { it.id == snippet.id })
    }

    fun deleteSnippet(snippetId: String) = mutate { cfg ->
        cfg.copy(snippets = cfg.snippets.filterNot { it.id == snippetId })
    }

    // --- Global appearance ---------------------------------------------------

    fun setDefaultAppearance(appearance: TerminalAppearance) = mutate { cfg ->
        cfg.copy(defaultAppearance = appearance)
    }
}

/** Replaces the first item matching [match] with [item], or appends it. */
private fun <T> List<T>.upsert(item: T, match: (T) -> Boolean): List<T> {
    val index = indexOfFirst(match)
    return if (index >= 0) toMutableList().also { it[index] = item } else this + item
}
