package io.github.danielperezmartinez.titanssh.config

import io.github.danielperezmartinez.titanssh.secret.SecretRef
import io.github.danielperezmartinez.titanssh.secret.SecretStore
import io.github.danielperezmartinez.titanssh.secret.wipe
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What reading the stable app gives: its files, or why there are none. */
sealed interface StableRead

/** The stable app's `config.json` and `known_hosts` (null if it has none), as read. */
class StableSnapshot(val config: ByteArray, val knownHosts: ByteArray?) : StableRead

/** Nothing to copy, for [reason]: the stable app is missing, too old or has no configuration yet. */
class StableUnavailable(val reason: String) : StableRead

internal const val NO_STABLE_CONFIG = "la app estable aún no tiene configuración"

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
    private val readStable: suspend () -> StableRead,
    private val secrets: Pair<SecretStore, SecretStore>?,
) : StableCopier {

    override val copiesSecrets: Boolean get() = secrets != null

    override fun hasOwnConfig(): Boolean = File(configDir, JsonFileConfigStore.CONFIG_FILE).exists()

    override suspend fun copy(): StableCopyResult {
        val snapshot = when (val read = runCatching { readStable() }.getOrElse {
            return StableCopyResult.NotCopied("no se pudo leer la app estable: ${it.message ?: it::class.simpleName}")
        }) {
            is StableSnapshot -> read
            is StableUnavailable -> return StableCopyResult.NotCopied(read.reason)
        }
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
