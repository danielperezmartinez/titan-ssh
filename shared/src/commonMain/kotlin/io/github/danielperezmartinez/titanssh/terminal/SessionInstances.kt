package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.ReopenMode
import io.github.danielperezmartinez.titanssh.config.ResilienceLevel
import io.github.danielperezmartinez.titanssh.config.Session
import io.github.danielperezmartinez.titanssh.config.SessionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The instances a saved session can have on its destination (ADR-0020): the
 * principal, number [PRINCIPAL], under the saved session's own id, and parallel
 * ones from 2 on, under `<id>-p<n>`. That id names the agent session (level 3)
 * and the tmux/screen session (level 2), so an instance is found again after a
 * drop, an app restart or from another device.
 */
object SessionInstances {
    const val PRINCIPAL = 1

    private const val SUFFIX = "-p"

    /** The id instance [instance] of saved session [savedSessionId] goes by on the destination. */
    fun id(savedSessionId: String, instance: Int): String =
        if (instance <= PRINCIPAL) savedSessionId else "$savedSessionId$SUFFIX$instance"

    /** The agent-side id of an instance ([AgentTransport.sanitizeId]). */
    fun agentId(savedSessionId: String, instance: Int): String =
        AgentTransport.sanitizeId(id(savedSessionId, instance))

    /** Which instance of [savedSessionId] agent session [agentSessionId] is, or null when it is none of them. */
    fun instanceOf(savedSessionId: String, agentSessionId: String): Int? {
        val base = AgentTransport.sanitizeId(savedSessionId)
        if (agentSessionId == base) return PRINCIPAL
        if (!agentSessionId.startsWith(base + SUFFIX)) return null
        val n = agentSessionId.substring(base.length + SUFFIX.length).toIntOrNull() ?: return null
        // Only the exact spelling this side writes: no leading zeros or signs.
        return n.takeIf { it > PRINCIPAL && agentSessionId == base + SUFFIX + it }
    }

    /** The lowest parallel number not in [taken]. */
    fun nextParallel(taken: Collection<Int>): Int {
        var n = PRINCIPAL + 1
        while (n in taken) n++
        return n
    }

    /**
     * The name a tab or list shows for an instance: the one the user gave it
     * ([customName]) or else `name` or `name · 2`.
     */
    fun label(name: String, instance: Int, customName: String? = null): String =
        customName?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (instance <= PRINCIPAL) name else "$name · $instance"

    /**
     * Whether [session] keeps anything on its destination to re-attach to,
     * and so can have parallel instances: levels 2 and 3, and not a mouse pad.
     */
    fun supportsInstances(session: Session): Boolean =
        session.type == SessionType.TERMINAL && session.resilienceLevel != ResilienceLevel.BASE

    /**
     * What launching [session] does, given the instances open in tabs
     * ([openInTabs]) and those its agent last reported alive ([live]).
     * With none of them it opens the principal; otherwise it follows the
     * session's [ReopenMode].
     */
    fun plan(session: Session, openInTabs: Set<Int>, live: Set<Int>): LaunchPlan {
        if (!supportsInstances(session)) return LaunchPlan.Open(PRINCIPAL)
        val known = openInTabs + live
        if (known.isEmpty()) return LaunchPlan.Open(PRINCIPAL)
        val next = nextParallel(known)
        return when (session.reopen) {
            ReopenMode.ATTACH -> LaunchPlan.Open(PRINCIPAL)
            ReopenMode.PARALLEL -> LaunchPlan.Open(next, mustBeNew = true)
            ReopenMode.ASK -> LaunchPlan.Ask(
                options = (known + PRINCIPAL).sorted().map { InstanceOption(it, it in openInTabs, it in live) },
                nextParallel = next,
            )
        }
    }
}

/** One instance a launch can re-attach to; the principal is listed even when nothing holds it. */
data class InstanceOption(val instance: Int, val openInTab: Boolean, val live: Boolean)

/** What launching a saved session does ([SessionInstances.plan]). */
sealed interface LaunchPlan {
    /** Open [instance] at once; [mustBeNew] for a new parallel one. */
    data class Open(val instance: Int, val mustBeNew: Boolean = false) : LaunchPlan

    /** Let the user pick one of [options] or a new parallel instance, number [nextParallel]. */
    data class Ask(val options: List<InstanceOption>, val nextParallel: Int) : LaunchPlan {
        /**
         * The instances alive on the destination without a tab, in order: what
         * "Abrir todas" re-attaches to. Worth offering from two on.
         */
        val background: List<Int> get() = options.filter { it.live && !it.openInTab }.map { it.instance }
    }
}

/**
 * The instance a tab shows. A new parallel one [mustBeNew] until its first
 * attach: if the destination already holds that number (another device opened
 * it after the app last looked), the tab [skipTaken]s to the next one instead of
 * showing a terminal the user did not ask for. Once it attached, it stays.
 */
class TabInstance(
    val savedSessionId: String,
    private val start: Int = SessionInstances.PRINCIPAL,
    mustBeNew: Boolean = false,
    private val onChange: (Int) -> Unit = {},
) {
    private val _number = MutableStateFlow(start)
    val number: StateFlow<Int> = _number.asStateFlow()

    var mustBeNew: Boolean = mustBeNew
        private set

    /** The id this instance goes by on the destination ([SessionInstances.id]). */
    val id: String get() = SessionInstances.id(savedSessionId, _number.value)

    /**
     * The current number was already alive: move to the next one. Returns false,
     * and stops asking for a new one, once [MAX_SKIPS] numbers were taken.
     */
    fun skipTaken(): Boolean {
        if (_number.value - start >= MAX_SKIPS) {
            mustBeNew = false
            return false
        }
        _number.value += 1
        onChange(_number.value)
        return true
    }

    /** The tab attached to its instance: later connections re-attach to it. */
    fun claimed() {
        mustBeNew = false
    }

    private companion object {
        /** A bound on [skipTaken], so a misbehaving destination cannot loop the tab. */
        const val MAX_SKIPS = 32
    }
}
