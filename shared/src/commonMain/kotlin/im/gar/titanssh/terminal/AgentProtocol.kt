package im.gar.titanssh.terminal

/**
 * Wire protocol between the titan-ssh client and its resilience **level-3 agent**
 * (`titan-agent`) on the destination (ADR-0008, [[Resiliencia nivel 3 agente
 * propio en el destino]]).
 *
 * The client connects over plain SSH (sshj) and `exec`s `titan-agent`; the two
 * then speak this **binary, length-prefixed** protocol over the stdio of that exec
 * channel — no extra port, reusing SSH's transport and authentication. This file
 * is the **source of truth for the on-the-wire format**; the Go agent mirrors it.
 *
 * ## Frame layout
 * ```
 * +--------+-----------------+-------------------+
 * | type   | length (u32 BE) | payload[length]   |
 * | 1 byte | 4 bytes         | length bytes      |
 * +--------+-----------------+-------------------+
 * ```
 *
 * Offsets are a monotonic count of bytes the session's PTY has produced since it
 * was created; [Data] carries the offset of its first byte, which makes [Ack] and
 * [ReplayFrom] self-describing. This object only (de)serializes frames — the
 * buffer/replay *semantics* live in the agent transport (a separate subtask).
 *
 * The decoder ([FrameDecoder]) tolerates arbitrary chunking of the byte stream
 * (an exec channel delivers whatever-sized reads), accumulating until whole
 * frames are available.
 */
object AgentProtocol {

    /** Hard cap on a single frame's payload, to bound memory on malformed input. */
    const val MAX_PAYLOAD: Int = 16 * 1024 * 1024

    /** One-byte wire code per frame type. */
    enum class Type(val code: Int) {
        HELLO(1),
        HELLO_OK(2),
        DATA(3),
        INPUT(4),
        RESIZE(5),
        REPLAY_FROM(6),
        ACK(7),
        BYE(8),
        ;

        companion object {
            fun fromCode(code: Int): Type? = entries.firstOrNull { it.code == code }
        }
    }

    /** Serializes [frame] to its full wire representation (header + payload). */
    fun encode(frame: AgentFrame): ByteArray {
        val payload = frame.payload()
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = frame.type.code.toByte()
        writeU32(out, 1, payload.size)
        payload.copyInto(out, HEADER_SIZE)
        return out
    }

    /**
     * Incremental frame decoder. [feed] appends bytes and returns every frame that
     * became complete; leftover bytes are retained for the next call. Not
     * thread-safe: drive it from a single reader coroutine.
     */
    class FrameDecoder {
        private var buffer = ByteArray(0)

        fun feed(chunk: ByteArray): List<AgentFrame> {
            if (chunk.isEmpty()) return emptyList()
            buffer += chunk
            val frames = mutableListOf<AgentFrame>()
            var offset = 0
            while (buffer.size - offset >= HEADER_SIZE) {
                val length = readU32(buffer, offset + 1)
                if (length < 0 || length > MAX_PAYLOAD) {
                    throw AgentProtocolException("Frame length out of range: $length")
                }
                if (buffer.size - offset - HEADER_SIZE < length) break // wait for more
                val code = buffer[offset].toInt() and 0xFF
                val type = Type.fromCode(code)
                    ?: throw AgentProtocolException("Unknown frame type: $code")
                val payload = buffer.copyOfRange(offset + HEADER_SIZE, offset + HEADER_SIZE + length)
                frames += decode(type, payload)
                offset += HEADER_SIZE + length
            }
            buffer = if (offset == 0) buffer else buffer.copyOfRange(offset, buffer.size)
            return frames
        }
    }

    /** Decodes a single frame from its [type] and already-framed [payload]. */
    fun decode(type: Type, payload: ByteArray): AgentFrame = when (type) {
        Type.HELLO -> {
            val sidLen = readU16(payload, 0)
            val sid = payload.copyOfRange(2, 2 + sidLen).decodeToString()
            var p = 2 + sidLen
            val lastOffset = readU64(payload, p); p += 8
            val cols = readU16(payload, p); p += 2
            val rows = readU16(payload, p)
            AgentFrame.Hello(sid, lastOffset, cols, rows)
        }
        Type.HELLO_OK -> AgentFrame.HelloOk(readU64(payload, 0), readU64(payload, 8))
        Type.DATA -> AgentFrame.Data(readU64(payload, 0), payload.copyOfRange(8, payload.size))
        Type.INPUT -> AgentFrame.Input(payload.copyOf())
        Type.RESIZE -> AgentFrame.Resize(readU16(payload, 0), readU16(payload, 2))
        Type.REPLAY_FROM -> AgentFrame.ReplayFrom(readU64(payload, 0))
        Type.ACK -> AgentFrame.Ack(readU64(payload, 0))
        Type.BYE -> AgentFrame.Bye
    }

    private const val HEADER_SIZE = 5

