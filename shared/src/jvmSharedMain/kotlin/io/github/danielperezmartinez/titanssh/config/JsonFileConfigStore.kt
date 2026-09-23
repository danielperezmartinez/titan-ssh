package io.github.danielperezmartinez.titanssh.config

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * File-backed [ConfigStore] shared by Android and desktop (both JVM). The config
 * is a single pretty-printed JSON document under [directory]; writes go through
 * a temp file and an atomic rename so a crash mid-write cannot corrupt it.
 *
 * The document holds no secrets — only references to the SecretStore (ADR-0001).
 */
class JsonFileConfigStore(private val directory: File) : ConfigStore {

    private val file: File = File(directory, CONFIG_FILE)

    override suspend fun load(): TitanConfig = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext TitanConfig()
        try {
            JSON.decodeFromString(TitanConfig.serializer(), file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            throw ConfigStoreException("Failed to read config at ${file.path}", e)
        }
    }

    override suspend fun save(config: TitanConfig) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val text = JSON.encodeToString(TitanConfig.serializer(), config)
        val tmp = File(directory, "$CONFIG_FILE.tmp")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                // renameTo can fail if the target exists on some filesystems.
                file.delete()
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    throw ConfigStoreException("Failed to persist config at ${file.path}")
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            if (e is ConfigStoreException) throw e
            throw ConfigStoreException("Failed to persist config at ${file.path}", e)
        }
    }

    companion object {
        const val CONFIG_FILE = "config.json"

        private val JSON = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
            // Sealed HostAuth is written with this discriminator field.
            classDiscriminator = "kind"
        }
    }
}

/** A config persistence operation failed for a platform-specific reason. */
class ConfigStoreException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
