package io.github.danielperezmartinez.titanssh.terminal

/**
 * Where a scroll of the terminal pane (a finger drag or a mouse wheel) goes.
 *
 * A full-screen app that asked for mouse reports (Claude Code's full-screen
 * mode, vim with `mouse=a`, htop…) keeps its own history and scrolls it on wheel
 * events, so the wheel is reported to it. On the alternate screen without mouse
 * reports the wheel becomes arrow keys, as xterm's alternate scroll mode does
 * (`less`, `man`). Otherwise the pane scrolls its own scrollback.
 */
object TerminalWheel {

    /** True when scrolling over [snap] is sent to the remote instead of moving the local scrollback. */
    fun goesToRemote(snap: TerminalSnapshot): Boolean =
        snap.mouseTracking || (snap.altScreen && snap.alternateScroll)

    /**
     * The bytes for [notches] wheel steps over [snap] (positive: up, towards
     * older lines), with the pointer at the 0-based [column],[row]; null when
     * the wheel scrolls the local scrollback instead.
     */
    fun bytes(snap: TerminalSnapshot, notches: Int, column: Int, row: Int): ByteArray? {
        if (notches == 0 || !goesToRemote(snap)) return null
        val up = notches > 0
        val one = when {
            snap.mouseTracking -> report(if (up) WHEEL_UP else WHEEL_DOWN, column, row, snap.sgrMouse)
            else -> TerminalKeys.inCursorMode(
                TerminalKeys.special(if (up) TerminalKeys.SpecialKey.UP else TerminalKeys.SpecialKey.DOWN),
                snap.applicationCursorKeys,
            )
        }
        val count = kotlin.math.abs(notches)
        val out = ByteArray(one.size * count)
        for (i in 0 until count) one.copyInto(out, i * one.size)
        return out
    }

    /** A mouse report for [button] at the 0-based [column],[row]: SGR (`CSI < b ; x ; y M`) or X10 (`CSI M` + 3 bytes). */
    private fun report(button: Int, column: Int, row: Int, sgr: Boolean): ByteArray {
        val x = column.coerceAtLeast(0) + 1
        val y = row.coerceAtLeast(0) + 1
        if (sgr) return "\u001B[<$button;$x;${y}M".encodeToByteArray()
        // X10 carries each value + 32 in one byte: positions past 223 cannot be told.
        return byteArrayOf(
            0x1B, '['.code.toByte(), 'M'.code.toByte(),
            (32 + button).toByte(), (32 + x.coerceAtMost(X10_MAX)).toByte(), (32 + y.coerceAtMost(X10_MAX)).toByte(),
        )
    }

    private const val WHEEL_UP = 64
    private const val WHEEL_DOWN = 65
    private const val X10_MAX = 223
}
