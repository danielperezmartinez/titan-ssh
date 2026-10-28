package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.Host
import io.github.danielperezmartinez.titanssh.config.HostAuth
import io.github.danielperezmartinez.titanssh.config.ResolvedConnection
import io.github.danielperezmartinez.titanssh.config.Session
import io.github.danielperezmartinez.titanssh.config.TerminalAppearance
import io.github.danielperezmartinez.titanssh.ssh.InMemoryKnownHostsStore
import io.github.danielperezmartinez.titanssh.ssh.SshCredentials
import io.github.danielperezmartinez.titanssh.ssh.SshEndpoint
import io.github.danielperezmartinez.titanssh.ssh.createSshConnector
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The quality bar against a real server ([[Indicar cuando la conexión deja de
 * responder]]): a frozen server shows no response, a delayed one shows slow,
 * both come back to good on their own, and the tab never leaves CONNECTED.
 *
 * Opt-in: needs the usual test host properties plus
 * `-PtitanQualityContainer=<name>`, a throwaway Docker container (NET_ADMIN,
 * `tc` installed) that the test host points at. The test pauses it and adds
 * latency to it, so it must never be a shared one.
 */
class SessionTabQualityIntegrationTest {

    @Test
    fun a_frozen_or_slow_server_shows_on_the_bar_without_reconnecting() {
        val host = System.getenv("TITAN_SSH_TEST_HOST")
        val user = System.getenv("TITAN_SSH_TEST_USER")
        val keyPath = System.getenv("TITAN_SSH_TEST_KEY")
        val container = System.getenv("TITAN_QUALITY_CONTAINER")
        if (host == null || user == null || keyPath == null || container == null) {
            println("[integration] skipped: set TITAN_SSH_TEST_HOST/USER/KEY and TITAN_QUALITY_CONTAINER to run")
            return
        }
        val port = System.getenv("TITAN_SSH_TEST_PORT")?.toIntOrNull() ?: 22

        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val hostConfig = Host(
                id = "h",
                alias = "quality",
                hostname = host,
                port = port,
                username = user,
                auth = HostAuth.SoftwareKey(secretRef = "unused-in-test"),
            )
            val tab = SessionTab(
                id = "tab-quality",
                resolved = ResolvedConnection(
                    session = Session(id = "s", name = "quality", hostId = "h"),
                    host = hostConfig,
                    endpoint = SshEndpoint(host, port, user),
                    auth = hostConfig.auth,
                    appearance = TerminalAppearance(),
                    jumps = emptyList(),
                ),
                connector = createSshConnector(),
                credentials = { SshCredentials.PrivateKey(File(keyPath).readText().toCharArray(), null) },
                knownHostsStore = InMemoryKnownHostsStore(),
                scope = scope,
                columns = 80,
                rows = 24,
            )
            scope.launch { tab.pendingHostKey.collect { it?.accept() } }
            val phases = mutableSetOf<TabPhase>()
            val grades = mutableListOf<QualityGrade>()
            try {
                tab.start()
                awaitThat("the tab connects") { tab.status.value.phase == TabPhase.CONNECTED }
                scope.launch { tab.status.collect { phases += it.phase } }
                scope.launch { tab.quality.collect { grades += it.grade; println("[integration] ${it.label}") } }
                val watcher = scope.launch { tab.watchQuality() }
                delay(5_000)
                assertEquals(QualityGrade.GOOD, tab.quality.value.grade, "an idle healthy server is good")

                docker("pause", container)
                try {
                    awaitThat("a frozen server shows no response", 15_000) {
                        tab.quality.value.grade == QualityGrade.NO_RESPONSE
                    }
                } finally {
                    docker("unpause", container)
                }
                awaitThat("it comes back to good", 15_000) { tab.quality.value.grade == QualityGrade.GOOD }

                docker("exec", container, "tc", "qdisc", "add", "dev", "eth0", "root", "netem", "delay", "900ms")
                try {
                    awaitThat("a delayed server shows slow", 15_000) { tab.quality.value.grade == QualityGrade.SLOW }
                } finally {
                    docker("exec", container, "tc", "qdisc", "del", "dev", "eth0", "root")
                }
                awaitThat("it comes back to good", 15_000) { tab.quality.value.grade == QualityGrade.GOOD }

                watcher.cancel()
                delay(500)
                assertEquals(QualityGrade.GOOD, tab.quality.value.grade, "nothing is watched once off screen")
                assertEquals(setOf(TabPhase.CONNECTED), phases, "the bar never makes the tab reconnect")
                assertTrue(QualityGrade.NO_RESPONSE in grades && QualityGrade.SLOW in grades)
            } finally {
                tab.close()
                scope.cancel()
            }
        }
    }

    private suspend fun awaitThat(what: String, millis: Long = 15_000, check: () -> Boolean) {
        val ok = withTimeoutOrNull(millis) {
            while (!check()) delay(100)
            true
        } ?: false
        assertTrue(ok, what)
    }

    private fun docker(vararg args: String) {
        val process = ProcessBuilder("docker", *args).inheritIO().start()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "docker ${args.joinToString(" ")} failed" }
    }
}