    private fun AgentFrame.payload(): ByteArray = when (this) {
        is AgentFrame.Hello -> {
            val sid = sessionId.encodeToByteArray()
            val out = ByteArray(2 + sid.size + 8 + 2 + 2)
            writeU16(out, 0, sid.size)
            sid.copyInto(out, 2)
            var p = 2 + sid.size
            writeU64(out, p, lastOffset); p += 8
            writeU16(out, p, columns); p += 2
            writeU16(out, p, rows)
            out
        }
        is AgentFrame.HelloOk -> ByteArray(16).also {
            writeU64(it, 0, headOffset); writeU64(it, 8, tailOffset)
        }
        is AgentFrame.Data -> ByteArray(8 + bytes.size).also {
            writeU64(it, 0, offset); bytes.copyInto(it, 8)
        }
        is AgentFrame.Input -> bytes.copyOf()
        is AgentFrame.Resize -> ByteArray(4).also { writeU16(it, 0, columns); writeU16(it, 2, rows) }
        is AgentFrame.ReplayFrom -> ByteArray(8).also { writeU64(it, 0, offset) }
        is AgentFrame.Ack -> ByteArray(8).also { writeU64(it, 0, offset) }
        is AgentFrame.Bye -> ByteArray(0)
    }

    // --- big-endian fixed-width helpers -------------------------------------

    private fun writeU16(dst: ByteArray, at: Int, value: Int) {
        require(value in 0..0xFFFF) { "u16 out of range: $value" }
        dst[at] = (value ushr 8).toByte()
        dst[at + 1] = value.toByte()
    }

    private fun readU16(src: ByteArray, at: Int): Int =
        ((src[at].toInt() and 0xFF) shl 8) or (src[at + 1].toInt() and 0xFF)

    private fun writeU32(dst: ByteArray, at: Int, value: Int) {
        dst[at] = (value ushr 24).toByte()
        dst[at + 1] = (value ushr 16).toByte()
        dst[at + 2] = (value ushr 8).toByte()
        dst[at + 3] = value.toByte()
    }

    private fun readU32(src: ByteArray, at: Int): Int =
        ((src[at].toInt() and 0xFF) shl 24) or
            ((src[at + 1].toInt() and 0xFF) shl 16) or
            ((src[at + 2].toInt() and 0xFF) shl 8) or
            (src[at + 3].toInt() and 0xFF)

    private fun writeU64(dst: ByteArray, at: Int, value: Long) {
        for (i in 0 until 8) dst[at + i] = (value ushr (56 - i * 8)).toByte()
    }

    private fun readU64(src: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (src[at + i].toLong() and 0xFF)
        return v
    }
}

/** A single protocol frame (ADR-0008). See [AgentProtocol] for the wire layout. */
sealed interface AgentFrame {
    val type: AgentProtocol.Type

    /**
     * Client → agent. Opens or re-attaches session [sessionId], asking for replay
     * from [lastOffset] (the last byte offset the client has durably applied), and
     * declares the current terminal size.
     */
    data class Hello(
        val sessionId: String,
        val lastOffset: Long,
        val columns: Int,
        val rows: Int,
    ) : AgentFrame {
        override val type get() = AgentProtocol.Type.HELLO
    }

    /**
     * Agent → client. Announces the byte range currently held in the ring buffer:
     * [tailOffset] (oldest still available) to [headOffset] (total produced). If
     * the client's requested offset is below [tailOffset] it must reset its
     * emulator to the replay that follows.
     */
    data class HelloOk(val headOffset: Long, val tailOffset: Long) : AgentFrame {
        override val type get() = AgentProtocol.Type.HELLO_OK
    }

    /** Agent → client. Raw PTY output; [offset] is the offset of the first byte. */
    data class Data(val offset: Long, val bytes: ByteArray) : AgentFrame {
        override val type get() = AgentProtocol.Type.DATA
        override fun equals(other: Any?): Boolean =
            this === other || (other is Data && offset == other.offset && bytes.contentEquals(other.bytes))
        override fun hashCode(): Int = 31 * offset.hashCode() + bytes.contentHashCode()
    }

    /** Client → agent. Raw bytes to write to the PTY. */
    data class Input(val bytes: ByteArray) : AgentFrame {
        override val type get() = AgentProtocol.Type.INPUT
        override fun equals(other: Any?): Boolean =
            this === other || (other is Input && bytes.contentEquals(other.bytes))
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** Client → agent. New terminal size. */
    data class Resize(val columns: Int, val rows: Int) : AgentFrame {
        override val type get() = AgentProtocol.Type.RESIZE
    }

    /** Client → agent. Explicit request to replay from [offset]. */
    data class ReplayFrom(val offset: Long) : AgentFrame {
        override val type get() = AgentProtocol.Type.REPLAY_FROM
    }

    /** Client → agent. Confirms [offset] has been durably applied; buffer may trim below it. */
    data class Ack(val offset: Long) : AgentFrame {
        override val type get() = AgentProtocol.Type.ACK
    }

    /** Either direction. Graceful goodbye. */
    data object Bye : AgentFrame {
        override val type get() = AgentProtocol.Type.BYE
    }
}

/** A malformed or out-of-range frame was read from the wire. */
class AgentProtocolException(message: String) : Exception(message)
