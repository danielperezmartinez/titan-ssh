package io.github.danielperezmartinez.titanssh.terminal

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

    /**
     * The agent's cap on a frame's payload on an input connection (mirror of
     * `protocol.MaxInputPayload`); it drops the connection past it. Longer text
     * goes as several TEXT frames.
     */
    const val MAX_INPUT_PAYLOAD: Int = 4096

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

        // Input frames of a mouse pad session (ADR-0016), on `--input` only.
        POINTER_MOVE(9),
        POINTER_BUTTON(10),
        SCROLL(11),
        TEXT(12),
        KEY(13),
        INPUT_READY(14),
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
        Type.HELLO_OK -> AgentFrame.HelloOk(
            headOffset = readU64(payload, 0),
            tailOffset = readU64(payload, 8),
            // The flags byte is optional: agents before it sent only the two offsets.
            created = if (payload.size > 16) (payload[16].toInt() and HELLO_OK_CREATED) != 0 else null,
        )
        Type.DATA -> AgentFrame.Data(readU64(payload, 0), payload.copyOfRange(8, payload.size))
        Type.INPUT -> AgentFrame.Input(payload.copyOf())
        Type.RESIZE -> AgentFrame.Resize(readU16(payload, 0), readU16(payload, 2))
        Type.REPLAY_FROM -> AgentFrame.ReplayFrom(readU64(payload, 0))
        Type.ACK -> AgentFrame.Ack(readU64(payload, 0))
        // The reason is optional: agents before it sent an empty BYE.
        Type.BYE -> AgentFrame.Bye(payload.takeIf { it.isNotEmpty() }?.decodeToString())
        Type.POINTER_MOVE -> AgentFrame.PointerMove(readI16(payload, 0), readI16(payload, 2))
        Type.POINTER_BUTTON -> AgentFrame.PointerButton(payload[0].toInt() and 0xFF, payload[1].toInt() != 0)
        Type.SCROLL -> AgentFrame.Scroll(readI16(payload, 0), readI16(payload, 2))
        Type.TEXT -> AgentFrame.Text(payload.decodeToString())
        Type.KEY -> AgentFrame.Key(
            key = readU16(payload, 0),
            modifiers = payload[2].toInt() and 0xFF,
            action = payload[3].toInt() and 0xFF,
        )
        // An optional flags byte; unknown bits are room for later flags.
        Type.INPUT_READY -> AgentFrame.InputReady(
            blocked = payload.isNotEmpty() && (payload[0].toInt() and INPUT_READY_BLOCKED) != 0,
        )
    }

    private const val HEADER_SIZE = 5

    /** Bit 0 of `HELLO_OK`'s trailing flags byte (see [AgentFrame.HelloOk.created]). */
    private const val HELLO_OK_CREATED = 0x01

    /** Bit 0 of `INPUT_READY`'s optional flags byte (see [AgentFrame.InputReady.blocked]). */
    private const val INPUT_READY_BLOCKED = 0x01

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
        is AgentFrame.HelloOk -> ByteArray(if (created == null) 16 else 17).also {
            writeU64(it, 0, headOffset); writeU64(it, 8, tailOffset)
            if (created == true) it[16] = HELLO_OK_CREATED.toByte()
        }
        is AgentFrame.Data -> ByteArray(8 + bytes.size).also {
            writeU64(it, 0, offset); bytes.copyInto(it, 8)
        }
        is AgentFrame.Input -> bytes.copyOf()
        is AgentFrame.Resize -> ByteArray(4).also { writeU16(it, 0, columns); writeU16(it, 2, rows) }
        is AgentFrame.ReplayFrom -> ByteArray(8).also { writeU64(it, 0, offset) }
        is AgentFrame.Ack -> ByteArray(8).also { writeU64(it, 0, offset) }
        is AgentFrame.Bye -> reason?.encodeToByteArray() ?: ByteArray(0)
        is AgentFrame.PointerMove -> ByteArray(4).also { writeI16(it, 0, dx); writeI16(it, 2, dy) }
        is AgentFrame.PointerButton -> byteArrayOf(button.toByte(), if (pressed) 1 else 0)
        is AgentFrame.Scroll -> ByteArray(4).also { writeI16(it, 0, dx); writeI16(it, 2, dy) }
        is AgentFrame.Text -> text.encodeToByteArray()
        is AgentFrame.Key -> ByteArray(4).also {
            writeU16(it, 0, key)
            it[2] = modifiers.toByte()
            it[3] = action.toByte()
        }
        is AgentFrame.InputReady -> if (blocked) byteArrayOf(INPUT_READY_BLOCKED.toByte()) else ByteArray(0)
    }

    // --- big-endian fixed-width helpers -------------------------------------

    private fun writeU16(dst: ByteArray, at: Int, value: Int) {
        require(value in 0..0xFFFF) { "u16 out of range: $value" }
        dst[at] = (value ushr 8).toByte()
        dst[at + 1] = value.toByte()
    }

    private fun readU16(src: ByteArray, at: Int): Int =
        ((src[at].toInt() and 0xFF) shl 8) or (src[at + 1].toInt() and 0xFF)

    private fun writeI16(dst: ByteArray, at: Int, value: Int) {
        require(value in Short.MIN_VALUE..Short.MAX_VALUE) { "i16 out of range: $value" }
        dst[at] = (value ushr 8).toByte()
        dst[at + 1] = value.toByte()
    }

    private fun readI16(src: ByteArray, at: Int): Int = readU16(src, at).toShort().toInt()

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
     *
     * [created] (a trailing flags byte, bit 0) says whether this attach created
     * the session — a fresh PTY, whose replay then starts at its first byte — or
     * re-attached to a live one. It is `null` from agents that predate the flag.
     */
    data class HelloOk(
        val headOffset: Long,
        val tailOffset: Long,
        val created: Boolean? = null,
    ) : AgentFrame {
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

    /**
     * Either direction. Graceful goodbye. From the agent, instead of `HELLO_OK`,
     * it refuses the session: [reason] is then `<code> <message>` of the
     * `TITAN_AGENT_ERROR` contract (ADR-0009), e.g. `E_NO_CONPTY …`. Optional
     * payload: null for a plain goodbye and from agents that predate it.
     */
    data class Bye(val reason: String? = null) : AgentFrame {
        override val type get() = AgentProtocol.Type.BYE
    }

    // --- Input frames of a mouse pad session (ADR-0016) -----------------------
    // No offsets and no replay: an event lost to a network drop is gone. The key
    // catalogue, buttons and modifiers are in [InputKeys].

    /** Client → agent. Relative pointer motion in pixels, after the client's acceleration. */
    data class PointerMove(val dx: Int, val dy: Int) : AgentFrame {
        override val type get() = AgentProtocol.Type.POINTER_MOVE
    }

    /** Client → agent. Presses or releases [button] (an [InputKeys] `BUTTON_*`). */
    data class PointerButton(val button: Int, val pressed: Boolean) : AgentFrame {
        override val type get() = AgentProtocol.Type.POINTER_BUTTON
    }

    /** Client → agent. Scroll in 1/120 of a wheel notch; [dy] > 0 scrolls up, [dx] > 0 right. */
    data class Scroll(val dx: Int, val dy: Int) : AgentFrame {
        override val type get() = AgentProtocol.Type.SCROLL
    }

    /** Client → agent. Typed as characters, whatever the destination's keyboard layout. */
    data class Text(val text: String) : AgentFrame {
        override val type get() = AgentProtocol.Type.TEXT
    }

    /**
     * Client → agent. A key of [InputKeys]. With [InputKeys.ACTION_PRESS] the
     * [modifiers] are held around the tap; [InputKeys.ACTION_DOWN] and
     * [InputKeys.ACTION_UP] move the key alone and ignore them.
     */
    data class Key(val key: Int, val modifiers: Int = 0, val action: Int = InputKeys.ACTION_PRESS) : AgentFrame {
        override val type get() = AgentProtocol.Type.KEY
    }

    /**
     * Agent → client. The injector is ready; sent again whenever [blocked]
     * changes: input cannot reach the desktop while it is locked or a secure
     * desktop (UAC) is in front.
     */
    data class InputReady(val blocked: Boolean = false) : AgentFrame {
        override val type get() = AgentProtocol.Type.INPUT_READY
    }
}

