package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.desktopConfigDirectory
import java.io.File

actual fun createAgentWatchStore(): AgentWatchStore =
    JsonFileAgentWatchStore(File(desktopConfigDirectory(), JsonFileAgentWatchStore.FILE_NAME))
