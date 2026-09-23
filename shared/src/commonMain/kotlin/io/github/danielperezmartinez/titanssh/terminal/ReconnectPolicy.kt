package io.github.danielperezmartinez.titanssh.terminal

/**
 * How a [SessionTab] rides out a network micro-cut (resilience level 1,
 * ADR-0003 / [[Resiliencia de sesión ante microcortes de red]]). It governs only
 * the client-side retry cadence; the shell state and scrollback are kept in the
 * tab's terminal emulator across the reconnect regardless.
 *
 * The retry loop starts only *after* a session has been live at least once: a
 * drop is a micro-cut to ride out, whereas a host that never came up is a plain
 * connection failure the user must act on.
 */
data class ReconnectPolicy(
    /** Maximum reconnection attempts after a drop before giving up. */
    val maxAttempts: Int = 6,
    /** Backoff before the first attempt; doubles each attempt up to [maxBackoffMillis]. */
    val initialBackoffMillis: Long = 500,
    /** Ceiling for the exponential backoff. */
    val maxBackoffMillis: Long = 8_000,
    /**
     * After the shell output ends, how long to wait for the transport to report
     * itself down before deciding the cause. If it stays up within this window
     * the shell ended cleanly (the user exited); if it goes down it was a drop to
     * reconnect through.
     */
    val dropGraceMillis: Long = 1_500,
) {
    /** Capped exponential backoff for a 1-based [attempt] number. */
    fun backoffMillis(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 20)
        val raw = initialBackoffMillis shl shift
        return raw.coerceIn(initialBackoffMillis, maxBackoffMillis)
    }

    companion object {
        val Default = ReconnectPolicy()
    }
}
