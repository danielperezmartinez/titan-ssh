package io.github.danielperezmartinez.titanssh.ssh

/**
 * TOFU host-key verification (ADR-0005). A [KnownHostsVerifier] plugs into the
 * engine's injectable [HostKeyVerifier] and decides trust against a persisted
 * store of previously accepted host keys:
 *
 * - **First contact** (no stored key for this host+type): ask the user via
 *   [HostTrustPrompt]; if accepted, persist the key and proceed.
 * - **Known and matching**: proceed silently.
 * - **Known but different key** (same host+type, different key): reject as a
 *   possible MITM — never prompt to silently overwrite a trusted key.
 */

/** One trusted host key, in OpenSSH `known_hosts` terms. */
data class KnownHostEntry(
    val host: String,
    val port: Int,
    val keyType: String,
    val publicKeyBase64: String,
)

/** Persistence for accepted host keys. Implementations must be safe to call concurrently. */
interface KnownHostsStore {
    /** All stored entries for [host]:[port] (possibly several key types). */
    suspend fun entriesFor(host: String, port: Int): List<KnownHostEntry>

    /** Persists a newly trusted [entry]. */
    suspend fun add(entry: KnownHostEntry)
}

/** Asks the user whether to trust a host seen for the first time. */
fun interface HostTrustPrompt {
    /** Returns `true` to trust [info] on first contact and remember it. */
    suspend fun confirmNewHost(info: HostKeyInfo): Boolean
}

/**
 * [HostKeyVerifier] implementing TOFU over a [KnownHostsStore] and a
 * [HostTrustPrompt].
 */
class KnownHostsVerifier(
    private val store: KnownHostsStore,
    private val prompt: HostTrustPrompt,
) : HostKeyVerifier {

    override suspend fun verify(info: HostKeyInfo): Boolean {
        val sameType = store.entriesFor(info.host, info.port)
            .filter { it.keyType == info.keyType }

        if (sameType.isNotEmpty()) {
            // Known host+type: accept only an exact key match.
            return sameType.any { it.publicKeyBase64 == info.publicKeyBase64 }
        }

        // First contact for this host+type: defer to the user, then remember.
        if (!prompt.confirmNewHost(info)) return false
        store.add(
            KnownHostEntry(
                host = info.host,
                port = info.port,
                keyType = info.keyType,
                publicKeyBase64 = info.publicKeyBase64,
            ),
        )
        return true
    }
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
}
