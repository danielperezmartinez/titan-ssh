package im.gar.titanssh.config

import android.content.Context
import java.io.File

/**
 * Holds the application [Context] the Android [ConfigStore] needs to locate its
 * app-private directory. The Android entrypoint calls [init] once on startup,
 * before [createConfigStore] runs. Mirrors the SecretStore's context holder;
 * commonMain cannot pass a [Context] through the `expect` seam.
 */
object AndroidConfigContext {
    @Volatile
    private var appContext: Context? = null

    /** Records the application context. Safe to call more than once. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    internal fun require(): Context =
        appContext
            ?: error(
                "AndroidConfigContext.init(context) must be called before createConfigStore()",
            )
}

actual fun createConfigStore(): ConfigStore =
    JsonFileConfigStore(File(AndroidConfigContext.require().filesDir, CONFIG_DIR))

private const val CONFIG_DIR = "titan-config"
