package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.ssh.SshCredentials
import io.github.danielperezmartinez.titanssh.ssh.SshEndpoint
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import io.github.danielperezmartinez.titanssh.ssh.createSshConnector
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A remote desktop (ADR-0029) against a real destination, such as the
 * containers of tools/test-x11 (X11) and tools/test-wlroots (sway): the agent installs, `--screen` sends the whole
 * screen and keeps it up to date, the pointer goes where it is told and comes
 * back as the cursor's position, and after a drop a new connection starts
 * with a whole frame again. Opt-in: TITAN_SSH_TEST_HOST/USER (+ _PORT),
 * TITAN_SSH_TEST_KEY or _PASSWORD, TITAN_AGENT_BIN, and TITAN_SSH_TEST_DESKTOP=1
 * (the host has a desktop session for that user; TITAN_SSH_TEST_X11=1 too).
 */
class ScreenTransportIntegrationTest {

    private class Surface(override val width: Int, override val height: Int) : ScreenSurface {
        val pixels = IntArray(width * height)
        override fun write(pixels: IntArray, x: Int, y: Int, w: Int, h: Int) {
            for (row in y until y + h) pixels.copyInto(this.pixels, row * width + x, row * width + x, row * width + x + w)
        }
    }

    private fun env(): Triple<SshEndpoint, () -> SshCredentials, File>? {
        val host = System.getenv("TITAN_SSH_TEST_HOST")
        val user = System.getenv("TITAN_SSH_TEST_USER")
        val key = System.getenv("TITAN_SSH_TEST_KEY")
        val password = System.getenv("TITAN_SSH_TEST_PASSWORD")
        val bin = System.getenv("TITAN_AGENT_BIN")
        if (host == null || user == null || (key == null && password == null) || bin == null ||
            (System.getenv("TITAN_SSH_TEST_DESKTOP") != "1" && System.getenv("TITAN_SSH_TEST_X11") != "1")
        ) {
            println("[integration] remote desktop skipped: set TITAN_SSH_TEST_HOST/USER, _KEY or _PASSWORD, TITAN_AGENT_BIN and TITAN_SSH_TEST_DESKTOP=1")
            return null
        }
        val port = System.getenv("TITAN_SSH_TEST_PORT")?.toIntOrNull() ?: 22
        val creds = {
            if (key != null) {
                SshCredentials.PrivateKey(File(key).readText().toCharArray(), System.getenv("TITAN_SSH_TEST_PASSPHRASE")?.toCharArray())
            } else {
                SshCredentials.Password(password!!.toCharArray())
            }
        }
        return Triple(SshEndpoint(host, port, user), creds, File(bin))
    }

    private suspend fun connect(endpoint: SshEndpoint, creds: () -> SshCredentials): SshSession =
        createSshConnector().connect(endpoint, creds(), { true }, keepAliveSeconds = 0)

    @Test
    fun sees_and_points_at_a_desktop() {
        val (endpoint, creds, bin) = env() ?: return
        val bytes = bin.readBytes()
        val surfaces = mutableListOf<Surface>()
        val screen = RemoteScreen { w, h -> Surface(w, h).also { synchronized(surfaces) { surfaces += it } } }

        runBlocking {
            val s1 = connect(endpoint, creds)
            val launch = assertNotNull(
                (agentDeployer(version = "itest-screen", isStale = { false }) { bytes }.ensureInstalled(s1) as? AgentDeployment.Ready)?.launch,
                "agent should install",
            )
            val started = System.currentTimeMillis()
            var readyAt = 0L
            val t1 = ScreenTransport(s1, launch, screen, ScreenCodec.QUALITY_AUTO) { readyAt = System.currentTimeMillis() }
            val j1 = launch { t1.run() }
            withTimeout(15_000) { while (screen.view.value.frame < 1) delay(20) }
            val first = assertNotNull(screen.view.value.surface) as Surface
            println("[integration] first frame ${first.width}x${first.height} after ${readyAt - started} ms")
            assertTrue(first.width >= 640 && first.height >= 480, "screen ${first.width}x${first.height}")
            assertTrue(first.pixels.toSet().size > 2, "the screen should not be a single colour")

            // Point somewhere: the cursor's position comes back from the agent.
            t1.send(AgentFrame.PointerTo(200, 150))
            withTimeout(5_000) {
                while (screen.view.value.cursorX != 200 || screen.view.value.cursorY != 150) delay(20)
            }
            assertNotNull(screen.view.value.cursor, "the cursor's image should have arrived")

            // Frames keep coming (the test desktop has a clock), each acknowledged.
            val before = screen.view.value.frame
            withTimeout(5_000) { while (screen.view.value.frame <= before) delay(50) }

            // A drop: the channel goes away. The image stays; a new connection
            // starts with a whole frame.
            s1.close()
            j1.join()
            val keptFrame = screen.view.value.frame
            assertEquals(first, screen.view.value.surface)

            val s2 = connect(endpoint, creds)
            try {
                val t2 = ScreenTransport(s2, launch, screen, ScreenCodec.QUALITY_LOSSLESS) {}
                val j2 = launch { t2.run() }
                withTimeout(15_000) { while (screen.view.value.frame <= keptFrame) delay(20) }
                val second = screen.view.value.surface as Surface
                assertTrue(second !== first, "a new connection starts with a new screen and a whole frame")
                assertTrue(second.pixels.toSet().size > 2)
                t2.close()
                j2.join()
            } finally {
                s2.close()
            }
        }
    }
}
