package io.github.danielperezmartinez.titanssh.sftp

import androidx.compose.runtime.Composable

/**
 * The system's picker for the files to upload (ADR-0024): the document picker
 * on Android, the file dialog on desktop, both with several files at once.
 * Returns what opens it; [onPicked] gets the files chosen, never an empty list.
 */
@Composable
expect fun rememberUploadPicker(onPicked: (List<UploadSource>) -> Unit): () -> Unit
