package io.github.danielperezmartinez.titanssh.config

import io.github.danielperezmartinez.titanssh.AppChannel
import io.github.danielperezmartinez.titanssh.secret.createSecretStore
import io.github.danielperezmartinez.titanssh.secret.stableSecretStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop: the canary reads the stable app's folder and secret namespace
 * directly. The OS secret store protects per user, not per app, so the canary
 * could read them anyway and copying the secrets costs no security (ADR-0027 §4).
 */
actual fun createStableCopier(): StableCopier? {
    if (!AppChannel.isCanary) return null
    val own = desktopConfigDirectory()
    return FileStableCopier(
        configDir = own,
        knownHosts = File(own, "known_hosts"),
        readStable = {
            withContext(Dispatchers.IO) {
                val stable = stableConfigDirectory()
                val config = File(stable, JsonFileConfigStore.CONFIG_FILE).takeIf { it.isFile }?.readBytes()
                config?.let { StableSnapshot(it, File(stable, "known_hosts").takeIf { f -> f.isFile }?.readBytes()) }
            }
        },
        secrets = stableSecretStore() to createSecretStore(),
    )
}
