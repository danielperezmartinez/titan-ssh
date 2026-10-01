package io.github.danielperezmartinez.titanssh.ssh

/**
 * TOFU host-key verification (ADR-0005). A [KnownHostsVerifier] plugs into the
 * engine's injectable [HostKeyVerifier] and decides trust against a persisted
 * store of previously accepted host keys. Trust is per host+port, whatever the
 * key type:
 *
 * - **First contact** (nothing stored for this host+port): ask the user via
 *   [HostTrustPrompt]; if accepted, persist the key and proceed. A verifier
 *   that does not accept new hosts (the STRICT policy) refuses instead.
 * - **Known and matching** (this exact key is stored): proceed silently.
 * - **Known, any other key** (of the same type or not): reject as a changed
 *   key — never prompt to silently add or overwrite a trusted key. Replacing a
 *   stored key is a separate, deliberate step ([KnownHostsStore.replace]).
 */

/** One trusted host key, in OpenSSH `known_hosts` terms. */
data class KnownHostEntry(
    val host: String,
    val port: Int,
    val keyType: String,
    val publicKeyBase64: String,
) {
    /** The standard `SHA256:...` fingerprint, for display. */
    val fingerprintSha256: String get() = hostKeyFingerprint(publicKeyBase64)
}

/** Persistence for accepted host keys. Implementations must be safe to call concurrently. */
interface KnownHostsStore {
    /** All stored entries for [host]:[port] (possibly several key types). */
    suspend fun entriesFor(host: String, port: Int): List<KnownHostEntry>

    /** Persists a newly trusted [entry]. */
    suspend fun add(entry: KnownHostEntry)

    /** Drops every key stored for [entry]'s host and port, and stores [entry] in their place. */
    suspend fun replace(entry: KnownHostEntry)
}

/** Asks the user whether to trust a host seen for the first time. */
fun interface HostTrustPrompt {
    /** Returns `true` to trust [info] on first contact and remember it. */
    suspend fun confirmNewHost(info: HostKeyInfo): Boolean
}

/** Why a [KnownHostsVerifier] refused a host key. */
sealed interface HostKeyRejection {
    /** The key the server presented. */
    val presented: HostKeyInfo

    /** Other keys are stored for this host: [presented] is not one of them. */
    data class Changed(override val presented: HostKeyInfo, val stored: List<KnownHostEntry>) : HostKeyRejection

    /** Nothing is stored for this host and the verifier does not accept new hosts. */
    data class NotTrusted(override val presented: HostKeyInfo) : HostKeyRejection

    /** First contact, and the user declined the key. */
    data class Declined(override val presented: HostKeyInfo) : HostKeyRejection
}

/**
 * [HostKeyVerifier] implementing TOFU over a [KnownHostsStore] and a
 * [HostTrustPrompt]. With [acceptNewHosts] false (the STRICT policy) a host is
 * only reached if one of its keys is already stored, and [prompt] is never
 * asked. Every refusal is reported to [onRejected] before the verifier answers.
 */
class KnownHostsVerifier(
    private val store: KnownHostsStore,
    private val acceptNewHosts: Boolean = true,
    private val onRejected: (HostKeyRejection) -> Unit = {},
    private val prompt: HostTrustPrompt,
) : HostKeyVerifier {

    override suspend fun verify(info: HostKeyInfo): Boolean {
        val stored = store.entriesFor(info.host, info.port)
        if (stored.any { it.matches(info) }) return true
        if (stored.isNotEmpty()) return reject(HostKeyRejection.Changed(info, stored))
        if (!acceptNewHosts) return reject(HostKeyRejection.NotTrusted(info))

        // First contact for this host: defer to the user, then remember.
        if (!prompt.confirmNewHost(info)) return reject(HostKeyRejection.Declined(info))
        store.trust(info)
        return true
    }

    override suspend fun knownKeyTypes(host: String, port: Int): List<String> =
        store.entriesFor(host, port).map { it.keyType }.distinct()

    private fun reject(rejection: HostKeyRejection): Boolean {
        onRejected(rejection)
        return false
    }
}

private fun KnownHostEntry.matches(info: HostKeyInfo): Boolean =
    keyType == info.keyType && publicKeyBase64 == info.publicKeyBase64

/** The [KnownHostEntry] that trusts this key. */
fun HostKeyInfo.toKnownHostEntry(): KnownHostEntry =
    KnownHostEntry(host = host, port = port, keyType = keyType, publicKeyBase64 = publicKeyBase64)

/** Remembers [info] as trusted, unless this exact key is already stored. */
suspend fun KnownHostsStore.trust(info: HostKeyInfo) {
    if (entriesFor(info.host, info.port).any { it.matches(info) }) return
    add(info.toKnownHostEntry())
}

/** In-memory [KnownHostsStore] for tests and ephemeral sessions. */
class InMemoryKnownHostsStore(
    initial: List<KnownHostEntry> = emptyList(),
) : KnownHostsStore {
    private val entries = initial.toMutableList()

    override suspend fun entriesFor(host: String, port: Int): List<KnownHostEntry> =
        entries.filter { it.host == host && it.port == port }

    override suspend fun add(entry: KnownHostEntry) {
        entries.add(entry)
    }

    override suspend fun replace(entry: KnownHostEntry) {
        entries.removeAll { it.host == entry.host && it.port == entry.port }
        entries.add(entry)
    }
}
