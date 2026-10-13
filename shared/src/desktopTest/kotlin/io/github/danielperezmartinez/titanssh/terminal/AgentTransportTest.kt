package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.ssh.SshConnectionState
import io.github.danielperezmartinez.titanssh.ssh.SshExecChannel
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import io.github.danielperezmartinez.titanssh.ssh.SshShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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
import kotlin.test.assertFalse
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
            agent = AgentLaunch("/home/u/.local/share/titan-ssh/agent-0.0.1-linux-amd64", RemoteShell.POSIX),
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
            agent = AgentLaunch("agent", RemoteShell.POSIX),
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

    @Test
    fun a_new_parallel_instance_leaves_a_live_session_alone() = runBlocking {
        val ch = FakeExec()
        val outputs = mutableListOf<String>()
        var attached = false
        val transport = AgentTransport(
            session = FakeSession(ch),
            agentSessionId = "s-p2",
            agent = AgentLaunch("agent", RemoteShell.POSIX),
            onOutput = { outputs += it.decodeToString() },
            initialColumns = 80,
            initialRows = 24,
            onAttached = { attached = true },
            requireFresh = true,
        )
        val job = launch { transport.run() }
        waitUntil { ch.sentFrames().any { it is AgentFrame.Hello } }
        // The agent already holds titan-s-p2, and replays it.
        ch.emit(AgentFrame.HelloOk(headOffset = 0, tailOffset = 5, created = false))
        ch.emit(AgentFrame.Data(offset = 0, bytes = "busy$".encodeToByteArray()))
        job.join()
        assertTrue(transport.occupied)
        assertFalse(attached, "someone else's terminal is not attached")
        assertTrue(outputs.isEmpty(), "nothing of it reaches the emulator")
        assertTrue(ch.sentFrames().any { it is AgentFrame.Bye })
    }

    @Test
    fun a_new_parallel_instance_attaches_to_a_fresh_session() = runBlocking {
        val ch = FakeExec()
        val fresh = CompletableDeferred<Boolean>()
        val transport = AgentTransport(
            session = FakeSession(ch),
            agentSessionId = "s-p2",
            agent = AgentLaunch("agent", RemoteShell.POSIX),
            onOutput = {},
            initialColumns = 80,
            initialRows = 24,
            onAttached = { fresh.complete(it) },
            requireFresh = true,
        )
        val job = launch { transport.run() }
        waitUntil { ch.sentFrames().any { it is AgentFrame.Hello } }
        ch.emit(AgentFrame.HelloOk(headOffset = 0, tailOffset = 0, created = true))
        assertTrue(withTimeout(2_000) { fresh.await() })
        assertFalse(transport.occupied)
        ch.close()
        job.join()
    }

    /** Runs a transport through one HELLO_OK and returns what onAttached reported. */
    private suspend fun attach(
        helloOk: AgentFrame.HelloOk,
        startOffset: Long,
        then: suspend (FakeExec, Channel<String>, AgentTransport) -> Unit = { _, _, _ -> },
    ): Boolean = coroutineScope {
        val ch = FakeExec()
        val outputs = Channel<String>(Channel.UNLIMITED)
        val attached = CompletableDeferred<Boolean>()
        val transport = AgentTransport(
            session = FakeSession(ch),
            agentSessionId = "s",
            agent = AgentLaunch("agent", RemoteShell.POSIX),
            onOutput = { bytes -> outputs.trySend(bytes.decodeToString()) },
            initialColumns = 80,
            initialRows = 24,
            startOffset = startOffset,
            onAttached = { fresh -> attached.complete(fresh) },
        )
        val job = launch { transport.run() }
        waitUntil { ch.sentFrames().any { it is AgentFrame.Hello } }
        ch.emit(helloOk)
        val fresh = withTimeout(2_000) { attached.await() }
        then(ch, outputs, transport)
        ch.close()
        job.join()
        fresh
    }

    @Test
    fun a_fresh_session_resets_an_offset_carried_from_a_lost_one() = runBlocking {
        // The tab had applied 500 bytes of a session the host no longer has.
        val fresh = attach(AgentFrame.HelloOk(0, 0, created = true), startOffset = 500) { ch, outputs, t ->
            ch.emit(AgentFrame.Data(offset = 0, bytes = "new$ ".encodeToByteArray()))
            assertEquals("new$ ", outputs.receive())
            assertEquals(5L, t.appliedOffset)
        }
        assertTrue(fresh)
    }

    @Test
    fun a_reattach_is_not_fresh_and_keeps_the_offset() = runBlocking {
        val fresh = attach(AgentFrame.HelloOk(6, 0, created = false), startOffset = 3) { ch, outputs, t ->
            ch.emit(AgentFrame.Data(offset = 3, bytes = "def".encodeToByteArray()))
            assertEquals("def", outputs.receive())
            assertEquals(6L, t.appliedOffset)
        }
        assertFalse(fresh)
    }

    @Test
    fun an_agent_without_the_flag_is_fresh_only_before_any_output() = runBlocking {
        assertTrue(attach(AgentFrame.HelloOk(0, 0, created = null), startOffset = 0))
        assertFalse(attach(AgentFrame.HelloOk(91, 0, created = null), startOffset = 0))
        // Still resets when the agent's head is behind the tab (a different session).
        attach(AgentFrame.HelloOk(4, 0, created = null), startOffset = 500) { ch, outputs, t ->
            ch.emit(AgentFrame.Data(offset = 4, bytes = "$ ".encodeToByteArray()))
            assertEquals("$ ", outputs.receive())
            assertEquals(6L, t.appliedOffset)
        }
        Unit
    }
}
