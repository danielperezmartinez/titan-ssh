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
import java.io.FileOutputStream
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
        AppFolderDownloadStore(context)
    }
}

private class MediaStoreDownloadStore(private val context: Context) : DownloadStore {
    override fun create(fileName: String): DownloadTarget {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeOf(fileName))
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
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
        // MediaStore renames a taken name to "name (1).ext" itself.
        val actual = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: fileName
        return MediaStoreDownloadTarget(context, uri, out, actual)
    }
}

private class MediaStoreDownloadTarget(
    private val context: Context,
    private val uri: Uri,
    private val out: OutputStream,
    override val name: String,
) : DownloadTarget {
    override val location: String get() = "${Environment.DIRECTORY_DOWNLOADS}/$name"

    override fun write(bytes: ByteArray, length: Int) = out.write(bytes, 0, length)

    override fun complete() {
        out.close()
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }

    override fun discard() {
        runCatching { out.close() }
        context.contentResolver.delete(uri, null, null)
    }

    override fun open(): Boolean = viewIntent(context, uri, mimeTypeOf(name))
}

private class AppFolderDownloadStore(private val context: Context) : DownloadStore {
    override fun create(fileName: String): DownloadTarget {
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, Environment.DIRECTORY_DOWNLOADS)
        directory.mkdirs()
        var n = 0
        while (true) {
            val candidate = File(directory, if (n == 0) fileName else numberedFileName(fileName, n))
            if (candidate.createNewFile()) return AppFolderDownloadTarget(candidate)
            n++
        }
    }
}

private class AppFolderDownloadTarget(private val file: File) : DownloadTarget {
    private val out = FileOutputStream(file)

    override val name: String get() = file.name
    override val location: String get() = file.path

    override fun write(bytes: ByteArray, length: Int) = out.write(bytes, 0, length)

    override fun complete() = out.close()

    override fun discard() {
        runCatching { out.close() }
        file.delete()
    }

    /** Sharing an app-private file needs a FileProvider; not worth it for Android 8–9. */
    override fun open(): Boolean = false
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
