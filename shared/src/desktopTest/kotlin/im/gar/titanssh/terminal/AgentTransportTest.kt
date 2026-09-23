package im.gar.titanssh.terminal

import im.gar.titanssh.ssh.SshConnectionState
import im.gar.titanssh.ssh.SshExecChannel
import im.gar.titanssh.ssh.SshSession
import im.gar.titanssh.ssh.SshShell
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives [AgentTransport] against a fake exec channel that stands in for
 * `titan-agent`: verifies the opening HELLO, that DATA is fed to the emulator
 * with offset-based de-duplication of replay, that a gap is accepted, that input
 * becomes INPUT frames, and that each DATA is acknowledged (ADR-0008).
 */
class AgentTransportTest {

    private class FakeExec : SshExecChannel {
        private val outCh = Channel<ByteArray>(Channel.UNLIMITED)
        override val output: Flow<ByteArray> = outCh.receiveAsFlow()
        override val errors: Flow<ByteArray> = emptyFlow()

        private val decoder = AgentProtocol.FrameDecoder()
        val sent = mutableListOf<AgentFrame>()

        override suspend fun send(data: ByteArray) {
            synchronized(sent) { decoder.feed(data).forEach { sent += it } }
        }

        override suspend fun close(): Int? {
            outCh.close()
            return 0
        }

        /** Simulate the agent emitting a frame to the client. */
        fun emit(frame: AgentFrame) {
            outCh.trySend(AgentProtocol.encode(frame))
        }

        fun sentFrames(): List<AgentFrame> = synchronized(sent) { sent.toList() }
    }

    private class FakeSession(private val ch: FakeExec) : SshSession {
        override val state = MutableStateFlow(SshConnectionState.CONNECTED)
        override suspend fun openShell(columns: Int, rows: Int): SshShell = error("not used")
        override suspend fun exec(command: String): SshExecChannel = ch
        override suspend fun close() {}
    }

    private suspend fun waitUntil(cond: () -> Boolean) =
        withTimeout(2_000) { while (!cond()) delay(5) }

    @Test
    fun sends_hello_applies_data_dedups_replay_and_acks() = runBlocking {
        val ch = FakeExec()
        val outputs = Channel<String>(Channel.UNLIMITED)
        val transport = AgentTransport(
            session = FakeSession(ch),
            agentSessionId = "sess_42",
            agentPath = "~/.local/share/titan-ssh/agent-0.0.1-linux-amd64",
            onOutput = { bytes -> outputs.trySend(bytes.decodeToString()) },
            initialColumns = 80,
            initialRows = 24,
            startOffset = 0,
        )
        val job = launch { transport.run() }

        // Opening HELLO carries the (sanitized) session id and the size.
        waitUntil { ch.sentFrames().any { it is AgentFrame.Hello } }
        val hello = ch.sentFrames().filterIsInstance<AgentFrame.Hello>().first()
        assertEquals("titan-sess_42", hello.sessionId)
        assertEquals(80, hello.columns)
        assertEquals(0L, hello.lastOffset)

        ch.emit(AgentFrame.HelloOk(headOffset = 0, tailOffset = 0))
        ch.emit(AgentFrame.Data(offset = 0, bytes = "abc".encodeToByteArray()))
        assertEquals("abc", outputs.receive())
        assertEquals(3L, transport.appliedOffset)

        // Overlapping replay from 0 ("abcde"): only the new tail "de" is applied.
        ch.emit(AgentFrame.Data(offset = 0, bytes = "abcde".encodeToByteArray()))
        assertEquals("de", outputs.receive())
        assertEquals(5L, transport.appliedOffset)

        // A gap (buffer trimmed: offset jumps ahead of appliedOffset) is fed as-is.
        ch.emit(AgentFrame.Data(offset = 10, bytes = "XY".encodeToByteArray()))
        assertEquals("XY", outputs.receive())
        assertEquals(12L, transport.appliedOffset)

        // Client input becomes an INPUT frame; DATA is acknowledged.
        transport.sendInput("ls\n".encodeToByteArray())
        waitUntil { ch.sentFrames().any { it is AgentFrame.Input } }
        assertTrue(ch.sentFrames().any { it is AgentFrame.Ack }, "each DATA should be ACKed")
        val input = ch.sentFrames().filterIsInstance<AgentFrame.Input>().first()
        assertEquals("ls\n", input.bytes.decodeToString())

        ch.close()
        job.join()
    }

    @Test
    fun resize_sends_a_resize_frame() = runBlocking {
        val ch = FakeExec()
        val transport = AgentTransport(
            session = FakeSession(ch),
            agentSessionId = "s",
            agentPath = "agent",
            onOutput = {},
            initialColumns = 80,
            initialRows = 24,
        )
        val job = launch { transport.run() }
        waitUntil { ch.sentFrames().any { it is AgentFrame.Hello } }
        transport.resize(120, 40)
        waitUntil { ch.sentFrames().any { it is AgentFrame.Resize } }
        val r = ch.sentFrames().filterIsInstance<AgentFrame.Resize>().first()
        assertEquals(120, r.columns)
        assertEquals(40, r.rows)
        ch.close()
        job.join()
    }
}
