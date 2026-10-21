package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.ssh.SshConnectionState
import io.github.danielperezmartinez.titanssh.ssh.SshExecChannel
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import io.github.danielperezmartinez.titanssh.ssh.SshShell
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives [ScreenTransport] against a fake `titan-agent --screen`: the blocked
 * state of a locked Windows comes as INPUT_READY (ADR-0031 §4), also before
 * any frame, and the new error codes explain a refusal.
 */
class ScreenTransportTest {

    private class FakeExec(stderr: String = "", private val exit: Int? = 0) : SshExecChannel {
        private val outCh = Channel<ByteArray>(Channel.UNLIMITED)
        override val output: Flow<ByteArray> = outCh.receiveAsFlow()
        override val errors: Flow<ByteArray> = if (stderr.isEmpty()) flowOf() else flowOf(stderr.encodeToByteArray())

        private val decoder = AgentProtocol.FrameDecoder()
        private val sent = mutableListOf<AgentFrame>()

        override suspend fun send(data: ByteArray) {
            synchronized(sent) { decoder.feed(data).forEach { sent += it } }
        }

        override suspend fun close(): Int? {
            outCh.close()
            return exit
        }

        fun emit(frame: AgentFrame) {
            outCh.trySend(AgentProtocol.encode(frame))
        }

        fun end() = outCh.close()

        fun sentFrames(): List<AgentFrame> = synchronized(sent) { sent.toList() }
    }

    private class FakeSession(private val ch: FakeExec) : SshSession {
        override val state = MutableStateFlow(SshConnectionState.CONNECTED)
        override suspend fun openShell(columns: Int, rows: Int): SshShell = error("not used")
        override suspend fun exec(command: String): SshExecChannel = ch
        override suspend fun close() {}
    }

    private class NullSurface(override val width: Int, override val height: Int) : ScreenSurface {
        override fun write(pixels: IntArray, x: Int, y: Int, w: Int, h: Int) {}
    }

    private val launch = AgentLaunch("C:\\Users\\u\\AppData\\Local\\titan-ssh\\agent-0.0.1-windows-amd64.exe", RemoteShell.CMD)

    private suspend fun waitUntil(cond: () -> Boolean) = withTimeout(2_000) { while (!cond()) delay(5) }

    @Test
    fun a_screen_locked_from_the_start_is_ready_and_blocked() = runBlocking {
        val ch = FakeExec()
        val states = mutableListOf<Boolean>()
        var readies = 0
        val transport = ScreenTransport(
            FakeSession(ch), launch, RemoteScreen { w, h -> NullSurface(w, h) }, ScreenCodec.QUALITY_AUTO,
            onBlocked = { synchronized(states) { states += it } },
        ) { readies++ }
        val job = launch { transport.run() }

        // Locked before the first frame: the tab is up, showing why there is no image.
        ch.emit(AgentFrame.InputReady(blocked = true))
        waitUntil { transport.ready }
        // Unlocked: the frames start, and the first one is acknowledged.
        ch.emit(AgentFrame.InputReady(blocked = false))
        ch.emit(AgentFrame.ScreenSize(2, 2))
        ch.emit(AgentFrame.ScreenTile(0, 0, 2, 2, ScreenCodec.ENCODING_FILL, byteArrayOf(1, 2, 3)))
        ch.emit(AgentFrame.ScreenFrameEnd(1))
        waitUntil { ch.sentFrames().any { it is AgentFrame.ScreenAck } }

        assertEquals(listOf(true, false), synchronized(states) { states.toList() })
        assertEquals(1, readies, "ready once per connection")
        assertTrue(AgentFrame.ScreenAck(1) in ch.sentFrames())
        ch.end()
        job.join()
    }

    @Test
    fun an_inactive_session_is_explained() = runBlocking {
        val ch = FakeExec(
            stderr = "TITAN_AGENT_ERROR E_DESKTOP_INACTIVE the user's graphical session is not on screen: " +
                "another user is signed in on the desktop\n",
            exit = 1,
        )
        val transport = ScreenTransport(FakeSession(ch), launch, RemoteScreen { w, h -> NullSurface(w, h) }, 0) { }
        val job = launch { transport.run() }
        ch.end()
        job.join()
        assertFalse(transport.ready)
        val issue = transport.unavailable!!
        assertEquals(AgentDiagnostics.E_DESKTOP_INACTIVE, issue.code)
        val text = AgentDiagnostics.describeDesktop(issue)
        assertTrue(text.startsWith("El escritorio remoto no está disponible: tu sesión en el destino no está en la pantalla"), text)
        assertTrue(text.contains("another user is signed in"), text)
    }
}
