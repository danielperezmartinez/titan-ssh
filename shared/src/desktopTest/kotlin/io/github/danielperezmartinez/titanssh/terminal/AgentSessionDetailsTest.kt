package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.formatLocalDateTime
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client side of [[Detalle de las sesiones del agente en el panel]]: the
 * extra session fields of the status contract, the preview and the detail
 * lines the agent panel shows.
 */
class AgentSessionDetailsTest {

    /** The exact session JSON `TestStatusJSONContract` pins on the Go side. */
    private val goSession =
        """{"id":"s2","createdMs":1,"lastUsedMs":2,"clients":0,"bufferBytes":3,"cpuPercent":12,""" +
            """"shell":"/bin/bash","shellPid":40,"foreground":"vim","foregroundPid":41,"cwd":"/tmp",""" +
            """"cols":80,"rows":24,"lastOutputMs":2,"title":"vim notes"}"""

    @Test
    fun parsesTheSessionDetails() {
        val r = AgentStatusReport.parse("""{"schema":1,"state":"running","cli":"1","nowMs":5,"sessions":[$goSession]}""")
        val s = r.sessions.single()
        assertEquals(12, s.cpuPercent)
        assertEquals("/bin/bash", s.shell)
        assertEquals(40, s.shellPid)
        assertEquals("vim", s.foreground)
        assertEquals(41, s.foregroundPid)
        assertEquals("/tmp", s.cwd)
        assertEquals(80 to 24, s.cols to s.rows)
        assertEquals(2L, s.lastOutputMs)
        assertEquals("vim notes", s.title)
    }

    @Test
    fun anOlderAgentSendsNoDetails() {
        val s = AgentStatusReport.parse(
            """{"schema":1,"state":"running","cli":"1","nowMs":5,"sessions":[{"id":"s1","createdMs":1,"lastUsedMs":2,"clients":0,"bufferBytes":3}]}""",
        ).sessions.single()
        assertNull(s.shell)
        assertNull(s.foreground)
        assertNull(s.cpuPercent)
    }

    @Test
    fun parsesThePreviewContract() {
        // The exact JSON `TestStatusJSONContract` pins for --preview.
        val p = AgentPreview.parse("""{"cols":80,"rows":24,"data":"aGk="}""")
        assertEquals(listOf("hi"), p.lines())
    }

    private fun preview(text: String, cols: Int = 20, rows: Int = 5) =
        AgentPreview(cols, rows, Base64.encode(text.encodeToByteArray()))

    @Test
    fun thePreviewShowsTheScreenAsTheTerminalDrawsIt() {
        // Colours are dropped, a clear screen wipes what came before, and
        // blank rows around the content are trimmed.
        val p = preview("old stuff\r\n\u001b[2J\u001b[H\u001b[32m$ \u001b[0mls\r\nfile.txt\r\n$ ")
        assertEquals(listOf("$ ls", "file.txt", "$"), p.lines())
    }

    @Test
    fun thePreviewKeepsTheLastLines() {
        val p = preview((1..10).joinToString("\r\n") { "line $it" }, rows = 10)
        assertEquals(listOf("line 8", "line 9", "line 10"), p.lines(maxLines = 3))
    }

    @Test
    fun aFullPreviewStartsAtALineBreak() {
        // Cut inside an escape sequence: without skipping to the first line
        // break, the replay would print its tail as text.
        val cut = "5;31mGARBAGE\r\nclean line\r\n"
        val text = cut + "x".repeat(AgentPreview.FULL_PREVIEW_BYTES - cut.length)
        val lines = preview(text, cols = 200, rows = 200).lines(maxLines = 200)
        assertEquals("clean line", lines.first())
    }

    @Test
    fun aBrokenPreviewShowsNothing() {
        assertEquals(emptyList(), AgentPreview(80, 24, "not base64!").lines())
    }

    private val minute = 60_000L
    private val hour = 60 * minute

    /** An observation made at app time [appNow], when the destination clock read [agentNow]. */
    private fun obs(s: AgentSessionReport, agentNow: Long, appNow: Long) = AgentObservation(
        AgentStatusReport(state = AgentStatusReport.STATE_RUNNING, nowMs = agentNow, sessions = listOf(s)),
        observedAtMs = appNow,
    )

