package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.ssh.SshConnectionState
import io.github.danielperezmartinez.titanssh.ssh.SshExecChannel
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import io.github.danielperezmartinez.titanssh.ssh.SshShell
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

/**
 * The client side of [[Transparencia y control del agente en el destino]]: the
 * `--status --json` contract, the warnings derived from it, and how pending
 * closes are applied and forgotten.
 */
class AgentWatchTest {

    /** The exact JSON `TestStatusJSONContract` pins on the Go side. */
    private val goContract =
        """{"schema":1,"state":"running","agent":"1.0.0","cli":"1.0.0","pid":9,"os":"linux","arch":"amd64",""" +
            """"nowMs":3000,"startedMs":1000,"memoryBytes":2048,"sessions":[{"id":"s1","createdMs":1000,""" +
            """"lastUsedMs":2000,"detachedMs":2000,"clients":0,"bufferBytes":5}]}"""

    @Test
    fun parsesTheAgentContract() {
        val r = AgentStatusReport.parse(goContract + "\n")
        assertTrue(r.isRunning)
        assertEquals("1.0.0", r.agent)
        assertEquals(9, r.pid)
        assertEquals(2048L, r.memoryBytes)
        val s = r.sessions.single()
        assertEquals("s1", s.id)
        assertEquals(2000L, s.detachedMs)
        assertEquals(0, s.clients)
        assertNull(s.memoryBytes)
    }

    @Test
    fun parsesMinimalAndFutureReports() {
        val stopped = AgentStatusReport.parse("""{"schema":1,"state":"stopped","cli":"1.0.0","nowMs":5,"sessions":[],"later":true}""")
        assertFalse(stopped.hasDaemon)
        val legacy = AgentStatusReport.parse("""{"schema":1,"state":"legacy","agent":"0.1.0-beta.3","cli":"1.0.0","pid":7,"nowMs":5,"sessions":[]}""")
        assertTrue(legacy.hasDaemon)
        assertTrue(AgentInsights.isOutdated(legacy, "0.1.0-beta.3"), "a legacy daemon is always outdated")
    }

    private fun obs(report: AgentStatusReport, observedAtMs: Long) = AgentObservation(report, observedAtMs)

    private fun session(id: String, detachedMs: Long?, clients: Int = 0) =
        AgentSessionReport(id = id, createdMs = 0, lastUsedMs = detachedMs ?: 0, detachedMs = detachedMs, clients = clients)

    private val hour = 60L * 60 * 1000

    @Test
    fun abandonmentIsMeasuredOnTheAgentClockPlusTheTimeSinceTheQuery() {
        // The destination clock is far off the app's; only differences count.
        val report = AgentStatusReport(
            state = AgentStatusReport.STATE_RUNNING, nowMs = 100 * hour,
            sessions = listOf(session(AgentTransport.sanitizeId("saved-1"), detachedMs = 80 * hour)),
        )
        val seen = obs(report, observedAtMs = 5_000)
        val live = assertNotNull(AgentInsights.sessionOf(seen, "saved-1"))
        assertEquals(20 * hour, AgentInsights.detachedForMs(seen, live, appNowMs = 5_000))
        assertFalse(AgentInsights.isAbandoned(seen, live, appNowMs = 5_000))
        assertTrue(AgentInsights.isAbandoned(seen, live, appNowMs = 5_000 + 4 * hour), "20 h + 4 h since the query")
        assertNull(AgentInsights.sessionOf(seen, "other"))
    }

    @Test
    fun anAttachedSessionIsNeverAbandoned() {
        val report = AgentStatusReport(
            state = AgentStatusReport.STATE_RUNNING, nowMs = 100 * hour,
            sessions = listOf(session("titan-a", detachedMs = null, clients = 1)),
        )
        val seen = obs(report, 0)
        assertFalse(AgentInsights.isAbandoned(seen, report.sessions.single(), appNowMs = 1000 * hour))
    }

    @Test
    fun memoryAndVersionWarnings() {
        val big = AgentStatusReport(state = AgentStatusReport.STATE_RUNNING, agent = "1.0.0", nowMs = 0, memoryBytes = 1L shl 30)
        assertTrue(AgentInsights.isOverMemory(obs(big, 0)))
        assertFalse(AgentInsights.isOverMemory(obs(big.copy(memoryBytes = (1L shl 30) - 1), 0)))
        assertFalse(AgentInsights.isOverMemory(obs(big.copy(memoryBytes = null), 0)))
        assertFalse(AgentInsights.isOutdated(big, "1.0.0"))
        assertTrue(AgentInsights.isOutdated(big, "1.1.0"))
        assertFalse(AgentInsights.isOutdated(big.copy(state = AgentStatusReport.STATE_STOPPED), "1.1.0"))
    }

    @Test
    fun formatsForTheUi() {
        assertEquals("menos de 1 min", AgentInsights.formatDuration(30_000))
        assertEquals("5 min", AgentInsights.formatDuration(5 * 60_000))
        assertEquals("26 h", AgentInsights.formatDuration(26 * hour))
        assertEquals("3 días", AgentInsights.formatDuration(72 * hour))
        assertEquals("1,2 GB", AgentInsights.formatBytes((1.2 * (1L shl 30)).toLong()))
        assertEquals("180 MB", AgentInsights.formatBytes(180L shl 20))
        assertEquals("2,5 MB", AgentInsights.formatBytes((2.5 * (1 shl 20)).toLong()))
    }

