package io.github.danielperezmartinez.titanssh.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The mouse pad's input frames (ADR-0016). The hex strings are the same as in
 * `agent/internal/protocol/input_test.go`, which keeps both codecs in lockstep.
 */
class InputFramesTest {

    private val wire = listOf(
        AgentFrame.PointerMove(12, -3) to "0900000004000cfffd",
        AgentFrame.PointerButton(InputKeys.BUTTON_RIGHT, pressed = true) to "0a000000020201",
        AgentFrame.PointerButton(InputKeys.BUTTON_LEFT, pressed = false) to "0a000000020100",
        AgentFrame.Scroll(-120, 240) to "0b00000004ff8800f0",
        AgentFrame.Text("ñ€") to "0c00000005c3b1e282ac",
        AgentFrame.Key(InputKeys.A + 2, InputKeys.MOD_CTRL or InputKeys.MOD_SHIFT, InputKeys.ACTION_PRESS) to "0d0000000400660300",
        AgentFrame.Key(InputKeys.ALT, action = InputKeys.ACTION_DOWN) to "0d0000000400ca0001",
        AgentFrame.InputReady() to "0e00000000",
        AgentFrame.InputReady(blocked = true) to "0e0000000101",
    )

    @Test
    fun input_frames_have_the_same_wire_bytes_as_the_go_agent() {
        for ((frame, hex) in wire) {
            assertEquals(hex, AgentProtocol.encode(frame).toHex(), "encoding $frame")
            assertEquals(listOf(frame), AgentProtocol.FrameDecoder().feed(hex.hexToBytes()), "decoding $hex")
        }
    }

    @Test
    fun input_ready_ignores_unknown_flags() {
        val frames = AgentProtocol.FrameDecoder().feed("0e0000000180".hexToBytes())
        assertEquals(listOf(AgentFrame.InputReady(blocked = false)), frames)
    }

    @Test
    fun keys_for_shortcut_characters() {
        assertEquals(InputKeys.A + 2, InputKeys.forChar('c'))
        assertEquals(InputKeys.A + 2, InputKeys.forChar('C'))
        assertEquals(InputKeys.DIGIT_0 + 7, InputKeys.forChar('7'))
        assertNull(InputKeys.forChar('ñ'))
        assertNull(InputKeys.forChar('/'))
        assertEquals(InputKeys.F12, InputKeys.function(12))
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
