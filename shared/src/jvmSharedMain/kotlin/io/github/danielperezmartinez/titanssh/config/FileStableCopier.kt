package io.github.danielperezmartinez.titanssh.config

import io.github.danielperezmartinez.titanssh.secret.SecretRef
import io.github.danielperezmartinez.titanssh.secret.SecretStore
import io.github.danielperezmartinez.titanssh.secret.wipe
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The stable app's `config.json` and `known_hosts` (null if it has none), as read. */
class StableSnapshot(val config: ByteArray, val knownHosts: ByteArray?)

/**
 * [StableCopier] over the canary's own files: [configDir] (where
 * [JsonFileConfigStore] keeps `config.json`) and [knownHosts]. [readStable]
 * fetches the stable app's files, a plain read on the desktop and a call to its
 * provider on Android. With [secrets] (stable store to canary store) every
 * secret the copied config refers to is copied too.
 */
internal class FileStableCopier(
    private val configDir: File,
    private val knownHosts: File,
    private val readStable: suspend () -> StableSnapshot?,
    private val secrets: Pair<SecretStore, SecretStore>?,
) : StableCopier {

    override val copiesSecrets: Boolean get() = secrets != null

    override fun hasOwnConfig(): Boolean = File(configDir, JsonFileConfigStore.CONFIG_FILE).exists()

    override suspend fun copy(): StableCopyResult {
        val snapshot = runCatching { readStable() }.getOrElse {
            return StableCopyResult.NotCopied("no se pudo leer la app estable: ${it.message ?: it::class.simpleName}")
        } ?: return StableCopyResult.NotCopied("la app estable no está instalada o aún no tiene configuración")
        // Parsed (and migrated) before anything is replaced: a document this
        // canary cannot read leaves its own configuration untouched.
        val staging = withContext(Dispatchers.IO) {
            File(configDir, "stable-copy").apply {
                deleteRecursively()
                mkdirs()
                PrivateFiles.writeText(File(this, JsonFileConfigStore.CONFIG_FILE), snapshot.config.decodeToString())
            }
        }
        val config = try {
            JsonFileConfigStore(staging).load()
        } catch (e: ConfigStoreException) {
            return StableCopyResult.NotCopied("la configuración de la app estable no se puede leer")
        } finally {
            withContext(Dispatchers.IO) { staging.deleteRecursively() }
        }
        JsonFileConfigStore(configDir).save(config)
        withContext(Dispatchers.IO) {
            if (snapshot.knownHosts != null) {
                PrivateFiles.writeText(knownHosts, snapshot.knownHosts.decodeToString())
            } else {
                knownHosts.delete()
            }
        }
        var copied = 0
        var missing = 0
        secrets?.let { (from, to) ->
            for (ref in config.secretRefs()) {
                val secretRef = runCatching { SecretRef(ref) }.getOrNull() ?: continue
                val value = runCatching { from.get(secretRef) }.getOrNull()
                if (value == null) {
                    missing++
                    continue
                }
                try {
                    to.put(secretRef, value)
                    copied++
                } catch (_: Exception) {
                    missing++
                } finally {
                    value.wipe()
                }
            }
        }
        return StableCopyResult.Copied(config.hosts.size, config.sessions.size, copied, missing)
    }
}
