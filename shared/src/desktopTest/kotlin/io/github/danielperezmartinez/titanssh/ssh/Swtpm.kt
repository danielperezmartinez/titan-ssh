package io.github.danielperezmartinez.titanssh.ssh

import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files

/**
 * A throwaway TPM 2.0 simulator (swtpm) on loopback TCP for the TPM tests,
 * with its state in a temporary folder. [start] returns null when swtpm is not
 * installed, so the tests skip.
 */
internal class Swtpm private constructor(private val process: Process, val port: Int, private val state: File) : AutoCloseable {

    fun transport(): Tpm2Transport = Tpm2Socket("127.0.0.1", port)

    override fun close() {
        process.destroy()
        process.waitFor()
        state.deleteRecursively()
    }

    companion object {
        fun start(): Swtpm? {
            val swtpm = System.getenv("PATH").orEmpty().split(File.pathSeparator)
                .map { File(it, "swtpm") }.firstOrNull { it.canExecute() }
                ?: return null.also { println("[tpm] skipped: swtpm is not installed") }
            val state = Files.createTempDirectory("titan-swtpm").toFile()
            val port = freePort()
            val ctrl = freePort()
            val process = ProcessBuilder(
                swtpm.path, "socket", "--tpm2",
                "--tpmstate", "dir=${state.path}",
                "--server", "type=tcp,port=$port,bindaddr=127.0.0.1",
                "--ctrl", "type=tcp,port=$ctrl,bindaddr=127.0.0.1",
                "--flags", "not-need-init,startup-clear",
            ).redirectErrorStream(true).redirectOutput(File(state, "swtpm.log")).start()
            repeat(50) {
                if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return Swtpm(process, port, state)
                Thread.sleep(100)
            }
            process.destroy()
            error("swtpm did not start: ${File(state, "swtpm.log").readText()}")
        }

        private fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
