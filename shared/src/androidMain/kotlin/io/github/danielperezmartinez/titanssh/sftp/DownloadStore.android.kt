package io.github.danielperezmartinez.titanssh.sftp

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import io.github.danielperezmartinez.titanssh.config.AndroidConfigContext
import java.io.File
import java.io.OutputStream

/**
 * Android 10+ saves to the shared Downloads collection through MediaStore, which
 * needs no permission (ADR-0021). Android 8–9 lack that collection and would
 * need the storage permission, so there the files go to the app's own
 * Downloads folder (`Android/data/<app>/files/Download`).
 */
actual fun createDownloadStore(): DownloadStore {
    val context = AndroidConfigContext.require()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStoreDownloadStore(context)
    } else {
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, Environment.DIRECTORY_DOWNLOADS)
        // Sharing an app-private file needs a FileProvider; not worth it for Android 8–9.
        FileDownloadStore(directory) { false }
    }
}

private class MediaStoreDownloadStore(private val context: Context) : DownloadStore {
    override fun create(fileName: String): DownloadTarget = insert(Environment.DIRECTORY_DOWNLOADS, fileName)

    /**
     * A folder of Downloads (ADR-0024). MediaStore files carry their folder in
     * RELATIVE_PATH and it creates the folders itself; the taken names are
     * looked up on the shared storage, which this app can read but not list
     * whole, so a folder of another app may go unseen and be shared (its files
     * are never overwritten: MediaStore numbers a taken file name).
     */
    override fun createFolder(name: String): DownloadFolder {
        val downloads = publicDownloads()
        var n = 0
        while (true) {
            val candidate = if (n == 0) name else "$name ($n)"
            if (!File(downloads, candidate).exists()) {
                runCatching { File(downloads, candidate).mkdirs() }
                return MediaStoreDownloadFolder(candidate)
            }
            n++
        }
    }

    private inner class MediaStoreDownloadFolder(override val name: String) : DownloadFolder {
        override val location: String get() = "${Environment.DIRECTORY_DOWNLOADS}/$name"

        override fun create(subfolder: String, fileName: String): DownloadTarget =
            insert(relativePath(subfolder), fileName)

        // MediaStore has no empty folders; the shared storage takes them from Android 11 on.
        override fun makeFolder(subfolder: String) {
            runCatching { File(publicDownloads(), "$name/$subfolder").mkdirs() }
        }

        override fun discard() {
            runCatching { removeEmptyFolders(File(publicDownloads(), name)) }
        }

        override fun open(): Boolean = false

        private fun relativePath(subfolder: String) =
            listOf(Environment.DIRECTORY_DOWNLOADS, name, subfolder).filter { it.isNotEmpty() }.joinToString("/")
    }

    @Suppress("DEPRECATION") // Only to tell whether a folder name is taken.
    private fun publicDownloads(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /** A new MediaStore file [fileName] in [relativePath] (`Download/…`). */
    private fun insert(relativePath: String, fileName: String): DownloadTarget {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeOf(fileName))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            // Hidden from other apps until it is complete.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("no se pudo crear el archivo en Descargas")
        val out = runCatching { resolver.openOutputStream(uri, "w") }.getOrNull()
        if (out == null) {
            resolver.delete(uri, null, null)
            error("no se pudo escribir en Descargas")
        }
        return MediaStoreDownloadTarget(context, uri, out, displayName(context, uri) ?: fileName, relativePath)
    }
}

/** The name MediaStore gave [uri]: it numbers a taken one ("name (1).ext") itself. */
private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
}.getOrNull()

private class MediaStoreDownloadTarget(
    private val context: Context,
    private val uri: Uri,
    private val out: OutputStream,
    name: String,
    private val relativePath: String,
) : DownloadTarget {
    override var name: String = name
        private set

    override val location: String get() = "$relativePath/$name"

    override fun write(bytes: ByteArray, length: Int) = out.write(bytes, 0, length)

    override fun complete() {
        out.close()
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
        // A pending file can keep the name asked for and only be numbered on publishing.
        displayName(context, uri)?.let { name = it }
    }

    override fun discard() {
        runCatching { out.close() }
        context.contentResolver.delete(uri, null, null)
    }

    override fun open(): Boolean = viewIntent(context, uri, mimeTypeOf(name))
}

private fun viewIntent(context: Context, uri: Uri, mimeType: String): Boolean = runCatching {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess

private fun mimeTypeOf(fileName: String): String {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
}
