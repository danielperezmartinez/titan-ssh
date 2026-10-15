package io.github.danielperezmartinez.titanssh.sftp

/**
 * Where the SFTP browser saves what it downloads (ADR-0021): the device's
 * Downloads folder for now; a setting will choose another one
 * ([[Pantalla de ajustes generales de la app]]). Its calls block: run them off
 * the main thread.
 */
interface DownloadStore {
    /**
     * Creates a new, empty file for a download of [fileName]. A name already
     * taken gets a `(1)`, `(2)`… suffix, so nothing is overwritten.
     */
    fun create(fileName: String): DownloadTarget

    /**
     * Creates a new folder [name] for the download of a whole remote folder
     * (ADR-0024). A name already taken gets a `(1)`, `(2)`… suffix, as files do.
     */
    fun createFolder(name: String): DownloadFolder
}

/** A folder being downloaded: its files and subfolders go in it, keeping the remote layout. */
interface DownloadFolder {
    /** The name the folder really got. */
    val name: String

    /** Where it is, as the user would look for it (`Descargas/proyecto`). */
    val location: String

    /**
     * Creates the file [fileName] in [subfolder] (`""` for the folder itself,
     * else `/`-separated names, already made safe), which is created as needed.
     */
    fun create(subfolder: String, fileName: String): DownloadTarget

    /** Creates [subfolder] even if no file goes in it, where the platform can hold an empty folder. */
    fun makeFolder(subfolder: String)

    /**
     * The download will not finish: removes the folders it left empty once its
     * files are discarded. Never deletes a file.
     */
    fun discard()

    /** Opens the folder with the system's file manager; false if it cannot. */
    fun open(): Boolean
}

/** One file being downloaded: written in order, then kept or thrown away. */
interface DownloadTarget {
    /** The name the file really got, which can differ from the one asked for. */
    val name: String

    /** Where the file is, as the user would look for it (`Descargas/informe.pdf`). */
    val location: String

    /** Appends the first [length] bytes of [bytes]. */
    fun write(bytes: ByteArray, length: Int)

    /** The download finished: closes the file and makes it visible. */
    fun complete()

    /** The download will not finish: closes and deletes the partial file. */
    fun discard()

    /** Opens the finished file with the app the system picks; false if it cannot. */
    fun open(): Boolean
}

/**
 * A file on this device picked to be uploaded (ADR-0024). Its calls block: run
 * them off the main thread.
 */
interface UploadSource {
    val name: String

    /** Its size in bytes, when the platform tells. */
    val size: Long?

    /** Opens it for reading from byte [offset] on, for a resumed upload. */
    fun open(offset: Long): UploadReader
}

/** An open [UploadSource]. */
interface UploadReader {
    /** Fills [buffer] from the start; returns how many bytes it read, or -1 at the end. */
    fun read(buffer: ByteArray): Int

    fun close()
}

/** The platform's [DownloadStore]. */
expect fun createDownloadStore(): DownloadStore

/**
 * `informe.pdf` → `informe (n).pdf`, the name [DownloadStore.create] tries when
 * `informe.pdf` is taken. A leading dot is part of the name, not an extension.
 */
fun numberedFileName(fileName: String, n: Int): String {
    val dot = fileName.lastIndexOf('.')
    return if (dot <= 0) "$fileName ($n)" else "${fileName.substring(0, dot)} ($n)${fileName.substring(dot)}"
}

/**
 * A remote name made safe as a local file name: no path separators or
 * characters Windows refuses, and never empty.
 */
fun safeFileName(name: String): String {
    val cleaned = name.map { if (it in "/\\:*?\"<>|" || it.code < 32) '_' else it }.joinToString("").trim().trimEnd('.')
    return cleaned.ifEmpty { "descarga" }
}