/**
 * The input frames' catalogue (mirror of `agent/internal/protocol/input.go`):
 * pointer buttons, modifier bits, key actions and key codes. Keys are the
 * protocol's own, not Windows virtual keys or X11 keysyms; each injector on the
 * destination translates them.
 */
object InputKeys {
    const val BUTTON_LEFT = 1
    const val BUTTON_RIGHT = 2
    const val BUTTON_MIDDLE = 3

    const val MOD_SHIFT = 1 shl 0
    const val MOD_CTRL = 1 shl 1
    const val MOD_ALT = 1 shl 2
    const val MOD_META = 1 shl 3 // Windows key / Super

    const val ACTION_PRESS = 0
    const val ACTION_DOWN = 1
    const val ACTION_UP = 2

    const val ENTER = 1
    const val ESCAPE = 2
    const val BACKSPACE = 3
    const val TAB = 4
    const val SPACE = 5
    const val DELETE = 6
    const val INSERT = 7
    const val HOME = 8
    const val END = 9
    const val PAGE_UP = 10
    const val PAGE_DOWN = 11
    const val ARROW_LEFT = 12
    const val ARROW_RIGHT = 13
    const val ARROW_UP = 14
    const val ARROW_DOWN = 15
    const val F1 = 16 // F1..F12 are 16..27
    const val F12 = 27
    const val PRINT_SCREEN = 28
    const val CONTEXT_MENU = 29
    const val VOLUME_UP = 30
    const val VOLUME_DOWN = 31
    const val VOLUME_MUTE = 32
    const val MEDIA_PLAY_PAUSE = 33
    const val MEDIA_NEXT = 34
    const val MEDIA_PREVIOUS = 35

    const val A = 100 // A..Z are 100..125
    const val Z = 125
    const val DIGIT_0 = 130 // 0..9 are 130..139
    const val DIGIT_9 = 139

    const val SHIFT = 200
    const val CTRL = 201
    const val ALT = 202
    const val META = 203

    /** F[n] for n in 1..12. */
    fun function(n: Int): Int {
        require(n in 1..12) { "no F$n key" }
        return F1 + n - 1
    }

    /**
     * The key for a letter or digit, to send it as a shortcut (Ctrl+C), or
     * null for any other character, which goes as [AgentFrame.Text].
     */
    fun forChar(c: Char): Int? = when (c) {
        in 'a'..'z' -> A + (c - 'a')
        in 'A'..'Z' -> A + (c - 'A')
        in '0'..'9' -> DIGIT_0 + (c - '0')
        else -> null
    }
}

/** A malformed or out-of-range frame was read from the wire. */
class AgentProtocolException(message: String) : Exception(message)
