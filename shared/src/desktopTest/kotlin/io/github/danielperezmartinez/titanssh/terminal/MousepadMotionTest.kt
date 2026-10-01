package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.MousepadSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MousepadMotionTest {

    @Test
    fun slow_strokes_are_precise_and_lose_nothing_to_rounding() {
        val motion = MousepadMotion()
        var total = 0
        // 100 slow events of 0.5 dp: the fractions add up, none is dropped.
        repeat(100) { motion.move(0.5f, 0f, millis = 16)?.let { total += it.dx } }
        // 70 px, give or take the last fraction still carried.
        assertTrue(total in 69..70, "moved $total px")
    }

    @Test
    fun fast_strokes_go_further_per_dp() {
        val slow = MousepadMotion().move(10f, 0f, millis = 1000)!!.dx
        val fast = MousepadMotion().move(10f, 0f, millis = 2)!!.dx
        assertEquals((10 * MousepadMotion.SLOW_GAIN).toInt(), slow)
        assertEquals((10 * MousepadMotion.FAST_GAIN).toInt(), fast)
    }

    @Test
    fun the_speed_setting_scales_the_pointer() {
        val normal = MousepadMotion().move(20f, -20f, millis = 1000)!!
        val quick = MousepadMotion(MousepadSettings(pointerSpeed = 2f)).move(20f, -20f, millis = 1000)!!
        assertEquals(normal.dx * 2, quick.dx)
        assertEquals(normal.dy * 2, quick.dy)
    }

    @Test
    fun tiny_motion_waits_for_a_whole_pixel() {
        assertNull(MousepadMotion().move(0.1f, 0.1f, millis = 16))
    }

    @Test
    fun natural_scroll_follows_the_fingers() {
        val natural = MousepadMotion(MousepadSettings(naturalScroll = true))
        // Fingers down 40 dp: the content follows them, so the view scrolls up one notch.
        assertEquals(AgentFrame.Scroll(0, 120), natural.scroll(0f, 40f))
        // Fingers right: the content follows, so the view scrolls left.
        assertEquals(AgentFrame.Scroll(-120, 0), natural.scroll(40f, 0f))
    }

    @Test
    fun classic_scroll_works_like_a_wheel() {
        val classic = MousepadMotion(MousepadSettings(naturalScroll = false))
        assertEquals(AgentFrame.Scroll(0, -120), classic.scroll(0f, 40f))
        assertEquals(AgentFrame.Scroll(120, 0), classic.scroll(40f, 0f))
    }

    @Test
    fun the_acceleration_curve_is_monotonic_and_bounded() {
        var last = 0f
        for (i in 0..40) {
            val gain = MousepadMotion.pointerGain(i * 0.05f)
            assertTrue(gain >= last)
            last = gain
        }
        assertEquals(MousepadMotion.SLOW_GAIN, MousepadMotion.pointerGain(0f))
        assertEquals(MousepadMotion.FAST_GAIN, MousepadMotion.pointerGain(10f))
    }

    @Test
    fun typing_with_modifiers_sends_shortcuts() {
        assertEquals(listOf(AgentFrame.Text("hola ñ")), MousepadTyping.typed("hola ñ", modifiers = 0))
        assertEquals(
            listOf(AgentFrame.Key(InputKeys.A + 2, InputKeys.MOD_CTRL)),
            MousepadTyping.typed("c", InputKeys.MOD_CTRL),
        )
        // A character with no key of its own still goes out, as text.
        assertEquals(listOf(AgentFrame.Text("ñ")), MousepadTyping.typed("ñ", InputKeys.MOD_CTRL))
    }
}
