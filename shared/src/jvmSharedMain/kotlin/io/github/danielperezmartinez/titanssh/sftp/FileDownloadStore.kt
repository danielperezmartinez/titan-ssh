package io.github.danielperezmartinez.titanssh.sftp

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Downloads as plain files in [directory]: the desktop's Downloads, and the
 * app's own Downloads folder on Android 8–9. [opener] opens a finished file or
 * folder with the system's app; false if it cannot.
 */
internal class FileDownloadStore(
    private val directory: File,
    private val opener: (File) -> Boolean,
) : DownloadStore {
    override fun create(fileName: String): DownloadTarget = newFile(directory, fileName, opener)

    override fun createFolder(name: String): DownloadFolder {
        directory.mkdirs()
        var n = 0
        while (true) {
            val candidate = File(directory, if (n == 0) name else "$name ($n)")
            // mkdir is atomic: two downloads of the same folder never share one.
            if (candidate.mkdir()) return FileDownloadFolder(candidate, opener)
            if (!candidate.exists()) throw IOException("no se pudo crear la carpeta ${candidate.path}")
            n++
        }
    }
}

/** A new file named [fileName] in [directory], numbered if the name is taken. */
private fun newFile(directory: File, fileName: String, opener: (File) -> Boolean): DownloadTarget {
    directory.mkdirs()
    var n = 0
    while (true) {
        val candidate = File(directory, if (n == 0) fileName else numberedFileName(fileName, n))
        // createNewFile is atomic: two downloads of the same name never share a file.
        if (candidate.createNewFile()) return FileDownloadTarget(candidate, opener)
        n++
    }
}

private class FileDownloadFolder(private val root: File, private val opener: (File) -> Boolean) : DownloadFolder {
    override val name: String get() = root.name
    override val location: String get() = root.path

    override fun create(subfolder: String, fileName: String): DownloadTarget =
        newFile(folder(subfolder), fileName, opener)

    override fun makeFolder(subfolder: String) {
        folder(subfolder).mkdirs()
    }

    override fun discard() = removeEmptyFolders(root)

    override fun open(): Boolean = opener(root)

    private fun folder(subfolder: String): File =
        subfolder.split('/').filter { it.isNotEmpty() }.fold(root) { dir, part -> File(dir, part) }
}

/** Deletes [dir] and the folders under it that hold nothing; files are left alone. */
internal fun removeEmptyFolders(dir: File) {
    dir.listFiles()?.filter { it.isDirectory }?.forEach(::removeEmptyFolders)
    if (dir.listFiles()?.isEmpty() == true) dir.delete()
}

private class FileDownloadTarget(private val file: File, private val opener: (File) -> Boolean) : DownloadTarget {
    private val out = FileOutputStream(file)

    override val name: String get() = file.name
    override val location: String get() = file.path

    override fun write(bytes: ByteArray, length: Int) = out.write(bytes, 0, length)

    override fun complete() = out.close()

    override fun discard() {
        runCatching { out.close() }
        file.delete()
    }

    override fun open(): Boolean = opener(file)
}

/** An [UploadReader] over a stream opened at the start of the file: skips to [offset] first. */
internal class StreamUploadReader(private val input: InputStream, offset: Long) : UploadReader {
    init {
        var left = offset
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
            } else if (input.read() < 0) {
                input.close()
                throw IOException("el archivo local es más corto que lo ya subido")
            } else {
                left--
            }
        }
    }

    override fun read(buffer: ByteArray): Int = input.read(buffer)

    override fun close() = input.close()
}

/** A local file as an [UploadSource]. */
internal class FileUploadSource(private val file: File) : UploadSource {
    override val name: String get() = file.name
    override val size: Long? get() = file.length()

    override fun open(offset: Long): UploadReader = StreamUploadReader(file.inputStream(), offset)
}
