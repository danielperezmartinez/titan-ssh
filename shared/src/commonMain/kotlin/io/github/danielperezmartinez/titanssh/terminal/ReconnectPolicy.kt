package io.github.danielperezmartinez.titanssh.terminal

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How a [SessionTab] rides out a network micro-cut (resilience level 1,
 * ADR-0003 / [[Resiliencia de sesión ante microcortes de red]]). It governs only
 * the client-side retry cadence; the shell state and scrollback are kept in the
 * tab's terminal emulator across the reconnect regardless.
 *
 * The retry loop starts only *after* a session has been live at least once: a
 * drop is a micro-cut to ride out, whereas a host that never came up is a plain
 * connection failure the user must act on.
 *
 * Retries are bounded by time rather than by count
 * ([[Reconexión que no se rinde tras un corte largo]]): a cut of a few minutes
 * (a tunnel, a lift) must not leave the tab down.
 */
data class ReconnectPolicy(
    /** Maximum reconnection attempts after a drop before giving up; null: no limit. */
    val maxAttempts: Int? = null,
    /** How long after a drop to keep retrying before giving up; null: no limit. */
    val giveUpAfterMillis: Long? = 15 * 60_000L,
    /** Backoff before the first attempt; doubles each attempt up to [maxBackoffMillis]. */
    val initialBackoffMillis: Long = 500,
    /** Ceiling for the exponential backoff. */
    val maxBackoffMillis: Long = 10_000,
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

    /**
     * Why the tab stops retrying before a 1-based [attempt] made [sinceDrop]
     * after the drop, or null to keep going.
     */
    fun giveUpReason(attempt: Int, sinceDrop: Duration): String? = when {
        maxAttempts != null && attempt > maxAttempts ->
            "No se pudo reconectar tras $maxAttempts intentos"
        giveUpAfterMillis != null && sinceDrop >= giveUpAfterMillis.milliseconds ->
            "No se pudo reconectar en ${describe(giveUpAfterMillis)}"
        else -> null
    }

    /** Status detail while waiting for [attempt]. */
    fun progress(attempt: Int): String =
        "Reconectando… (intento $attempt" + (maxAttempts?.let { "/$it" } ?: "") + ")"

    private fun describe(millis: Long): String =
        if (millis >= 60_000) "${millis / 60_000} min" else "${millis / 1_000} s"

    companion object {
        val Default = ReconnectPolicy()
    }
}
