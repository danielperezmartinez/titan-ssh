package io.github.danielperezmartinez.titanssh.config

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

    /**
     * Creates a group named [name] in [scope]'s list, inside [parentId] (or at
     * the top), and returns it.
     */
    fun createGroup(scope: GroupScope, name: String, parentId: String? = null): Group {
        val group = Group(id = Ids.group(), name = name.trim(), parentId = parentId)
        mutate { cfg -> cfg.withGroups(scope, cfg.groups(scope) + group) }
        return group
    }

    fun renameGroup(scope: GroupScope, groupId: String, name: String) =
        updateGroup(scope, groupId) { it.copy(name = name.trim()) }

    fun setGroupCollapsed(scope: GroupScope, groupId: String, collapsed: Boolean) =
        updateGroup(scope, groupId) { it.copy(collapsed = collapsed) }

    /**
     * Moves a group, with everything it holds, inside [parentId] (or to the
     * top). Moving it into itself or into one of its own subfolders is refused
     * and returns `false`.
     */
    fun moveGroup(scope: GroupScope, groupId: String, parentId: String?): Boolean {
        val groups = _state.value.groups(scope)
        if (parentId != null) {
            if (parentId == groupId || groups.none { it.id == parentId }) return false
            if (parentId in GroupTree(groups).descendants(groupId)) return false
        }
        updateGroup(scope, groupId) { it.copy(parentId = parentId) }
        return true
    }

    /**
     * Removes a group of [scope]'s list. What it held (subfolders and members)
     * moves up to the group's parent, so deleting a folder never deletes a
     * host or a session.
     */
    fun deleteGroup(scope: GroupScope, groupId: String) = mutate { cfg ->
        val groups = cfg.groups(scope)
        val group = groups.firstOrNull { it.id == groupId } ?: return@mutate cfg
        val parent = group.parentId?.takeIf { p -> groups.any { it.id == p } }
        val remaining = groups
            .filterNot { it.id == groupId }
            .map { if (it.parentId == groupId) it.copy(parentId = parent) else it }
        val moved = cfg.withGroups(scope, remaining)
        when (scope) {
            GroupScope.HOSTS -> moved.copy(
                hosts = cfg.hosts.map { if (it.groupId == groupId) it.copy(groupId = parent) else it },
            )
            GroupScope.SESSIONS -> moved.copy(
                sessions = cfg.sessions.map { if (it.groupId == groupId) it.copy(groupId = parent) else it },
            )
        }
    }

    private fun updateGroup(scope: GroupScope, groupId: String, change: (Group) -> Group) = mutate { cfg ->
        cfg.withGroups(scope, cfg.groups(scope).map { if (it.id == groupId) change(it) else it })
    }

    // --- Script library (ADR-0013) -------------------------------------------

    fun upsertLibraryScript(script: LibraryScript) = mutate { cfg ->
        cfg.copy(scripts = cfg.scripts.upsert(script) { it.id == script.id })
    }

    /**
     * Removes a library script. Each session that referenced it keeps a copy of
     * it as its own script, so deleting from the library never stops a session
     * from running what it ran before.
     */
    fun deleteLibraryScript(libraryScriptId: String) = mutate { cfg ->
        val library = cfg.scripts.firstOrNull { it.id == libraryScriptId }
        cfg.copy(
            scripts = cfg.scripts.filterNot { it.id == libraryScriptId },
            sessions = if (library == null) cfg.sessions else cfg.sessions.map { session ->
                session.copy(
                    scripts = session.scripts.map { script ->
                        if (script.libraryScriptId == libraryScriptId) {
                            script.filledFrom(library).copy(libraryScriptId = null)
                        } else {
                            script
                        }
                    },
                )
            },
        )
    }

    // --- Global appearance ---------------------------------------------------

    fun setDefaultAppearance(appearance: TerminalAppearance) = mutate { cfg ->
        cfg.copy(defaultAppearance = appearance)
    }

    // --- App settings --------------------------------------------------------

    fun setAllowScreenCapture(allowed: Boolean) = mutate { cfg ->
        cfg.copy(settings = cfg.settings.copy(allowScreenCapture = allowed))
    }
}

/** Replaces the first item matching [match] with [item], or appends it. */
private fun <T> List<T>.upsert(item: T, match: (T) -> Boolean): List<T> {
    val index = indexOfFirst(match)
    return if (index >= 0) toMutableList().also { it[index] = item } else this + item
}
