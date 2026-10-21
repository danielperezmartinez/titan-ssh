package io.github.danielperezmartinez.titanssh.rdp

import io.github.danielperezmartinez.titanssh.config.PrivateFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [RdpCertificateStore] in a plain file, one `<key> <sha256>` per line, next
 * to `known_hosts` (ADR-0028 §6). Written aside and moved over, so a crash
 * never leaves half a file.
 */
class FileRdpCertificateStore(private val file: File) : RdpCertificateStore {
    private val mutex = Mutex()

    override suspend fun trusted(key: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock { read()[key] }
    }

    override suspend fun trust(key: String, sha256: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entries = read() + (key to sha256)
            file.parentFile?.mkdirs()
            val next = File(file.parentFile, file.name + ".new")
            PrivateFiles.writeText(next, entries.entries.joinToString("") { "${it.key} ${it.value}\n" })
            Files.move(next.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            Unit
        }
    }

    private fun read(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return file.readLines()
            .mapNotNull { line ->
                val parts = line.trim().split(' ')
                if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) parts[0] to parts[1] else null
            }
            .toMap()
    }
}
