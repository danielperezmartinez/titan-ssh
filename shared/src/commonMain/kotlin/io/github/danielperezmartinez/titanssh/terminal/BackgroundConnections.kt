package io.github.danielperezmartinez.titanssh.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the open tabs ask of the platform while the app is in the background:
 * [needed] when at least one would lose something without its connection, so a
 * foreground service keeps them all ([connectedTabs] for its notification).
 */
data class KeepAliveRequest(val needed: Boolean = false, val connectedTabs: Int = 0)

/**
 * Applies ADR-0023 to the open [tabs]: while any tab needs its connection
 * ([SessionTab.keepAlive]), [request] asks for a foreground service and every
 * tab stays connected. When none does and the app has been in the background
 * for [pauseAfterMillis], every tab is [paused][SessionTab.pause]; coming back
 * [resumes][SessionTab.resume] them. The platform reports visibility through
 * [setForeground]; one that never calls it never pauses (the desktop).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundConnections(
    scope: CoroutineScope,
    private val tabs: StateFlow<List<SessionTab>>,
    private val pauseAfterMillis: Long = PAUSE_AFTER_MILLIS,
) {
    private val foreground = MutableStateFlow(true)

    /** What the platform should keep up for the tabs; see [KeepAliveRequest]. */
    val request: StateFlow<KeepAliveRequest> = tabs
        .flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf(KeepAliveRequest())
            } else {
                combine(list.map { tab -> combine(tab.keepAlive, tab.status) { keep, status -> keep to status.phase } }) { states ->
                    KeepAliveRequest(
                        needed = states.any { it.first },
                        connectedTabs = states.count { it.second == TabPhase.CONNECTED },
                    )
                }
            }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, KeepAliveRequest())

    init {
        scope.launch {
            combine(foreground, request) { visible, request -> visible to request.needed }
                .distinctUntilChanged()
                .collectLatest { (visible, needed) ->
                    when {
                        visible -> tabs.value.forEach { it.resume() }
                        !needed -> {
                            // A short trip to another app or a glance at a
                            // notification does not cut anything.
                            delay(pauseAfterMillis)
                            tabs.value.forEach { it.pause() }
                        }
                    }
                }
        }
    }

    /** The app's window became visible ([visible]) or stopped being so. */
    fun setForeground(visible: Boolean) {
        foreground.value = visible
    }

    companion object {
        /** How long the app stays in the background before tabs with nothing to lose are paused. */
        const val PAUSE_AFTER_MILLIS = 60_000L
    }
}
