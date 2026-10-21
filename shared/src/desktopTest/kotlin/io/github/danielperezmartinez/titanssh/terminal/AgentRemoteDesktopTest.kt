package io.github.danielperezmartinez.titanssh.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The remote desktop's consent in `--status --json` (ADR-0031 §3), as the agent
 * panel reads it.
 */
class AgentRemoteDesktopTest {

    /** The exact JSON `TestRemoteDesktopJSONContract` pins on the Go side. */
    private val goRemote =
        """{"desktop":"KDE (Wayland)","consent":"dialog","remembered":true,"entry":true,"unattended":true,"unattendedAvailable":true}"""

    @Test
    fun parsesTheConsent() {
        val r = AgentStatusReport.parse("""{"schema":1,"state":"running","cli":"1","nowMs":5,"remoteDesktop":$goRemote}""").remoteDesktop!!
        assertEquals("KDE (Wayland)", r.desktop)
        assertTrue(r.asksConsent)
        assertTrue(r.remembered && r.entry && r.unattended && r.unattendedAvailable)
        assertTrue(r.isPresent)
    }

    @Test
    fun x11AsksNothingAndAnOlderAgentSaysNothing() {
        val x11 = AgentStatusReport.parse(
            """{"schema":1,"state":"running","cli":"1","nowMs":5,"remoteDesktop":{"desktop":"GNOME (X11)","consent":"none"}}""",
        ).remoteDesktop!!
        assertFalse(x11.asksConsent)
        assertFalse(x11.isPresent)
        assertNull(AgentStatusReport.parse("""{"schema":1,"state":"stopped","cli":"1","nowMs":5}""").remoteDesktop)
    }
}
