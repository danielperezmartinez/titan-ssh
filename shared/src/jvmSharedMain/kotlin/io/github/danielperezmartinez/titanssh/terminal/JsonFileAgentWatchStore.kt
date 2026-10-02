package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.PrivateFiles
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * File-backed [AgentWatchStore] shared by Android and desktop: one JSON
 * document, written through a temp file and a rename. It holds what the app
 * last saw of each agent and the sessions still to close; no secrets. A missing
 * or unreadable file loads as empty, as on a first run.
 */
class JsonFileAgentWatchStore(private val file: File) : AgentWatchStore {

    override suspend fun load(): AgentWatchState = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext AgentWatchState()
        runCatching { JSON.decodeFromString(AgentWatchState.serializer(), file.readText(Charsets.UTF_8)) }
            .getOrDefault(AgentWatchState())
    }

    override suspend fun save(state: AgentWatchState) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        PrivateFiles.writeText(tmp, JSON.encodeToString(AgentWatchState.serializer(), state))
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                error("Failed to persist ${file.path}")
            }
        }
    }

    companion object {
        const val FILE_NAME = "agents.json"

        private val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
