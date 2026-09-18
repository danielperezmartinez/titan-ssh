package im.gar.titanssh.config

import im.gar.titanssh.secret.SshKeyType
import kotlinx.serialization.Serializable

/**
 * Configuration domain model for the "Configuración" area (see the visual
 * decision [[Arquitectura de dos áreas Configuración y Sesiones]]).
 *
 * A [Host] describes *where and how to connect* and is reusable; a [Session]
 * describes *what to do on connect* and references a host, optionally overriding
 * some of its defaults. Start scripts and the global snippet library implement
 * the automation task ([[Scripts de inicio por sesión]]).
 *
 * These types are serialized to JSON on an app-private file by the config store.
 * They never hold secret material: passwords, passphrases and software keys are
 * referenced by a [SecretStore][im.gar.titanssh.secret.SecretStore] ref, and a
 * hardware-backed key by its OS-key-store alias (ADR-0001 / ADR-0005). Only the
 * *reference*, never the value, lives here.
 */

/** Host-key trust policy for a host (maps to the known_hosts verifier, ADR-0005). */
enum class HostKeyPolicy {
    /** Trust on first use: accept a new key after confirmation, then pin it. */
    TOFU,

    /** Only connect if the host key is already known; never accept a new one. */
    STRICT,
}

/**
 * Session resilience level against network micro-cuts (ADR-0003). Only the level
 * is configured here; the reconnection engine is built in
 * [[Resiliencia de sesión ante microcortes de red]].
 */
enum class ResilienceLevel {
    /** Client-side reconnect and scrollback restore. The guaranteed minimum. */
    BASE,

    /** Wrap the session in a remote multiplexer (tmux/screen) when available. */
    AUTO_MULTIPLEXER,

    /** titan-ssh's own persistent agent on the destination (full persistence). */
    AGENT,
}

/**
 * How a host authenticates. Config-level counterpart of the runtime
 * [AuthMethod][im.gar.titanssh.secret.AuthMethod]: it carries only references,
 * resolved to live credentials at connect time via the SecretStore.
 */
@Serializable
sealed interface HostAuth {

    /** Password authentication (fallback, ADR-0005). Password held by ref. */
    @Serializable
    data class Password(val secretRef: String) : HostAuth

    /**
     * Software-held private key (fallback): key bytes custodied by the
     * SecretStore under [secretRef], optional passphrase under [passphraseRef].
     */
    @Serializable
    data class SoftwareKey(
        val keyType: SshKeyType = SshKeyType.ED25519,
        val secretRef: String,
        val passphraseRef: String? = null,
    ) : HostAuth

    /**
     * Hardware-backed, non-exportable key (the v1 goal). The private key never
     * leaves the OS key store; only its [alias] is referenced.
     */
    @Serializable
    data class HardwareKey(
        val keyType: SshKeyType = SshKeyType.ECDSA_P256,
        val alias: String,
    ) : HostAuth
}

/**
 * Terminal appearance override. `null` fields inherit from the level below
 * (session → host → global default), so a session need only set what it changes.
 */
@Serializable
data class TerminalAppearance(
    val fontFamily: String? = null,
    val fontSize: Int? = null,
    /** Id of a color theme; `null` uses the default ANSI palette. */
    val colorTheme: String? = null,
)

/** Kind of port forwarding for a [Tunnel]. */
enum class TunnelType {
    /** Local forward: listen locally, forward to a destination via the server. */
    LOCAL,

    /** Remote forward: the server listens and forwards back to us. */
    REMOTE,

    /** Dynamic SOCKS proxy on a local port. */
    DYNAMIC_SOCKS,
}

/** A port-forwarding tunnel attached to a session. */
@Serializable
data class Tunnel(
    val id: String,
    val type: TunnelType,
    val label: String = "",
    val enabled: Boolean = true,
    val listenHost: String = "127.0.0.1",
    val listenPort: Int,
    /** Destination host/port for LOCAL/REMOTE; ignored for DYNAMIC_SOCKS. */
    val destinationHost: String? = null,
    val destinationPort: Int? = null,
)

/** Phase in which a [SessionScript] runs. */
enum class ScriptPhase {
    /** Runs locally before the connection is opened. */
    PRE_CONNECT_LOCAL,

    /** Runs as soon as the remote shell opens. */
    ON_SHELL_START,

    /** Runs once the prompt is ready. */
    POST_INIT,

    /** Runs when the session reconnects after a micro-cut. */
    ON_RECONNECT,

    /** Not run automatically; a manual snippet with a button in the session. */
    ON_DEMAND,
}

