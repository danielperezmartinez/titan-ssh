package io.github.danielperezmartinez.titanssh.config

import io.github.danielperezmartinez.titanssh.secret.AuthMethod
import io.github.danielperezmartinez.titanssh.secret.KeyStorage
import io.github.danielperezmartinez.titanssh.secret.SecretRef
import io.github.danielperezmartinez.titanssh.ssh.SshEndpoint

/**
 * A session resolved against its host into what the SSH engine consumes: the
 * endpoint, the (still reference-only) auth, the effective appearance and the
 * optional ProxyJump host. Materializing the actual [SshCredentials] from the
 * SecretStore happens at connect time (the terminal task), not here.
 */
data class ResolvedConnection(
    val session: Session,
    val host: Host,
    val endpoint: SshEndpoint,
    val auth: HostAuth,
    val appearance: TerminalAppearance,
    val proxyJump: Host?,
)

/** Raised when a session references a host that no longer exists. */
class ConfigResolutionException(message: String) : Exception(message)

/**
 * Resolves [session] against the hosts in this config, applying session
 * overrides over host defaults. Throws [ConfigResolutionException] if the
 * referenced host is missing, and propagates [SshEndpoint]'s own validation.
 */
fun TitanConfig.resolve(session: Session): ResolvedConnection {
    val host = hosts.firstOrNull { it.id == session.hostId }
        ?: throw ConfigResolutionException(
            "Session '${session.name}' references unknown host '${session.hostId}'",
        )
    val endpoint = SshEndpoint(
        host = host.hostname,
        port = session.portOverride ?: host.port,
        username = session.usernameOverride ?: host.username,
    )
    val appearance = defaultAppearance
        .mergedWith(host.appearance)
        .mergedWith(session.appearance)
    val proxyJump = host.proxyJumpHostId?.let { id -> hosts.firstOrNull { it.id == id } }
    return ResolvedConnection(session, host, endpoint, host.auth, appearance, proxyJump)
}

/** Overlays non-null fields of [override] on top of this appearance. */
fun TerminalAppearance.mergedWith(override: TerminalAppearance?): TerminalAppearance {
    if (override == null) return this
    return TerminalAppearance(
        fontFamily = override.fontFamily ?: fontFamily,
        fontSize = override.fontSize ?: fontSize,
        colorTheme = override.colorTheme ?: colorTheme,
    )
}

/**
 * Maps config auth to the runtime [AuthMethod] domain model (which already
 * encodes the ADR-0005 preference ordering via `byPreference()`). The secret
 * material itself is fetched from the SecretStore at connect time.
 */
fun HostAuth.toAuthMethod(): AuthMethod = when (this) {
    is HostAuth.Password -> AuthMethod.Password(SecretRef(secretRef))
    is HostAuth.SoftwareKey -> AuthMethod.PublicKey(
        keyType = keyType,
        storage = KeyStorage.SOFTWARE_FALLBACK,
        secretRef = SecretRef(secretRef),
    )
    is HostAuth.HardwareKey -> AuthMethod.PublicKey(
        keyType = keyType,
        storage = KeyStorage.HARDWARE_NON_EXPORTABLE,
        secretRef = null,
    )
}

/** Ordered scripts of a session for a given [phase], enabled ones first excluded. */
fun Session.scriptsFor(phase: ScriptPhase): List<SessionScript> =
    scripts.filter { it.enabled && it.phase == phase }

/**
 * The session's effective behavior when the client reconnects after a micro-cut
 * (ADR-0003 / [[Resiliencia de sesión ante microcortes de red]]). It is carried
 * by the enabled [ScriptPhase.ON_RECONNECT] scripts; when the session declares
 * none, the baseline is [ReconnectBehavior.RESTORE_CD_ONLY] — restoring the
 * working directory is the minimum win the product promises over Termius.
 */
fun Session.effectiveReconnectBehavior(): ReconnectBehavior =
    scriptsFor(ScriptPhase.ON_RECONNECT).firstOrNull()?.reconnectBehavior
        ?: ReconnectBehavior.RESTORE_CD_ONLY
