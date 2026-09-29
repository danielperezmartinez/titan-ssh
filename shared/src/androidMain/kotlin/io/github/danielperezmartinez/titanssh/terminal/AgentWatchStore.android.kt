package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.AndroidConfigContext
import io.github.danielperezmartinez.titanssh.config.CONFIG_DIR
import java.io.File

actual fun createAgentWatchStore(): AgentWatchStore =
    JsonFileAgentWatchStore(File(File(AndroidConfigContext.require().filesDir, CONFIG_DIR), JsonFileAgentWatchStore.FILE_NAME))