/** What to do with the script chain when one script fails. */
enum class ScriptFailurePolicy { CONTINUE, ABORT }

/**
 * How the ON_RECONNECT phase behaves, coherent with the resilience model
 * (ADR-0003 / [[Resiliencia de sesión ante microcortes de red]]).
 */
enum class ReconnectBehavior {
    /** Re-run the whole start chain. */
    RERUN_ALL,

    /** Only restore the working directory (`cd`). */
    RESTORE_CD_ONLY,

    /** Do nothing on reconnect. */
    NONE,
}

/** Fine-grained behavior of a single [SessionScript]. */
@Serializable
data class ScriptBehavior(
    /** Silent (not echoed to the terminal) vs visible. */
    val silent: Boolean = false,
    /** Wait for it to finish (sequential, with [timeoutSeconds]) vs fire-and-forget. */
    val waitForCompletion: Boolean = true,
    val timeoutSeconds: Int? = null,
    val onFailure: ScriptFailurePolicy = ScriptFailurePolicy.CONTINUE,
    /** Optional delay before running. */
    val delaySeconds: Int? = null,
    /** Wait for this pattern before sending (expect "password:"). */
    val expectPattern: String? = null,
)

/**
 * One start script of a session ([[Scripts de inicio por sesión]]). Order is the
 * index within [Session.scripts]; scripts are reordered by moving list items.
 */
@Serializable
data class SessionScript(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    val phase: ScriptPhase = ScriptPhase.ON_SHELL_START,
    /** The command(s) to run; may contain `${'$'}{VAR}` placeholders. */
    val body: String = "",
    /** If set, this script inserts a library [Snippet]; [body] is its cached text. */
    val snippetId: String? = null,
    val behavior: ScriptBehavior = ScriptBehavior(),
    /** Only meaningful when [phase] is [ScriptPhase.ON_RECONNECT]. */
    val reconnectBehavior: ReconnectBehavior = ReconnectBehavior.RERUN_ALL,
    /** Environment variables to export before running. */
    val envVars: Map<String, String> = emptyMap(),
    /** Secrets injected from the SecretStore (never inlined as plaintext). */
    val secretRefs: List<String> = emptyList(),
)

/**
 * A reusable command in the global library, insertable into any session and the
 * source of the "on demand" scripts (see [[Panel de gestión de hosts y sesiones]]).
 */
@Serializable
data class Snippet(
    val id: String,
    val name: String,
    val body: String = "",
    val tags: List<String> = emptyList(),
)

/** A folder for organizing hosts and sessions by project. */
@Serializable
data class Group(
    val id: String,
    val name: String,
    val parentId: String? = null,
)

/**
 * A reusable host: where and how to connect. Referenced by [Session]s, which may
 * override some fields.
 */
@Serializable
data class Host(
    val id: String,
    val alias: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val auth: HostAuth,
    val hostKeyPolicy: HostKeyPolicy = HostKeyPolicy.TOFU,
    val keepAliveSeconds: Int = 30,
    /** Another host used as a ProxyJump (bastion), by id; `null` if direct. */
    val proxyJumpHostId: String? = null,
    val appearance: TerminalAppearance? = null,
    val groupId: String? = null,
    val tags: List<String> = emptyList(),
    /** ASCII marker used as the row icon (visual language). */
    val marker: String = "[+]",
    val colorHex: String? = null,
)

/**
 * A session: what to do on connect. References a [Host] and adds its own
 * scripts, tunnels, working directory, resilience level and appearance, and may
 * override the host's username/port.
 */
@Serializable
data class Session(
    val id: String,
    val name: String,
    val hostId: String,
    val usernameOverride: String? = null,
    val portOverride: Int? = null,
    /** Working directory to `cd` into first; a first-class session field. */
    val initialDirectory: String? = null,
    val resilienceLevel: ResilienceLevel = ResilienceLevel.BASE,
    val scripts: List<SessionScript> = emptyList(),
    val tunnels: List<Tunnel> = emptyList(),
    val appearance: TerminalAppearance? = null,
    val groupId: String? = null,
    val tags: List<String> = emptyList(),
    val marker: String = "[+]",
    val colorHex: String? = null,
)

/**
 * Root of the persisted configuration. One JSON document holds the whole
 * "Configuración" area. [version] lets future migrations detect the shape.
 */
@Serializable
data class TitanConfig(
    val version: Int = CURRENT_VERSION,
    val hosts: List<Host> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val groups: List<Group> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    val defaultAppearance: TerminalAppearance = TerminalAppearance(),
) {
    companion object {
        const val CURRENT_VERSION: Int = 1
    }
}
