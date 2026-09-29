package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.Host
import io.github.danielperezmartinez.titanssh.config.HostAuth
import io.github.danielperezmartinez.titanssh.ssh.HostTrustPrompt
import io.github.danielperezmartinez.titanssh.ssh.KnownHostsStore
import io.github.danielperezmartinez.titanssh.ssh.KnownHostsVerifier
import io.github.danielperezmartinez.titanssh.ssh.SshConnector
import io.github.danielperezmartinez.titanssh.ssh.SshEndpoint
import io.github.danielperezmartinez.titanssh.ssh.SshHostKeyRejected

/**
 * What the agent panel and the launcher need to reach an agent without a tab:
 * the destination user's [endpoint] and how to authenticate.
 */
data class AgentHost(val endpoint: SshEndpoint, val auth: HostAuth, val keepAliveSeconds: Int) {
    val key: AgentKey get() = AgentKey.of(endpoint)

    companion object {
        /** The agent of [host]'s default user. */
        fun of(host: Host) = AgentHost(SshEndpoint(host.hostname, host.port, host.username), host.auth, host.keepAliveSeconds)
    }
}

/**
 * Opens a short-lived connection to an agent's destination to run control
 * commands ([[Transparencia y control del agente en el destino]]): refresh the
 * panel, terminate a session from the launcher, stop or update the agent.
 * Every connection also [AgentWatch.sync]s, so pending closes run and the
 * status is fresh.
 *
 * A host key the app has never trusted is refused, not prompted: trusting a new
 * key happens in a terminal tab, where the user sees the fingerprint.
 */
class AgentHostAccess(
    private val connector: SshConnector,
    private val credentialResolver: CredentialResolver,
    private val knownHostsStore: KnownHostsStore,
    private val agentDeployer: AgentDeployer?,
    private val watch: AgentWatch,
) {
    /** Connects, syncs, runs [block] (if any) and records the status it leaves. */
    suspend fun <T> withAgent(host: AgentHost, block: suspend (AgentControl) -> T): T {
        val key = host.key
        try {
            val deployer = agentDeployer ?: throw AgentControlException("Esta compilación no incluye el agente")
            val creds = credentialResolver.resolve(host.auth)
            val verifier = KnownHostsVerifier(knownHostsStore, HostTrustPrompt { false })
            val session = try {
                connector.connect(host.endpoint, creds, verifier, host.keepAliveSeconds)
            } catch (e: SshHostKeyRejected) {
                throw AgentControlException(
                    "La clave de este host no es de confianza todavía: abre una sesión con él para confirmarla",
                    e,
                )
            } finally {
                creds.wipe()
            }
            try {
                val launch = when (val deployment = deployer.ensureInstalled(session)) {
                    is AgentDeployment.Ready -> deployment.launch
                    is AgentDeployment.Unavailable -> throw AgentControlException(
                        AgentDiagnostics.describe(deployment.issue),
                    )
                }
                val control = AgentControl(session, launch)
                watch.sync(key, control)
                val result = block(control)
                watch.sync(key, control)
                return result
            } finally {
                runCatching { session.close() }
            }
        } catch (e: Exception) {
            watch.recordError(key, e.message ?: "No se pudo conectar con el destino")
            throw e
        }
    }

    /** Connects only to refresh what the app knows about [host]'s agent. */
    suspend fun refresh(host: AgentHost) {
        withAgent(host) { }
    }
}
