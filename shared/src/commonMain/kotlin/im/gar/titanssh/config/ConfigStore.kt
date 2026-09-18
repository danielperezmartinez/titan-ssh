package im.gar.titanssh.config

/**
 * Persists the non-secret configuration ([TitanConfig]) of the "Configuración"
 * area. Backed by a JSON document on an app-private file (both platforms are
 * JVM, so the implementation is shared in `jvmShared`); only the base directory
 * is platform-specific.
 *
 * Secrets are **not** persisted here: passwords, passphrases and software keys
 * live in the [SecretStore][im.gar.titanssh.secret.SecretStore] and are
 * referenced by name; a hardware key by its OS-key-store alias (ADR-0001).
 *
 * Construct the platform implementation with [createConfigStore].
 */
interface ConfigStore {
    /** Loads the persisted config, or an empty [TitanConfig] if none exists yet. */
    suspend fun load(): TitanConfig

    /** Persists [config], replacing the previous document atomically. */
    suspend fun save(config: TitanConfig)
}

/**
 * Builds the [ConfigStore] for the running platform. The `actual`s pick the
 * app-private directory (Android `filesDir`, desktop the OS config dir) and back
 * it with the shared JSON file store.
 */
expect fun createConfigStore(): ConfigStore
