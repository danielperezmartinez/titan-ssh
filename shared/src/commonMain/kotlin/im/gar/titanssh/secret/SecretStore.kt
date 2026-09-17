package im.gar.titanssh.secret

/**
 * Minimal seam that custodies secret material in the native secret store of the
 * running platform (Android KeyStore, Windows Credential Store, Linux Secret
 * Service). See ADR-0001.
 *
 * ## Contract
 * - Implementations **never** persist secret material in plaintext. When no
 *   OS-backed store is available they fail closed with [SecretStoreUnavailable]
 *   instead of falling back to an insecure location.
 * - Callers own the plaintext [ByteArray] they pass in and get back; the store
 *   does not retain a reference to it. Callers should zero it out with
 *   [wipe] once done, because it is the copy that lives in the JVM/ART heap.
 * - Operations are idempotent where it makes sense: [put] overwrites, [remove]
 *   on a missing ref is a no-op.
 *
 * ## What belongs here
 * Passwords, key passphrases and software-fallback private keys (see ADR-0005).
 * A hardware-backed, non-exportable SSH key is **not** stored here: it lives in
 * the OS key store and never leaves the hardware; only its alias is referenced.
 * That signer is built in a follow-up task.
 *
 * Construct the platform implementation with [createSecretStore].
 */
interface SecretStore {
    /** Stores [secret] under [ref], overwriting any previous value. */
    suspend fun put(ref: SecretRef, secret: ByteArray)

    /** Returns the secret stored under [ref], or `null` if there is none. */
    suspend fun get(ref: SecretRef): ByteArray?

    /** Deletes the secret under [ref]. No-op if it does not exist. */
    suspend fun remove(ref: SecretRef)

    /** Whether a secret is currently stored under [ref]. */
    suspend fun contains(ref: SecretRef): Boolean
}

/** Base type for every failure surfaced by a [SecretStore]. */
sealed class SecretStoreException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * The platform has no usable OS-backed secret store (e.g. a headless Linux box
 * with no Secret Service). The store fails closed rather than persisting
 * secrets insecurely.
 */
class SecretStoreUnavailable(message: String, cause: Throwable? = null) :
    SecretStoreException(message, cause)

/** A store operation failed for an unexpected, platform-specific reason. */
class SecretStoreOperationFailed(message: String, cause: Throwable? = null) :
    SecretStoreException(message, cause)

/**
 * Best-effort zeroing of a plaintext buffer once it is no longer needed, to
 * shorten how long secret material lingers on the heap. Not a security
 * guarantee (the JVM/ART may have copied it), but cheap defense in depth.
 */
fun ByteArray.wipe() {
    for (i in indices) this[i] = 0
}
