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
