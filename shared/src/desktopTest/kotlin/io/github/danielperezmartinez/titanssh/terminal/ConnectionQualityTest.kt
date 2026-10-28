package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.ssh.SshConnectionState
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import io.github.danielperezmartinez.titanssh.ssh.SshShell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource

class ConnectionQualityTest {

    @Test
    fun an_idle_connection_that_answers_is_probed_every_few_seconds_and_stays_good() = runTest {
        val session = ProbedSession(testTimeSource)
        val seen = watch(session)

        at(10.seconds)

        assertEquals(3, session.probes)
        assertEquals(listOf(ConnectionQuality.Good), seen)
    }

    @Test
    fun recent_traffic_sends_no_probe() = runTest {
        val session = ProbedSession(testTimeSource)
        backgroundScope.launch {
            while (true) {
                delay(1.seconds)
                session.received()
            }
        }
        watch(session)

        at(20.seconds)

        assertEquals(0, session.probes)
    }

    @Test
    fun a_silent_server_shows_no_response_and_recovers_on_its_own() = runTest {
        val session = ProbedSession(testTimeSource)
        val stall = CompletableDeferred<Unit>()
        session.stall = stall
        val seen = watch(session)

        // The probe leaves at 3 s; 5 s later without an answer it is silence.
        at(7.9.seconds)
        assertEquals(QualityGrade.GOOD, seen.last().grade)
        at(8.seconds)
        assertEquals(ConnectionQuality(QualityGrade.NO_RESPONSE, 5.seconds), seen.last())
        at(11.seconds)
        assertEquals(ConnectionQuality(QualityGrade.NO_RESPONSE, 8.seconds), seen.last())
        assertEquals("Sin respuesta (8 s)", seen.last().label)

        // The late answer only says it is back; a fresh probe, sent at once, says how well.
        session.stall = null
        stall.complete(Unit)
        at(11.5.seconds)
        assertEquals(ConnectionQuality.Good, seen.last())
        assertEquals(2, session.probes)
        assertFalse(QualityGrade.SLOW in seen.map { it.grade }, "the cut is not shown as latency")
    }

    @Test
    fun slow_answers_show_at_once_and_good_ones_need_two_in_a_row() = runTest {
        val session = ProbedSession(testTimeSource)
        session.answerAfter = 900.milliseconds
        val seen = watch(session)

        at(4.seconds)
        assertEquals(ConnectionQuality(QualityGrade.SLOW, 900.milliseconds), seen.last())
        assertEquals("Lenta (900 ms)", seen.last().label)

        session.answerAfter = 100.milliseconds
        at(6.5.seconds)
        assertEquals(QualityGrade.SLOW, seen.last().grade)
        at(9.5.seconds)
        assertEquals(QualityGrade.GOOD, seen.last().grade)
    }

    @Test
    fun the_grader_gets_worse_at_once_and_better_after_two_answers() {
        val grader = QualityGrader()
        grader.answered(600.milliseconds)
        assertEquals(QualityGrade.SLOW, grader.quality.grade)
        grader.answered(50.milliseconds)
        assertEquals(QualityGrade.SLOW, grader.quality.grade)
        grader.answered(700.milliseconds)
        grader.answered(50.milliseconds)
        assertEquals(QualityGrade.SLOW, grader.quality.grade)
        grader.answered(50.milliseconds)
        assertEquals(QualityGrade.GOOD, grader.quality.grade)
        grader.silent(6.seconds)
        assertEquals(QualityGrade.NO_RESPONSE, grader.quality.grade)
        grader.answered(50.milliseconds)
        assertEquals(QualityGrade.GOOD, grader.quality.grade)
    }

    private fun TestScope.watch(session: ProbedSession): List<ConnectionQuality> {
        val seen = mutableListOf<ConnectionQuality>()
        backgroundScope.launch { watchConnectionQuality(session, { seen += it }, testTimeSource) }
        return seen
    }

    /** Runs the virtual clock up to [time] since the test started. */
    private fun TestScope.at(time: Duration) {
        advanceTimeBy(time - testScheduler.currentTime.milliseconds)
        runCurrent()
    }

    /** A connection whose probe answers after [answerAfter], or waits for [stall] first. */
    private class ProbedSession(private val clock: TimeSource) : SshSession {
        override val state = MutableStateFlow(SshConnectionState.CONNECTED)
        var answerAfter = 100.milliseconds
        var stall: CompletableDeferred<Unit>? = null
        var probes = 0
        private var lastReceived = clock.markNow()

        fun received() {
            lastReceived = clock.markNow()
        }

        override val sinceLastReceived: Duration get() = lastReceived.elapsedNow()

        override suspend fun probe() {
            probes++
            stall?.await()
            delay(answerAfter)
        }

        override suspend fun openShell(columns: Int, rows: Int): SshShell = error("not used")
        override suspend fun close() {}
    }
}
