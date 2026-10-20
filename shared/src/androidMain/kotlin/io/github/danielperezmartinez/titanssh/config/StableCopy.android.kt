package io.github.danielperezmartinez.titanssh.config

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import io.github.danielperezmartinez.titanssh.AppChannel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android: the canary asks the stable app's [StableConfigProvider] for its
 * `config.json` and `known_hosts`. The secrets stay behind: they are sealed by
 * a Keystore key bound to the stable app (ADR-0001), so the copied hosts need
 * their credentials again (ADR-0027 §4).
 */
actual fun createStableCopier(): StableCopier? {
    if (!AppChannel.isCanary) return null
    val context = AndroidConfigContext.require()
    return FileStableCopier(
        configDir = File(context.filesDir, CONFIG_DIR),
        knownHosts = File(context.filesDir, "known_hosts"),
        readStable = { readStableSnapshot(context) },
        secrets = null,
    )
}

private suspend fun readStableSnapshot(context: Context): StableSnapshot? = withContext(Dispatchers.IO) {
    val authority = stableAuthority(context) ?: return@withContext null
    // A missing app or provider answers with null or IllegalArgumentException.
    val bundle = runCatching {
        context.contentResolver.call(Uri.parse("content://$authority"), StableConfigProvider.METHOD_SNAPSHOT, null, null)
    }.getOrNull() ?: return@withContext null
    val config = bundle.getByteArray(StableConfigProvider.KEY_CONFIG) ?: return@withContext null
    StableSnapshot(config, bundle.getByteArray(StableConfigProvider.KEY_KNOWN_HOSTS))
}

/** The stable app's provider authority, which the build writes into the manifest. */
private fun stableAuthority(context: Context): String? =
    runCatching {
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData?.getString(StableConfigProvider.META_STABLE_AUTHORITY)
    }.getOrNull()

/**
 * Exported by the stable app only, behind a `signature` permission: it hands
 * its `config.json` and `known_hosts` (no secrets, which the document only
 * references) to the canary, an app signed with the same key. Read-only: it
 * has no way to change anything.
 */
class StableConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_SNAPSHOT) return null
        val context = context ?: return null
        // call() is not covered by the provider's android:permission.
        if (context.checkCallingPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("$PERMISSION required")
        }
        val config = File(File(context.filesDir, CONFIG_DIR), JsonFileConfigStore.CONFIG_FILE)
        if (!config.isFile) return null
        return Bundle().apply {
            putByteArray(KEY_CONFIG, config.readBytes())
            File(context.filesDir, "known_hosts").takeIf { it.isFile }?.let { putByteArray(KEY_KNOWN_HOSTS, it.readBytes()) }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val PERMISSION = "io.github.danielperezmartinez.titanssh.permission.READ_STABLE_CONFIG"
        const val METHOD_SNAPSHOT = "snapshot"
        const val KEY_CONFIG = "config"
        const val KEY_KNOWN_HOSTS = "known_hosts"
        const val META_STABLE_AUTHORITY = "io.github.danielperezmartinez.titanssh.STABLE_CONFIG_AUTHORITY"
    }
}
