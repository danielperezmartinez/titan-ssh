package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.AppChannel
import io.github.danielperezmartinez.titanssh.config.Host
import io.github.danielperezmartinez.titanssh.config.HostAuth
import io.github.danielperezmartinez.titanssh.config.ResolvedConnection
import io.github.danielperezmartinez.titanssh.config.Session
import io.github.danielperezmartinez.titanssh.config.SessionType
import io.github.danielperezmartinez.titanssh.config.TerminalAppearance
import io.github.danielperezmartinez.titanssh.config.offered
import io.github.danielperezmartinez.titanssh.ssh.HostKeyVerifier
import io.github.danielperezmartinez.titanssh.ssh.InMemoryKnownHostsStore
import io.github.danielperezmartinez.titanssh.ssh.SshConnectFailed
import io.github.danielperezmartinez.titanssh.ssh.SshConnector
import io.github.danielperezmartinez.titanssh.ssh.SshCredentials
import io.github.danielperezmartinez.titanssh.ssh.SshEndpoint
import io.github.danielperezmartinez.titanssh.ssh.SshHop
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/** The remote desktop of ADR-0029 is compared in the canary before the stable app gets it. */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopSessionOfferTest {

    @Test
    fun the_remote_desktop_is_offered_only_in_the_canary() {
        assertTrue(SessionType.DESKTOP.experimental)
        assertEquals(AppChannel.isCanary, SessionType.DESKTOP.offered)
    }

    @Test
    fun the_stable_app_does_not_open_a_remote_desktop_session() = runTest {
        if (AppChannel.isCanary) return@runTest
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        var connects = 0
        val connector = object : SshConnector {
            override suspend fun connect(
                endpoint: SshEndpoint,
                credentials: SshCredentials,
                hostKeyVerifier: HostKeyVerifier,
                keepAliveSeconds: Int,
                via: List<SshHop>,
            ): SshSession {
                connects++
                throw SshConnectFailed("unexpected")
            }
        }
        val host = Host(id = "h", alias = "h", hostname = "x", username = "u", auth = HostAuth.Password("r"))
        val tab = SessionTab(
            id = "tab",
            resolved = ResolvedConnection(
                session = Session(id = "s", name = "n", hostId = "h", type = SessionType.DESKTOP),
                host = host,
                endpoint = SshEndpoint("x", 22, "u"),
                auth = host.auth,
                appearance = TerminalAppearance(),
                jumps = emptyList(),
            ),
            connector = connector,
            credentials = { SshCredentials.Password("pw".toCharArray()) },
            knownHostsStore = InMemoryKnownHostsStore(),
            scope = scope,
        )
        tab.start()
        advanceUntilIdle()
        assertEquals(TabPhase.FAILED, tab.status.value.phase)
        assertTrue(tab.status.value.detail.orEmpty().contains("canary"))
        assertEquals(0, connects)
        tab.reconnectNow()
        advanceUntilIdle()
        assertEquals(0, connects)
        assertFalse(SessionType.DESKTOP.offered)
        scope.coroutineContext[Job]?.cancel()
    }
}
