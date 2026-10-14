package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.ReopenMode
import io.github.danielperezmartinez.titanssh.config.ResilienceLevel
import io.github.danielperezmartinez.titanssh.config.Session
import io.github.danielperezmartinez.titanssh.config.SessionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionInstancesTest {

    private fun session(
        level: ResilienceLevel = ResilienceLevel.AGENT,
        reopen: ReopenMode = ReopenMode.ASK,
        type: SessionType = SessionType.TERMINAL,
    ) = Session(id = "session-00ff", name = "deploy", hostId = "h", resilienceLevel = level, reopen = reopen, type = type)

    @Test
    fun principalKeepsTheSavedSessionId() {
        assertEquals("session-00ff", SessionInstances.id("session-00ff", 1))
        assertEquals("titan-session-00ff", SessionInstances.agentId("session-00ff", 1))
        assertEquals("session-00ff-p2", SessionInstances.id("session-00ff", 2))
        assertEquals("titan-session-00ff-p3", SessionInstances.agentId("session-00ff", 3))
    }

    @Test
    fun aNameTheUserGaveReplacesTheNumberedLabel() {
        assertEquals("deploy", SessionInstances.label("deploy", 1))
        assertEquals("deploy · 2", SessionInstances.label("deploy", 2))
        assertEquals("logs", SessionInstances.label("deploy", 2, "  logs "))
        assertEquals("logs", SessionInstances.label("deploy", 1, "logs"))
        assertEquals("deploy · 3", SessionInstances.label("deploy", 3, "  "))
        assertEquals("deploy · 3", SessionInstances.label("deploy", 3, null))
    }

    @Test
    fun agentIdsMapBackToTheirInstance() {
        assertEquals(1, SessionInstances.instanceOf("session-00ff", "titan-session-00ff"))
        assertEquals(2, SessionInstances.instanceOf("session-00ff", "titan-session-00ff-p2"))
        assertEquals(12, SessionInstances.instanceOf("session-00ff", "titan-session-00ff-p12"))
        assertNull(SessionInstances.instanceOf("session-00ff", "titan-session-00ff-p1"))
        assertNull(SessionInstances.instanceOf("session-00ff", "titan-session-00ff-p02"))
        assertNull(SessionInstances.instanceOf("session-00ff", "titan-session-00ff-px"))
        assertNull(SessionInstances.instanceOf("session-00ff", "titan-session-00f"))
        assertNull(SessionInstances.instanceOf("session-00ff", "titan-session-00ffa"))
    }

    @Test
    fun aNewParallelTakesTheLowestFreeNumber() {
        assertEquals(2, SessionInstances.nextParallel(setOf(1)))
        assertEquals(3, SessionInstances.nextParallel(setOf(1, 2, 4)))
        assertEquals(2, SessionInstances.nextParallel(setOf(3)))
    }

    @Test
    fun labelsNameParallelInstances() {
        assertEquals("deploy", SessionInstances.label("deploy", 1))
        assertEquals("deploy · 2", SessionInstances.label("deploy", 2))
    }

    @Test
    fun nothingOpenOrAliveOpensThePrincipal() {
        assertEquals(LaunchPlan.Open(1), SessionInstances.plan(session(), emptySet(), emptySet()))
        assertEquals(LaunchPlan.Open(1), SessionInstances.plan(session(reopen = ReopenMode.PARALLEL), emptySet(), emptySet()))
    }

    @Test
    fun baseLevelAndMousepadNeverAsk() {
        assertEquals(LaunchPlan.Open(1), SessionInstances.plan(session(level = ResilienceLevel.BASE), setOf(1), emptySet()))
        assertEquals(LaunchPlan.Open(1), SessionInstances.plan(session(type = SessionType.MOUSEPAD), setOf(1), setOf(1)))
    }

    @Test
    fun askListsEveryKnownInstanceAndThePrincipal() {
        val plan = SessionInstances.plan(session(), openInTabs = setOf(3), live = setOf(2, 3))
        assertEquals(
            LaunchPlan.Ask(
                options = listOf(
                    InstanceOption(1, openInTab = false, live = false),
                    InstanceOption(2, openInTab = false, live = true),
                    InstanceOption(3, openInTab = true, live = true),
                ),
                nextParallel = 4,
            ),
            plan,
        )
    }

    @Test
    fun theSettingSkipsTheQuestion() {
        assertEquals(LaunchPlan.Open(1), SessionInstances.plan(session(reopen = ReopenMode.ATTACH), setOf(1), setOf(1, 2)))
        assertEquals(
            LaunchPlan.Open(3, mustBeNew = true),
            SessionInstances.plan(session(reopen = ReopenMode.PARALLEL), setOf(1), setOf(1, 2)),
        )
    }

    @Test
    fun aTabInstanceSkipsTakenNumbersUntilItAttaches() {
        val seen = mutableListOf<Int>()
        val instance = TabInstance("session-00ff", start = 2, mustBeNew = true) { seen += it }
        assertEquals("session-00ff-p2", instance.id)
        assertTrue(instance.skipTaken())
        assertEquals("session-00ff-p3", instance.id)
        assertEquals(listOf(3), seen)
        instance.claimed()
        assertFalse(instance.mustBeNew)
    }

    @Test
    fun aTabInstanceStopsSkippingAfterABound() {
        val instance = TabInstance("s", start = 2, mustBeNew = true)
        while (instance.skipTaken()) Unit
        assertFalse(instance.mustBeNew)
        assertEquals(34, instance.number.value)
    }

    @Test
    fun agentInsightsGroupsInstancesOfASavedSession() {
        fun s(id: String, closed: Boolean = false) = AgentSessionReport(
            id = id, createdMs = 0, lastUsedMs = 0, detachedMs = null, clients = 0, closed = closed, bufferBytes = 0,
        )
        val report = AgentStatusReport(
            state = AgentStatusReport.STATE_RUNNING,
            nowMs = 0,
            sessions = listOf(
                s("titan-session-00ff"),
                s("titan-session-00ff-p2"),
                s("titan-session-00ff-p3", closed = true),
                s("titan-other"),
            ),
        )
        val obs = AgentObservation(report, observedAtMs = 0)
        assertEquals(setOf(1, 2), AgentInsights.instancesOf(obs, "session-00ff").keys)
        assertEquals("titan-session-00ff", AgentInsights.sessionOf(obs, "session-00ff")?.id)
    }
}
