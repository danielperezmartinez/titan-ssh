package io.github.danielperezmartinez.titanssh.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The client side of [[Abrir programas en el escritorio del usuario desde el
 * destino]]: the launches in the desktop part of `--status --json` and the
 * lines the agent panel shows for them.
 */
class AgentDesktopRunTest {

    /** The exact desktop JSON `TestStatusJSONContract` pins on the Go side. */
    private val goDesktop =
        """{"task":true,"state":"running","agent":"1.0.0","pid":7,"session":1,"runs":[""" +
            """{"timeMs":2000,"program":"C:\\Windows\\notepad.exe","args":["a b"],"dir":"C:\\","pid":8},""" +
            """{"timeMs":1000,"program":"C:\\x.exe","error":"refused"}]}"""

    @Test
    fun parsesTheLaunches() {
        val d = AgentStatusReport.parse("""{"schema":1,"state":"running","cli":"1","nowMs":5,"desktop":$goDesktop}""").desktop!!
        assertEquals(1, d.session)
        assertEquals(2, d.runs.size)
        val first = d.runs[0]
        assertEquals("C:\\Windows\\notepad.exe", first.program)
        assertEquals(listOf("a b"), first.args)
        assertEquals("C:\\", first.dir)
        assertEquals(8, first.pid)
        assertEquals("refused", d.runs[1].error)
    }

    @Test
    fun anOlderAgentReportsNoLaunches() {
        val d = AgentStatusReport.parse(
            """{"schema":1,"state":"running","cli":"1","nowMs":5,"desktop":{"task":true,"state":"running","pid":7,"session":1}}""",
        ).desktop!!
        assertTrue(d.runs.isEmpty())
    }

    @Test
    fun launchesAloneKeepTheHelperListed() {
        // The helper stopped and its task is gone, but what it opened is still recorded.
        val d = AgentDesktopReport(runs = listOf(AgentDesktopRun(timeMs = 1, program = "C:\\p.exe", pid = 2)))
        assertTrue(d.isPresent)
        assertTrue(!AgentDesktopReport().isPresent)
    }

    @Test
    fun linesShowWhenWhatAndHowItWent() {
        val hour = 3_600_000L
        // The destination's clock runs one hour behind the app's.
        val obs = AgentObservation(
            AgentStatusReport(state = AgentStatusReport.STATE_RUNNING, nowMs = 10 * hour),
            observedAtMs = 11 * hour,
        )
        val runs = listOf(
            AgentDesktopRun(timeMs = 9 * hour, program = "C:\\Android\\emulator\\emulator.exe", args = listOf("-avd", "Pixel 9"), pid = 42),
            AgentDesktopRun(timeMs = 8 * hour, program = "C:\\Windows\\regedit.exe", error = "needs an administrator"),
            AgentDesktopRun(timeMs = 7 * hour, program = "C:\\t.exe", args = listOf(""), pid = 1),
        )
        val lines = AgentInsights.desktopRunLines(obs, runs) { "@${it / hour}h" }
        assertEquals(
            listOf(
                "@10h · emulator.exe -avd \"Pixel 9\" · PID 42",
                "@9h · regedit.exe · no se abrió: needs an administrator",
                "@8h · t.exe \"\" · PID 1",
            ),
            lines,
        )
    }

    @Test
    fun aLongCommandIsCut() {
        val obs = AgentObservation(AgentStatusReport(state = AgentStatusReport.STATE_RUNNING, nowMs = 0), observedAtMs = 0)
        val run = AgentDesktopRun(timeMs = 0, program = "C:\\p.exe", args = listOf("x".repeat(200)), pid = 1)
        val line = AgentInsights.desktopRunLines(obs, listOf(run), maxCommand = 20) { "t" }.single()
        assertEquals("t · p.exe xxxxxxxxxxxxx… · PID 1", line)
    }
}
