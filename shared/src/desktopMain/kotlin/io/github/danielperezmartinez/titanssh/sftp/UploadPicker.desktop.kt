package io.github.danielperezmartinez.titanssh.sftp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import java.awt.FileDialog
import java.awt.Frame

@Composable
actual fun rememberUploadPicker(onPicked: (List<UploadSource>) -> Unit): () -> Unit {
    val picked by rememberUpdatedState(onPicked)
    return {
        // Modal: it runs its own event loop on the UI thread until closed.
        val dialog = FileDialog(null as Frame?, "Subir archivos", FileDialog.LOAD).apply { isMultipleMode = true }
        dialog.isVisible = true
        val files = dialog.files.orEmpty().filter { it.isFile }
        dialog.dispose()
        if (files.isNotEmpty()) picked(files.map(::FileUploadSource))
    }
}