    // --- AgentWatch -------------------------------------------------------

    private class MemoryStore(var saved: AgentWatchState = AgentWatchState()) : AgentWatchStore {
        override suspend fun load() = saved
        override suspend fun save(state: AgentWatchState) { saved = state }
    }

    /** Answers `--status` with [status] and records every command it runs. */
    private class FakeAgentSession(
        var status: String,
        val failClose: Boolean = false,
    ) : SshSession {
        val commands = mutableListOf<String>()
        override val state = MutableStateFlow(SshConnectionState.CONNECTED)
        override suspend fun openShell(columns: Int, rows: Int): SshShell = error("not used")
        override suspend fun close() {}
        override suspend fun exec(command: String): SshExecChannel {
            commands += command
            return when {
                "--status" in command -> Exec(status, "", 0)
                "--close-session" in command && failClose ->
                    Exec("", "TITAN_AGENT_ERROR E_AUTH the daemon did not accept the token\n", 1)
                else -> Exec("", "", 0)
            }
        }
    }

    private class Exec(stdout: String, stderr: String, private val exit: Int?) : SshExecChannel {
        override val output: Flow<ByteArray> = if (stdout.isEmpty()) emptyFlow() else flowOf(stdout.encodeToByteArray())
        override val errors: Flow<ByteArray> = if (stderr.isEmpty()) emptyFlow() else flowOf(stderr.encodeToByteArray())
        override suspend fun send(data: ByteArray) {}
        override suspend fun close(): Int? = exit
    }

    private val key = AgentKey("host.example", 22, "u")
    private val launch = AgentLaunch("/home/u/.local/share/titan-ssh/agent-1.0.0-linux-amd64", RemoteShell.POSIX)

    private fun running(vararg ids: String) =
        """{"schema":1,"state":"running","agent":"1.0.0","cli":"1.0.0","nowMs":10,"sessions":[""" +
            ids.joinToString(",") { """{"id":"$it","createdMs":1,"lastUsedMs":1,"detachedMs":1,"clients":0,"bufferBytes":0}""" } +
            "]}"

    private fun newWatch(store: AgentWatchStore, now: Long = 42) =
        AgentWatch(CoroutineScope(SupervisorJob() + Dispatchers.Default), store) { now }

    @Test
    fun syncClosesPendingSessionsThenRecordsTheStatus(): Unit = runBlocking {
        val store = MemoryStore()
        val watch = newWatch(store)
        watch.addPendingClose(key, "titan-gone")
        val session = FakeAgentSession(running("titan-kept"))

        watch.sync(key, AgentControl(session, launch))

        assertEquals(2, session.commands.size)
        assertTrue("--close-session titan-gone" in session.commands[0], session.commands[0])
        assertTrue("--status --json" in session.commands[1], session.commands[1])
        val state = watch.state.value
        assertNull(state.pendingClose[key.id])
        assertEquals(42, state.observations.getValue(key.id).observedAtMs)
        assertEquals(state, store.saved, "every change is persisted")
    }

    @Test
    fun aFailedCloseStaysPendingAndIsReported(): Unit = runBlocking {
        val watch = newWatch(MemoryStore())
        watch.addPendingClose(key, "titan-gone")
        val session = FakeAgentSession(running("titan-gone"), failClose = true)

        val e = assertFailsWith<AgentControlException> { watch.sync(key, AgentControl(session, launch)) }
        assertTrue("E_AUTH" in e.message.orEmpty(), e.message)
        assertEquals(listOf("titan-gone"), watch.state.value.pendingClose[key.id])
        assertNotNull(watch.errors.value[key.id])
    }

    @Test
    fun aRunningReportForgetsPendingSessionsItNoLongerLists(): Unit = runBlocking {
        val watch = newWatch(MemoryStore())
        watch.addPendingClose(key, "titan-a")
        watch.addPendingClose(key, "titan-b")
        watch.record(key, AgentStatusReport.parse(running("titan-b")))
        assertEquals(listOf("titan-b"), watch.state.value.pendingClose[key.id])

        // A legacy daemon lists nothing, which proves nothing: keep them.
        watch.record(key, AgentStatusReport.parse("""{"schema":1,"state":"legacy","cli":"1","nowMs":1,"sessions":[]}"""))
        assertEquals(listOf("titan-b"), watch.state.value.pendingClose[key.id])
    }

    @Test
    fun theFileStoreRoundTrips(): Unit = runBlocking {
        val dir = Files.createTempDirectory("agents").toFile()
        try {
            val file = dir.resolve(JsonFileAgentWatchStore.FILE_NAME)
            val store = JsonFileAgentWatchStore(file)
            assertEquals(AgentWatchState(), store.load(), "a missing file loads empty")
            val state = AgentWatchState(
                observations = mapOf(key.id to AgentObservation(AgentStatusReport.parse(goContract), 7)),
                pendingClose = mapOf(key.id to listOf("titan-x")),
            )
            store.save(state)
            assertEquals(state, JsonFileAgentWatchStore(file).load())
            file.writeText("{ not json")
            assertEquals(AgentWatchState(), store.load(), "an unreadable file loads empty")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theAgentSessionIdOfASavedSession() {
        assertEquals("titan-abc_1", AgentTransport.sanitizeId("abc/1"))
        assertEquals("u@host.example", key.label)
        assertEquals("u@host.example:2222", key.copy(port = 2222).label)
    }
}
