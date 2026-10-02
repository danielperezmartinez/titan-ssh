package io.github.danielperezmartinez.titanssh.config

import io.github.danielperezmartinez.titanssh.secret.AuthMethod
import io.github.danielperezmartinez.titanssh.secret.KeyStorage
import io.github.danielperezmartinez.titanssh.secret.SecretRef
import io.github.danielperezmartinez.titanssh.ssh.SshEndpoint

/**
 * A session resolved against its host into what the SSH engine consumes: the
 * endpoint, the (still reference-only) auth, the effective appearance and the
 * ProxyJump [jumps]. Materializing the actual [SshCredentials] from the
 * SecretStore happens at connect time (the terminal task), not here.
 */
data class ResolvedConnection(
    val session: Session,
    val host: Host,
    val endpoint: SshEndpoint,
    val auth: HostAuth,
    val appearance: TerminalAppearance,
    /** The jump hosts to go through, first hop first (see [jumpChain]); empty when direct. */
    val jumps: List<Host>,
)

/** Raised when a session references a host that no longer exists. */
class ConfigResolutionException(message: String) : Exception(message)

/**
 * Resolves [session] against the hosts in this config, applying session
 * overrides over host defaults. The resolved session carries its
 * [effectiveScripts], so what runs is the library's current content. Throws
 * [ConfigResolutionException] if the referenced host is missing, and
 * propagates [SshEndpoint]'s own validation.
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
    val effective = session.copy(scripts = effectiveScripts(session))
    return ResolvedConnection(effective, host, endpoint, host.auth, appearance, jumpChain(host))
}

/**
 * The jump hosts to reach [host] through, first hop first: its ProxyJump host,
 * preceded by that host's own ProxyJump, and so on. Throws
 * [ConfigResolutionException] when a jump host is missing or the chain loops,
 * so a configured jump is never skipped.
 */
fun TitanConfig.jumpChain(host: Host): List<Host> {
    val chain = ArrayDeque<Host>()
    val seen = mutableSetOf(host.id)
    var next = host.proxyJumpHostId
    while (next != null) {
        val jump = hosts.firstOrNull { it.id == next }
            ?: throw ConfigResolutionException("Host '${host.label}' jumps through a host that no longer exists")
        if (!seen.add(jump.id)) {
            throw ConfigResolutionException("The ProxyJump of host '${host.label}' loops through '${jump.label}'")
        }
        chain.addFirst(jump)
        next = jump.proxyJumpHostId
    }
    return chain.toList()
}

/**
 * Whether [candidate] can be the ProxyJump of host [hostId] (null for a host
 * not saved yet): it is not that host and does not jump through it.
 */
fun TitanConfig.canJumpThrough(hostId: String?, candidate: Host): Boolean {
    val seen = mutableSetOf<String>()
    var next: Host? = candidate
    while (next != null && seen.add(next.id)) {
        if (next.id == hostId) return false
        next = next.proxyJumpHostId?.let { id -> hosts.firstOrNull { it.id == id } }
    }
    return true
}

/** Where to connect to [Host] as a jump host: its own address, port and user. */
val Host.jumpEndpoint: SshEndpoint get() = SshEndpoint(hostname, port, username)

private val Host.label: String get() = alias.ifBlank { hostname }

/**
 * The scripts of [session] as they run (ADR-0013): each library reference is
 * filled with the current content of its [LibraryScript], keeping the
 * reference's own id, enabled flag, phase and reconnect behavior. A reference
 * whose library entry is gone is dropped, so it never runs an empty command.
 */
fun TitanConfig.effectiveScripts(session: Session): List<SessionScript> =
    session.scripts.mapNotNull { script ->
        val libraryId = script.libraryScriptId ?: return@mapNotNull script
        scripts.firstOrNull { it.id == libraryId }?.let { script.filledFrom(it) }
    }

/** This reference with the content of [library]; still linked to it. */
fun SessionScript.filledFrom(library: LibraryScript): SessionScript = copy(
    label = library.name,
    body = library.body,
    behavior = library.behavior,
    envVars = library.envVars,
    secretRefs = library.secretRefs,
)

/** A new reference to this library script, used by a session in [phase]. */
fun LibraryScript.reference(id: String, phase: ScriptPhase): SessionScript =
    SessionScript(id = id, label = name, phase = phase, libraryScriptId = this.id)

/** The sessions whose scripts reference the library script [libraryScriptId]. */
fun TitanConfig.sessionsUsing(libraryScriptId: String): List<Session> =
    sessions.filter { s -> s.scripts.any { it.libraryScriptId == libraryScriptId } }

/**
 * What the scripts menu of an open [session] offers: its own enabled
 * [ScriptPhase.ON_DEMAND] scripts first, then every library script it does not
 * already list there. Library scripts are offered as on-demand references, so
 * they run with the library's content.
 */
fun TitanConfig.onDemandScripts(session: Session): List<SessionScript> {
    val own = effectiveScripts(session).filter { it.enabled && it.phase == ScriptPhase.ON_DEMAND }
    val listed = own.mapNotNull { it.libraryScriptId }.toSet()
    val library = scripts
        .filterNot { it.id in listed }
        .map { it.reference(id = "library:${it.id}", phase = ScriptPhase.ON_DEMAND).filledFrom(it) }
    return own + library
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
