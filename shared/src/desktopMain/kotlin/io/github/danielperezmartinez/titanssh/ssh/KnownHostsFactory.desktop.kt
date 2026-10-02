package io.github.danielperezmartinez.titanssh.ssh

import io.github.danielperezmartinez.titanssh.config.desktopConfigDirectory
import java.io.File

/**
 * Desktop [KnownHostsStore]: a `known_hosts` file under the same per-user config
 * directory the config store uses (Windows `%APPDATA%`, otherwise
 * `$XDG_CONFIG_HOME` or `~/.config`), in the `titan-ssh` subfolder.
 */
actual fun createKnownHostsStore(): KnownHostsStore =
    FileKnownHostsStore(File(desktopConfigDirectory(), "known_hosts"))
