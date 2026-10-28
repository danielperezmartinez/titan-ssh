package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.ssh.SshSession
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How well a live connection answers, worst last. */
enum class QualityGrade { GOOD, SLOW, NO_RESPONSE }

/**
 * What the status strip's quality bar shows for a live connection
 * ([[Indicar cuando la conexión deja de responder]]): its [grade] and, for
 * [QualityGrade.SLOW], how long the last answer took or, for
 * [QualityGrade.NO_RESPONSE], how long the server has been silent.
 */
data class ConnectionQuality(val grade: QualityGrade, val wait: Duration = Duration.ZERO) {
    /** The strip's words for it, in place of "Conectado". */
    val label: String get() = when (grade) {
        QualityGrade.GOOD -> "Conectado"
        QualityGrade.SLOW -> "Lenta (${wait.inWholeMilliseconds} ms)"
        QualityGrade.NO_RESPONSE -> "Sin respuesta (${wait.inWholeSeconds} s)"
    }

    companion object {
        val Good = ConnectionQuality(QualityGrade.GOOD)

        /** An answer at or above this is slow. */
        val SLOW_FROM = 500.milliseconds

        /** A probe unanswered this long means the server does not respond. */
        val NO_RESPONSE_AFTER = 5.seconds
    }
}

/**
 * Turns answers and silences into a [ConnectionQuality]. Getting worse shows
 * at once; getting better needs [CONFIRMATIONS] answers in a row at the better
 * grade, so the bar does not flicker. Coming back from no response is shown at
 * once: the user waits to see that.
 */
internal class QualityGrader {
    var quality: ConnectionQuality = ConnectionQuality.Good
        private set
    private var better = 0

    fun answered(after: Duration) {
        // A good answer shows no time: the bar would change on every probe for nothing.
        val next = if (after >= ConnectionQuality.SLOW_FROM) ConnectionQuality(QualityGrade.SLOW, after) else ConnectionQuality.Good
        val current = quality.grade
        when {
            current == QualityGrade.NO_RESPONSE || next.grade >= current -> {
                quality = next
                better = 0
            }
            ++better >= CONFIRMATIONS -> {
                quality = next
                better = 0
            }
        }
    }

    fun silent(waited: Duration) {
        quality = ConnectionQuality(QualityGrade.NO_RESPONSE, waited)
        better = 0
    }

    companion object {
        const val CONFIRMATIONS = 2
    }
}

/**
 * Watches how [session] answers until cancelled, reporting each change to
 * [onQuality]. Run it only while the quality is on screen: it is the one that
 * sends traffic. A probe goes out only once nothing has arrived for
 * [QUIET_BEFORE_PROBE], since traffic already shows the server answers, and
 * one at a time. It never closes or reconnects anything.
 */
internal suspend fun watchConnectionQuality(
    session: SshSession,
    onQuality: (ConnectionQuality) -> Unit,
    timeSource: TimeSource = TimeSource.Monotonic,
) = coroutineScope {
    val grader = QualityGrader()
    val answers = Channel<Duration>(Channel.UNLIMITED)
    var probeSent: TimeMark? = null
    var lastSent: TimeMark? = null
    var recheck = false
    var shown: ConnectionQuality? = null
    while (true) {
        while (true) {
            val answer = answers.tryReceive().getOrNull() ?: break
            probeSent = null
            if (grader.quality.grade == QualityGrade.NO_RESPONSE && answer >= ConnectionQuality.NO_RESPONSE_AFTER) {
                // Back from silence: that wait was the cut, not the latency. A
                // fresh probe right away says how the connection answers now.
                recheck = true
            } else {
                grader.answered(answer)
            }
        }
        val pending = probeSent
        val quiet = session.sinceLastReceived ?: Duration.INFINITE
        if (pending != null) {
            val waited = pending.elapsedNow()
            // Traffic during the wait is an answer too: the probe's is on its way.
            if (waited >= ConnectionQuality.NO_RESPONSE_AFTER && quiet >= waited) {
                grader.silent(waited.inWholeSeconds.seconds)
            }
        } else if (recheck ||
            quiet >= QUIET_BEFORE_PROBE && (lastSent?.elapsedNow() ?: Duration.INFINITE) >= QUIET_BEFORE_PROBE
        ) {
            recheck = false
            val sent = timeSource.markNow()
            probeSent = sent
            lastSent = sent
            launch {
                try {
                    session.probe()
                    answers.send(sent.elapsedNow())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Closed, or not supported: stay unanswered, the tab's phase says the rest.
                }
            }
        }
        if (grader.quality != shown) {
            shown = grader.quality
            onQuality(grader.quality)
        }
        delay(TICK)
    }
}

/** Silence before a probe goes out: about one probe every few seconds while idle. */
internal val QUIET_BEFORE_PROBE = 3.seconds

private val TICK = 250.milliseconds