    @Test
    fun detailsOfADetachedSessionRunningAProgram() {
        // The destination's clock runs one hour behind the app's.
        val agentNow = 100 * hour
        val appNow = agentNow + hour
        val s = AgentSessionReport(
            id = "s", createdMs = agentNow - 72 * hour, lastUsedMs = agentNow - 2 * hour,
            detachedMs = agentNow - 2 * hour, clients = 0, bufferBytes = 3L shl 20, memoryBytes = 80L shl 20,
            cpuPercent = 12, shell = "/usr/bin/bash", shellPid = 40, foreground = "claude", foregroundPid = 41,
            cwd = "/home/demo/proyecto", cols = 120, rows = 40, lastOutputMs = agentNow - 5 * minute, title = "demo: ~/proyecto",
        )
        val lines = AgentInsights.sessionDetails(obs(s, agentNow, appNow), s, appNow) { "@${it / hour}h" }
        assertEquals(
            listOf(
                "Recuperable: al abrirla vuelves a esta terminal",
                // The dates are shown on the app's clock: +1 h.
                "creada el @29h · activa desde hace 3 días",
                "sin conectar desde el @99h (hace 2 h)",
                "en marcha: claude (PID 41)",
                "shell bash (PID 40) · 120×40",
                "directorio: /home/demo/proyecto",
                "título: demo: ~/proyecto",
                "última salida hace 5 min · CPU 12 % · memoria 80 MB",
                "historial guardado 3,0 MB",
            ),
            lines,
        )
    }

    @Test
    fun detailsOfAnAttachedSessionAtItsPromptFromAWindowsAgent() {
        val s = AgentSessionReport(
            id = "s", createdMs = 0, lastUsedMs = 0, clients = 2,
            shell = "C:\\Windows\\system32\\cmd.exe", shellPid = 7,
        )
        val lines = AgentInsights.sessionDetails(obs(s, 0, 0), s, 0) { "t" }
        assertEquals("conectada en 2 pestañas o dispositivos", lines[2])
        assertEquals("en el prompt de la shell", lines[3])
        assertEquals("shell cmd.exe (PID 7)", lines[4])
        assertEquals("historial guardado vacío", lines.last())
    }

    @Test
    fun anOlderAgentGivesOnlyWhatItKnows() {
        val s = AgentSessionReport(id = "s", createdMs = 0, lastUsedMs = 0, clients = 1, bufferBytes = 2048)
        val lines = AgentInsights.sessionDetails(obs(s, 0, 0), s, 0) { "t" }
        assertEquals(
            listOf(
                "Recuperable: al abrirla vuelves a esta terminal",
                "creada el t · activa desde hace menos de 1 min",
                "conectada en 1 pestaña o dispositivo",
                "historial guardado 2 KB",
            ),
            lines,
        )
    }

    @Test
    fun aSessionWhoseShellEndedIsNotRecoverable() {
        val s = AgentSessionReport(id = "s", createdMs = 0, lastUsedMs = 0, closed = true, shell = "/bin/sh")
        val lines = AgentInsights.sessionDetails(obs(s, 0, 0), s, 0) { "t" }
        assertEquals("La shell terminó: ya no se puede recuperar", lines.first())
        assertTrue(lines.none { it.startsWith("en marcha") || it.startsWith("en el prompt") })
    }

    @Test
    fun anOrphanCannotBeOpenedFromHere() {
        val s = AgentSessionReport(id = "s", createdMs = 0, lastUsedMs = 0)
        val lines = AgentInsights.sessionDetails(obs(s, 0, 0), s, 0, orphan = true) { "t" }
        assertEquals("Sigue viva, pero aquí no hay una sesión guardada para abrirla", lines.first())
    }

    @Test
    fun programNames() {
        assertEquals("bash", AgentInsights.programName("/usr/bin/bash"))
        assertEquals("pwsh.exe", AgentInsights.programName("C:\\Program Files\\PowerShell\\7\\pwsh.exe"))
        assertEquals("sh", AgentInsights.programName("sh"))
    }

    @Test
    fun formatsLocalDatesInTheDeviceZone() {
        val zone = ZoneId.systemDefault()
        val at = LocalDateTime.of(2026, 9, 27, 16, 10).atZone(zone).toInstant().toEpochMilli()
        val sameYear = LocalDateTime.of(2026, 12, 1, 0, 0).atZone(zone).toInstant().toEpochMilli()
        val nextYear = LocalDateTime.of(2027, 1, 2, 0, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("27/09 16:10", formatLocalDateTime(at, sameYear))
        assertEquals("27/09/2026 16:10", formatLocalDateTime(at, nextYear))
    }
}
