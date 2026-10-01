package io.github.danielperezmartinez.titanssh.ssh

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [KnownHostsStore] backed by a file in OpenSSH `known_hosts` format
 * (`<host> <keytype> <base64>`, with `[host]:port` for non-default ports). Plain
 * `java.io.File`, so it serves both Android (app storage) and desktop; the app
 * chooses the path.
 *
 * Host names are not hashed (`HashKnownHosts`), keeping the file simple to
 * inspect; this is our trust store, not necessarily shared with the system.
 */
class FileKnownHostsStore(private val file: File) : KnownHostsStore {

    private val mutex = Mutex()

    override suspend fun entriesFor(host: String, port: Int): List<KnownHostEntry> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!file.exists()) return@withLock emptyList()
                file.readLines()
                    .mapNotNull { parseLine(it) }
                    .filter { it.host == host && it.port == port }
            }
        }

    override suspend fun add(entry: KnownHostEntry) = withContext(Dispatchers.IO) {
        mutex.withLock {
            file.parentFile?.mkdirs()
            file.appendText(formatLine(entry) + "\n")
        }
    }

    override suspend fun replace(entry: KnownHostEntry) = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Every other line stays as it was, comments and unparsable ones included.
            val kept = if (file.exists()) {
                file.readLines().filter { line ->
                    val parsed = parseLine(line)
                    parsed == null || parsed.host != entry.host || parsed.port != entry.port
                }
            } else {
                emptyList()
            }
            file.parentFile?.mkdirs()
            // Written aside and moved over, so a crash never leaves a half file.
            val next = File(file.parentFile, file.name + ".new")
            next.writeText((kept + formatLine(entry)).joinToString("\n", postfix = "\n"))
            Files.move(next.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            Unit
        }
    }

    private companion object {
        fun parseLine(raw: String): KnownHostEntry? {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return null
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 3) return null
            val (host, port) = parseHostField(parts[0])
            return KnownHostEntry(host, port, keyType = parts[1], publicKeyBase64 = parts[2])
        }

        fun formatLine(entry: KnownHostEntry): String {
            val hostField = if (entry.port == 22) entry.host else "[${entry.host}]:${entry.port}"
            return "$hostField ${entry.keyType} ${entry.publicKeyBase64}"
        }

        fun parseHostField(field: String): Pair<String, Int> {
            if (field.startsWith("[")) {
                val close = field.indexOf("]:")
                if (close > 0) {
                    val host = field.substring(1, close)
                    val port = field.substring(close + 2).toIntOrNull() ?: 22
                    return host to port
                }
            }
            return field to 22
        }
    }
}
