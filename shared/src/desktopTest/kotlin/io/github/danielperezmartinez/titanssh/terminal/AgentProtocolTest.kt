package io.github.danielperezmartinez.titanssh.terminal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies the level-3 agent wire protocol codec (ADR-0008): every frame
 * round-trips through encode → decode, the decoder reassembles frames across
 * arbitrary chunk boundaries, and malformed input is rejected.
 */
class AgentProtocolTest {

    private fun roundTrip(frame: AgentFrame): AgentFrame {
        val decoder = AgentProtocol.FrameDecoder()
        val frames = decoder.feed(AgentProtocol.encode(frame))
        assertEquals(1, frames.size, "expected exactly one frame")
        return frames.first()
    }

    @Test
    fun every_frame_type_round_trips() {
        val frames = listOf(
            AgentFrame.Hello(sessionId = "titan-abc_123", lastOffset = 4096, columns = 120, rows = 40),
            AgentFrame.HelloOk(headOffset = 1_000_000, tailOffset = 983_616),
            AgentFrame.Data(offset = 42, bytes = "hola[0m mundo".encodeToByteArray()),
            AgentFrame.Input(bytes = byteArrayOf(0x03)), // Ctrl-C
            AgentFrame.Resize(columns = 80, rows = 24),
            AgentFrame.ReplayFrom(offset = 512),
            AgentFrame.Ack(offset = 65_536),
            AgentFrame.Bye,
        )
        for (frame in frames) {
            assertEquals(frame, roundTrip(frame), "frame did not round-trip: $frame")
        }
    }

    @Test
    fun data_frame_preserves_offset_and_raw_bytes() {
        val payload = ByteArray(256) { it.toByte() } // includes NUL and high bytes
        val decoded = roundTrip(AgentFrame.Data(offset = 9_999_999_999L, bytes = payload))
        val data = decoded as AgentFrame.Data
        assertEquals(9_999_999_999L, data.offset)
        assertContentEquals(payload, data.bytes)
    }

    @Test
    fun decoder_reassembles_frames_split_across_chunks() {
        val wire = AgentProtocol.encode(AgentFrame.Data(offset = 7, bytes = "chunked".encodeToByteArray())) +
            AgentProtocol.encode(AgentFrame.Ack(offset = 14))
        val decoder = AgentProtocol.FrameDecoder()
        val collected = mutableListOf<AgentFrame>()
        // Feed one byte at a time: nothing emits until each frame is whole.
        for (b in wire) collected += decoder.feed(byteArrayOf(b))
        assertEquals(2, collected.size)
        assertEquals(AgentFrame.Data(7, "chunked".encodeToByteArray()), collected[0])
        assertEquals(AgentFrame.Ack(14), collected[1])
    }

    @Test
    fun decoder_emits_multiple_frames_from_one_chunk_and_keeps_remainder() {
        val two = AgentProtocol.encode(AgentFrame.Resize(100, 30)) +
            AgentProtocol.encode(AgentFrame.ReplayFrom(1))
        val partial = AgentProtocol.encode(AgentFrame.Bye).copyOfRange(0, 3) // header only, truncated
        val decoder = AgentProtocol.FrameDecoder()
        val first = decoder.feed(two + partial)
        assertEquals(listOf<AgentFrame>(AgentFrame.Resize(100, 30), AgentFrame.ReplayFrom(1)), first)
        // The truncated trailing frame stays buffered until the rest arrives.
        val rest = AgentProtocol.encode(AgentFrame.Bye).copyOfRange(3, 5)
        assertEquals(listOf(AgentFrame.Bye), decoder.feed(rest))
    }

    @Test
    fun unknown_frame_type_is_rejected() {
        // type=99, length=0
        val bogus = byteArrayOf(99, 0, 0, 0, 0)
        assertFailsWith<AgentProtocolException> { AgentProtocol.FrameDecoder().feed(bogus) }
    }

    @Test
    fun oversize_frame_length_is_rejected() {
        // type=DATA, length = MAX_PAYLOAD + 1 (u32 BE)
        val len = AgentProtocol.MAX_PAYLOAD + 1
        val header = byteArrayOf(
            AgentProtocol.Type.DATA.code.toByte(),
            (len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte(),
        )
        assertFailsWith<AgentProtocolException> { AgentProtocol.FrameDecoder().feed(header) }
    }

    @Test
    fun empty_feed_yields_nothing() {
        assertTrue(AgentProtocol.FrameDecoder().feed(ByteArray(0)).isEmpty())
    }
}
