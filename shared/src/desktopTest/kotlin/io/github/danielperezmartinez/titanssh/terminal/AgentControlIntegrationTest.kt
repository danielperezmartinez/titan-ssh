package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.ssh.SshCredentials
import io.github.danielperezmartinez.titanssh.ssh.SshEndpoint
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import io.github.danielperezmartinez.titanssh.ssh.createSshConnector
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * [[Transparencia y control del agente en el destino]] against a real host:
 * a level-3 session left detached shows up in `--status`, a pending close
 * removes it on the next sync, and `--stop` ends the daemon. Opt-in: set
 * TITAN_SSH_TEST_HOST/USER (+ _PORT) and TITAN_SSH_TEST_KEY or
 * TITAN_SSH_TEST_PASSWORD, plus TITAN_AGENT_BIN (a binary for the host). It
 * stops the user's daemon on the host, so use a test destination.
 */
class AgentControlIntegrationTest {

    private fun env(): Triple<SshEndpoint, () -> SshCredentials, File>? {
        val host = System.getenv("TITAN_SSH_TEST_HOST")
        val user = System.getenv("TITAN_SSH_TEST_USER")
        val key = System.getenv("TITAN_SSH_TEST_KEY")
        val password = System.getenv("TITAN_SSH_TEST_PASSWORD")
        val bin = System.getenv("TITAN_AGENT_BIN")
        if (host == null || user == null || (key == null && password == null) || bin == null) {
            println("[integration] agent-control skipped: set TITAN_SSH_TEST_HOST/USER, _KEY or _PASSWORD, and TITAN_AGENT_BIN")
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

    private class MemoryStore : AgentWatchStore {
        var saved = AgentWatchState()
        override suspend fun load() = saved
        override suspend fun save(state: AgentWatchState) { saved = state }
    }

    @Test
    fun reports_closes_and_stops_on_a_real_host() {
        val (endpoint, creds, bin) = env() ?: return
        val bytes = bin.readBytes()
        val version = "itest-${System.currentTimeMillis()}"
        val savedId = "ctl-${System.currentTimeMillis()}"
        val agentId = AgentTransport.sanitizeId(savedId)
        val key = AgentKey.of(endpoint)

        runBlocking {
            val s = connect(endpoint, creds)
            val deployment = agentDeployer(version = version, isStale = { false }) { bytes }.ensureInstalled(s)
            val agentLaunch = assertNotNull((deployment as? AgentDeployment.Ready)?.launch, "agent should install")
            val control = AgentControl(s, agentLaunch)
            try {
                control.stop() // a clean slate: no daemon of an earlier run

                // A session that the client leaves: the daemon keeps it, detached.
                val seen = StringBuilder()
                val t = AgentTransport(s, savedId, agentLaunch, { b -> synchronized(seen) { seen.append(b.decodeToString()) } }, 80, 24)
                val j = launch { t.run() }
                withTimeout(10_000) { while (synchronized(seen) { seen.isEmpty() }) delay(20) }
                t.close()
                j.join()

                var report = control.status()
                withTimeout(10_000) {
                    while (report.sessions.firstOrNull { it.id == agentId }?.detachedMs == null) {
                        delay(100)
                        report = control.status()
                    }
                }
                assertTrue(report.isRunning, "state: ${report.state}")
                val listed = report.sessions.single { it.id == agentId }
                assertEquals(0, listed.clients)
                assertTrue(listed.bufferBytes > 0, "the prompt is in the history")
                println("[integration] status: agent ${report.agent} on ${report.os}/${report.arch}, memory ${report.memoryBytes}, session memory ${listed.memoryBytes}")

                // Deleting the saved session: the next sync closes it on the host.
                val watch = AgentWatch(CoroutineScope(SupervisorJob() + Dispatchers.Default), MemoryStore())
                watch.addPendingClose(key, agentId)
                val after = watch.sync(key, control)
                assertNull(after.sessions.firstOrNull { it.id == agentId }, "the pending session is closed")
                assertNull(watch.state.value.pendingClose[key.id])

                control.stop()
                assertEquals(AgentStatusReport.STATE_STOPPED, control.status().state)
                println("[integration] agent control verified end-to-end (status, pending close, stop)")
            } finally {
                runCatching { control.stop() }
                s.openSftp()?.let { sftp ->
                    val file = if (agentLaunch.shell == RemoteShell.POSIX) agentLaunch.path
                    else AgentInstall.windowsToSftpPath(agentLaunch.path)
                    runCatching { sftp.remove(file) }
                    sftp.close()
                }
                s.close()
            }
        }
    }
}
