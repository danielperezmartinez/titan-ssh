package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.Host
import io.github.danielperezmartinez.titanssh.config.HostAuth
import io.github.danielperezmartinez.titanssh.config.ResolvedConnection
import io.github.danielperezmartinez.titanssh.config.TitanConfig
import io.github.danielperezmartinez.titanssh.config.jumpChain
import io.github.danielperezmartinez.titanssh.config.jumpEndpoint
import io.github.danielperezmartinez.titanssh.ssh.HostKeyRejection
import io.github.danielperezmartinez.titanssh.ssh.HostTrustPrompt
import io.github.danielperezmartinez.titanssh.ssh.KnownHostsStore
import io.github.danielperezmartinez.titanssh.ssh.KnownHostsVerifier
import io.github.danielperezmartinez.titanssh.ssh.SshConnector
import io.github.danielperezmartinez.titanssh.ssh.SshCredentials
import io.github.danielperezmartinez.titanssh.ssh.SshEndpoint
import io.github.danielperezmartinez.titanssh.ssh.SshHop
import io.github.danielperezmartinez.titanssh.ssh.SshHostKeyRejected

/**
 * What the agent panel and the launcher need to reach an agent without a tab:
 * the destination user's [endpoint], how to authenticate, and the ProxyJump
 * [jumps] to go through (first hop first).
 */
data class AgentHost(
    val endpoint: SshEndpoint,
    val auth: HostAuth,
    val keepAliveSeconds: Int,
    val jumps: List<Host>,
) {
    val key: AgentKey get() = AgentKey.of(endpoint)

    companion object {
        /** The agent of [resolved]'s destination user. */
        fun of(resolved: ResolvedConnection) =
            AgentHost(resolved.endpoint, resolved.auth, resolved.host.keepAliveSeconds, resolved.jumps)

        /**
         * The agent of [host]'s default user in [config]; null when its
         * ProxyJump chain cannot be resolved, since it is never skipped.
         */
        fun of(host: Host, config: TitanConfig): AgentHost? {
            val jumps = runCatching { config.jumpChain(host) }.getOrNull() ?: return null
            return AgentHost(SshEndpoint(host.hostname, host.port, host.username), host.auth, host.keepAliveSeconds, jumps)
        }
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
            var rejection: HostKeyRejection? = null
            // Which host refused its key: a jump host's name, or null for the destination.
            var refusedJump: String? = null
            fun verifier(jump: Host?) = KnownHostsVerifier(
                store = knownHostsStore,
                acceptNewHosts = false,
                onRejected = {
                    rejection = it
                    refusedJump = jump?.let { j -> j.alias.ifBlank { j.hostname } }
                },
                prompt = HostTrustPrompt { false },
            )
            val creds = mutableListOf<SshCredentials>()
            val session = try {
                host.jumps.forEach { creds += credentialResolver.resolve(it.auth) }
                creds += credentialResolver.resolve(host.auth)
                val via = host.jumps.mapIndexed { i, jump ->
                    SshHop(jump.jumpEndpoint, creds[i], verifier(jump), jump.keepAliveSeconds)
                }
                connector.connect(host.endpoint, creds.last(), verifier(null), host.keepAliveSeconds, via)
            } catch (e: SshHostKeyRejected) {
                // A session with this host also shows its jump hosts' keys.
                val which = refusedJump?.let { "del bastión $it" } ?: "de este host"
                throw AgentControlException(
                    if (rejection is HostKeyRejection.Changed) {
                        "La clave $which ha cambiado: abre una sesión con este host para revisarla"
                    } else {
                        "La clave $which no es de confianza todavía: abre una sesión con este host para confirmarla"
                    },
                    e,
                )
            } finally {
                creds.forEach { it.wipe() }
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
