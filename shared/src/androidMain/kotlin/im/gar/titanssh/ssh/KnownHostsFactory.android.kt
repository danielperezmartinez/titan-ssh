package im.gar.titanssh.ssh

import im.gar.titanssh.config.AndroidConfigContext
import java.io.File

/**
 * Android [KnownHostsStore]: a `known_hosts` file in the app-private `filesDir`,
 * reusing the application context the entrypoint already installs for the config
 * store (see [AndroidConfigContext]).
 */
actual fun createKnownHostsStore(): KnownHostsStore =
    FileKnownHostsStore(File(AndroidConfigContext.require().filesDir, "known_hosts"))
