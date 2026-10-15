package io.github.danielperezmartinez.titanssh.sftp

import io.github.danielperezmartinez.titanssh.ssh.SftpEntry

/**
 * Path arithmetic for the SFTP browser (ADR-0021). SFTP paths are `/`-separated;
 * a Windows server shows its drives behind a leading slash (`/C:/Users/u`).
 */
object SftpPaths {

    /**
     * Where the browser starts for a session whose working directory is
     * [initialDirectory], given the user's [home] on the server: `~` and
     * relative paths hang from [home], and a Windows path (`C:\srv`) becomes
     * its SFTP form. Null or blank is [home] itself.
     */
    fun initial(initialDirectory: String?, home: String): String {
        val dir = initialDirectory?.trim().orEmpty()
        return when {
            dir.isEmpty() || dir == "~" -> home
            dir.startsWith("~/") || dir.startsWith("~\\") -> join(home, dir.substring(2))
            WINDOWS_DRIVE.containsMatchIn(dir) -> "/" + dir.replace('\\', '/')
            dir.startsWith("/") -> dir
            else -> join(home, dir)
        }
    }

    /** [child] under [directory]; [child] may hold several `/`-separated parts. */
    fun join(directory: String, child: String): String {
        val tail = child.replace('\\', '/').trim('/')
        if (tail.isEmpty()) return directory
        return if (directory.endsWith("/")) directory + tail else "$directory/$tail"
    }

    /** The folder holding [path], or null at the root. `/C:/Users` → `/C:/`, `/C:/` → `/`. */
    fun parent(path: String): String? {
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty()) return null
        val cut = trimmed.lastIndexOf('/')
        if (cut <= 0) return "/"
        val up = trimmed.substring(0, cut)
        // A drive's root lists only with its slash.
        return if (DRIVE_ONLY.matches(up)) "$up/" else up
    }

    /**
     * The folders from the root down to [path], each with the name to show and
     * the path to open: `/srv/app` → `/`, `srv`, `app`.
     */
    fun crumbs(path: String): List<Pair<String, String>> {
        val parts = path.split('/').filter { it.isNotEmpty() }
        val out = mutableListOf("/" to "/")
        var current = ""
        for (part in parts) {
            current += "/$part"
            out += part to if (DRIVE_ONLY.matches(current)) "$current/" else current
        }
        return out
    }

    /**
     * How the browser lists a folder: folders (and links to them) first, then the
     * rest, each by name ignoring case. Names starting with `.` only with [showHidden].
     */
    fun sorted(entries: List<SftpEntry>, showHidden: Boolean): List<SftpEntry> =
        entries
            .filter { showHidden || !it.name.startsWith(".") }
            .sortedWith(compareBy<SftpEntry> { !it.isDirectory }.thenBy { it.name.lowercase() }.thenBy { it.name })

    /**
     * Why [name] cannot be a file or folder name on the destination (empty,
     * `.`/`..`, or with a `/`), or null if it can. The server decides the rest.
     */
    fun nameProblem(name: String): String? = when {
        name.isEmpty() -> "Falta el nombre"
        name == "." || name == ".." -> "«$name» no es un nombre válido"
        '/' in name -> "El nombre no puede llevar «/»"
        else -> null
    }

    /** `1.4 MB`-style size for the listing. */
    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val rounded = if (value < 10) (value * 10).toLong() / 10.0 else value.toLong().toDouble()
        val text = if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
        return "$text ${units[unit]}"
    }

    /** `rwxr-xr-x` from the Unix permission bits. */
    fun formatPermissions(mode: Int): String = buildString {
        for (shift in listOf(6, 3, 0)) {
            val bits = (mode shr shift) and 7
            append(if (bits and 4 != 0) 'r' else '-')
            append(if (bits and 2 != 0) 'w' else '-')
            append(if (bits and 1 != 0) 'x' else '-')
        }
    }

    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:([\\\\/]|$)")
    private val DRIVE_ONLY = Regex("^/[A-Za-z]:$")
}
