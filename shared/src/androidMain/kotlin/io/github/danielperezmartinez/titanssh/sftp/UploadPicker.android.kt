package io.github.danielperezmartinez.titanssh.sftp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import java.io.IOException

@Composable
actual fun rememberUploadPicker(onPicked: (List<UploadSource>) -> Unit): () -> Unit {
    val context = LocalContext.current.applicationContext
    val picked by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) picked(uris.map { ContentUploadSource(context, it) })
    }
    return { launcher.launch(arrayOf("*/*")) }
}

/**
 * A document the picker granted. The grant lasts while the app runs, which is
 * as long as an upload can resume (it lives in its tab).
 */
private class ContentUploadSource(private val context: Context, private val uri: Uri) : UploadSource {
    private val info: Pair<String?, Long?> by lazy {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (!it.moveToFirst()) return@use null
                val name = if (it.isNull(0)) null else it.getString(0)
                val size = if (it.isNull(1)) null else it.getLong(1)
                name to size
            }
        }.getOrNull() ?: (null to null)
    }

    override val name: String get() = info.first ?: uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"
    override val size: Long? get() = info.second

    override fun open(offset: Long): UploadReader {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("no se pudo abrir $name")
        return StreamUploadReader(input, offset)
    }
}
