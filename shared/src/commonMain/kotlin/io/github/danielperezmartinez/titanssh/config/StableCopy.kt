package io.github.danielperezmartinez.titanssh.config

/**
 * Copies the stable app's configuration into the canary app (ADR-0027 §4):
 * always from the stable app to the canary, never the other way, and without
 * ever writing to the stable app's data. Null in the stable app.
 */
expect fun createStableCopier(): StableCopier?

interface StableCopier {
    /**
     * True on the desktop, where the secrets come along. On Android they stay
     * in the stable app's Keystore, so the copied hosts need their credentials
     * again.
     */
    val copiesSecrets: Boolean

    /** False until the canary has a configuration of its own: its first start. */
    fun hasOwnConfig(): Boolean

    /** Replaces the canary's configuration and `known_hosts` with the stable app's. */
    suspend fun copy(): StableCopyResult
}

sealed interface StableCopyResult {
    /** [secrets] copied; [missingSecrets] referenced but not found in the stable app. */
    data class Copied(val hosts: Int, val sessions: Int, val secrets: Int, val missingSecrets: Int) : StableCopyResult

    /** Nothing was copied, for [reason]; the canary's configuration is unchanged. */
    data class NotCopied(val reason: String) : StableCopyResult
}

/** Every SecretStore ref the config points at: host credentials and script secrets. */
fun TitanConfig.secretRefs(): Set<String> = buildSet {
    hosts.forEach { host ->
        when (val auth = host.auth) {
            is HostAuth.Password -> add(auth.secretRef)
            is HostAuth.SoftwareKey -> {
                add(auth.secretRef)
                auth.passphraseRef?.let(::add)
            }
            is HostAuth.HardwareKey -> Unit
        }
    }
    sessions.forEach { session -> session.scripts.forEach { addAll(it.secretRefs) } }
    scripts.forEach { addAll(it.secretRefs) }
}
