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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives [InputTransport] against a fake `titan-agent --input`: it execs the
 * right command, reports INPUT_READY and the blocked state, sends input frames
 * in order, and explains a refusal from the agent's contract line (ADR-0016).
 */
class InputTransportTest {

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
        var command: String? = null
        override suspend fun openShell(columns: Int, rows: Int): SshShell = error("not used")
        override suspend fun exec(command: String): SshExecChannel {
            this.command = command
            return ch
        }
        override suspend fun close() {}
    }

    private val launch = AgentLaunch("C:\\Users\\u\\AppData\\Local\\titan-ssh\\agent-0.0.1-windows-amd64.exe", RemoteShell.CMD)

    private suspend fun waitUntil(cond: () -> Boolean) = withTimeout(2_000) { while (!cond()) delay(5) }

    @Test
    fun reports_ready_and_blocked_and_sends_frames_in_order() = runBlocking {
        val ch = FakeExec()
        val session = FakeSession(ch)
        val states = mutableListOf<Boolean>()
        val transport = InputTransport(session, launch) { blocked -> synchronized(states) { states += blocked } }
        val job = launch { transport.run() }

        waitUntil { session.command != null }
        assertTrue(session.command!!.endsWith(" --input"), "command: ${session.command}")
        ch.emit(AgentFrame.InputReady())
        waitUntil { transport.ready }
        val frames = listOf(
            AgentFrame.PointerMove(3, -1),
            AgentFrame.PointerButton(InputKeys.BUTTON_LEFT, pressed = true),
            AgentFrame.PointerButton(InputKeys.BUTTON_LEFT, pressed = false),
            AgentFrame.Text("hola"),
        )
        frames.forEach { transport.send(it) }
        ch.emit(AgentFrame.InputReady(blocked = true))
        waitUntil { synchronized(states) { states.size == 2 } }
        assertEquals(listOf(false, true), synchronized(states) { states.toList() })
        assertEquals(frames, ch.sentFrames())

        ch.end()
        job.join()
        assertNull(transport.unavailable, "a clean end is not a refusal")
    }

    @Test
    fun a_refusal_carries_the_agents_reason() = runBlocking {
        val ch = FakeExec(
            stderr = "TITAN_AGENT_ERROR E_NO_DESKTOP the desktop helper did not start within 15s\n",
            exit = 1,
        )
        val transport = InputTransport(FakeSession(ch), launch) { }
        val job = launch { transport.run() }
        ch.end()
        job.join()
        assertFalse(transport.ready)
        assertEquals(AgentDiagnostics.E_NO_DESKTOP, transport.unavailable?.code)
        assertTrue(
            AgentDiagnostics.describeMousepad(transport.unavailable!!).startsWith("El mouse pad no está disponible"),
        )
    }

    @Test
    fun sending_before_the_channel_opens_is_dropped() = runBlocking {
        val transport = InputTransport(FakeSession(FakeExec()), launch) { }
        transport.send(AgentFrame.PointerMove(1, 1)) // no channel yet: nothing to do, no error
    }
}
