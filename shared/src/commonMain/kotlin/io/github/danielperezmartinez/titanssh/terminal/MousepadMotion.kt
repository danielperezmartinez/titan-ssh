package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.MousepadSettings
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Turns finger motion on the mouse pad into the integer deltas of the input
 * frames (ADR-0016): the pointer with an acceleration curve, so slow strokes
 * are precise and fast ones cross the screen, and two-finger scrolling in 1/120
 * of a notch. Works in dp, so the feel does not depend on the phone's density.
 * Fractions are carried over to the next event, so nothing is lost to
 * rounding. Pure, so it is tested headless; the destination applies the deltas
 * as they come, with no acceleration of its own.
 */
class MousepadMotion(private val settings: MousepadSettings = MousepadSettings()) {

    private var restX = 0f
    private var restY = 0f
    private var scrollRestX = 0f
    private var scrollRestY = 0f

    /**
     * The pointer delta for a finger that moved [dxDp], [dyDp] in [millis]
     * since the previous event, or null when it rounds to nothing yet.
     */
    fun move(dxDp: Float, dyDp: Float, millis: Long): AgentFrame.PointerMove? {
        val speed = hypot(dxDp, dyDp) / millis.coerceAtLeast(1)
        val gain = pointerGain(speed) * settings.pointerSpeed.coerceIn(MIN_SPEED, MAX_SPEED)
        val x = restX + dxDp * gain
        val y = restY + dyDp * gain
        val ix = x.toInt()
        val iy = y.toInt()
        restX = x - ix
        restY = y - iy
        if (ix == 0 && iy == 0) return null
        return AgentFrame.PointerMove(ix.clampToI16(), iy.clampToI16())
    }

    /**
     * The scroll for two fingers that moved [dxDp], [dyDp], or null when it
     * rounds to nothing yet. With [MousepadSettings.naturalScroll] (the default,
     * as on the phone itself and on Windows' touchpads) the content follows the
     * fingers; without it, fingers moving up scroll up, like a mouse wheel.
     */
    fun scroll(dxDp: Float, dyDp: Float): AgentFrame.Scroll? {
        val sign = if (settings.naturalScroll) 1f else -1f
        // The protocol's dy > 0 scrolls up and dx > 0 right.
        val x = scrollRestX - sign * dxDp * SCROLL_UNITS_PER_DP
        val y = scrollRestY + sign * dyDp * SCROLL_UNITS_PER_DP
        val ix = x.toInt()
        val iy = y.toInt()
        scrollRestX = x - ix
        scrollRestY = y - iy
        if (ix == 0 && iy == 0) return null
        return AgentFrame.Scroll(ix.clampToI16(), iy.clampToI16())
    }

    /** Forgets the fractions left over: a new gesture starts from zero. */
    fun reset() {
        restX = 0f
        restY = 0f
        scrollRestX = 0f
        scrollRestY = 0f
    }

    companion object {
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f

        /** Pixels on the destination per dp of finger travel, slow and fast. */
        const val SLOW_GAIN = 1.4f
        const val FAST_GAIN = 4.5f

        /** Finger speeds in dp/ms where the curve starts and stops rising. */
        const val SLOW_SPEED = 0.15f
        const val FAST_SPEED = 1.6f

        /** One wheel notch (120 units) for every 40 dp of two-finger travel. */
        const val SCROLL_UNITS_PER_DP = 3f

        /** The acceleration curve: linear between the slow and fast gains. */
        fun pointerGain(speedDpPerMs: Float): Float {
            val t = ((speedDpPerMs - SLOW_SPEED) / (FAST_SPEED - SLOW_SPEED)).coerceIn(0f, 1f)
            return SLOW_GAIN + (FAST_GAIN - SLOW_GAIN) * t
        }

        /** The pointer speed presets the session editor offers. */
        val SPEED_PRESETS: List<Pair<String, Float>> = listOf("Lenta" to 0.6f, "Normal" to 1f, "Rápida" to 1.6f)

        /** The preset closest to [speed], for showing a stored value. */
        fun nearestPreset(speed: Float): Float = SPEED_PRESETS.minBy { abs(it.second - speed) }.second
    }
}

/** What typing on the mouse pad's keyboard sends. */
object MousepadTyping {
    /**
     * [text] as typed with the sticky [modifiers] (InputKeys `MOD_*`): plain
     * text without them, and shortcuts (Ctrl+C) with them, for the characters
     * that have a key. The rest still goes as text.
     */
    fun typed(text: String, modifiers: Int): List<AgentFrame> {
        if (modifiers == 0) return listOf(AgentFrame.Text(text))
        return text.map { c ->
            InputKeys.forChar(c)?.let { AgentFrame.Key(it, modifiers) } ?: AgentFrame.Text(c.toString())
        }
    }
}

private fun Int.clampToI16(): Int = coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
