package io.github.danielperezmartinez.titanssh.update

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

internal actual fun createAppUpdater(): AppUpdater {
    val install = DesktopInstall.detect()
    return buildUpdater(
        platform = { (install as? DesktopInstall.Updatable)?.let(::DesktopUpdatePlatform) },
        unavailableReason = { (install as? DesktopInstall.Managed)?.reason },
    )
}

/** What kind of desktop install is running, from the jpackage launcher's path. */
internal sealed interface DesktopInstall {
    /** Updated by the app: [kind] is `msi`, `tar.gz`, `deb` or `rpm`. */
    data class Updatable(val kind: String, val launcher: File, val root: File) : DesktopInstall

    /** Updated by something else, or not at all. */
    data class Managed(val reason: String) : DesktopInstall

    companion object {
        fun detect(): DesktopInstall {
            if (System.getenv("FLATPAK_ID") != null) return Managed("Flatpak actualiza esta instalación.")
            // jpackage launchers set it; `gradlew run` and tests do not.
            val launcher = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile }
                ?: return Managed("Esta ejecución no es una instalación de la app.")
            val arch = System.getProperty("os.arch").orEmpty().lowercase()
            if (arch != "amd64" && arch != "x86_64") return Managed("No se publican paquetes para $arch.")
            val os = System.getProperty("os.name").orEmpty().lowercase()
            return when {
                os.contains("win") -> Updatable("msi", launcher, launcher.parentFile)
                os.contains("linux") -> detectLinux(launcher)
                else -> Managed("Este sistema no se actualiza desde la app.")
            }
        }

        /** `<root>/bin/titan-ssh`: a tar.gz the user owns, or a package of the system. */
        private fun detectLinux(launcher: File): DesktopInstall {
            val root = launcher.parentFile?.parentFile ?: return Managed("No se reconoce la instalación.")
            val parent = root.parentFile
            if (parent != null && Files.isWritable(root.toPath()) && Files.isWritable(parent.toPath())) {
                return Updatable("tar.gz", launcher, root)
            }
            val path = launcher.absolutePath
            if (succeeds("dpkg-query", "-S", path)) {
                return if (hasCommand("pkexec") && hasCommand("apt-get")) Updatable("deb", launcher, root)
                else Managed("Falta pkexec o apt-get para instalar el .deb.")
            }
            if (succeeds("rpm", "-qf", path)) {
                if (File("/run/ostree-booted").exists()) return Managed("El sistema es inmutable (rpm-ostree).")
                return if (hasCommand("pkexec")) Updatable("rpm", launcher, root)
                else Managed("Falta pkexec para instalar el .rpm.")
            }
            if (succeeds("pacman", "-Qo", path)) return Managed("pacman (AUR) actualiza esta instalación.")
            return Managed("La carpeta de la app no se puede escribir y no es de un paquete conocido.")
        }

        private fun hasCommand(name: String): Boolean = succeeds("sh", "-c", "command -v \"$1\"", "sh", name)

        private fun succeeds(vararg command: String): Boolean = runCatching {
            val process = ProcessBuilder(*command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)
    }
}

/**
 * Desktop half of the updater: the download waits in the per-user cache and
 * a small helper script (resources `/update/`) installs it once the app has
 * exited, since the running files cannot be replaced from inside.
 */
internal class DesktopUpdatePlatform(private val install: DesktopInstall.Updatable) : UpdatePlatform {
    override val artifactKind: String = install.kind
    override val cacheDir: File = desktopCacheDirectory()
    override val installsInBackground: Boolean = false

    private val windows = install.kind == "msi"

    override suspend fun install(file: File, version: String, relaunch: Boolean): InstallOutcome {
        val dir = file.parentFile
        val result = File(dir, RESULT_FILE).apply { delete() }
        // The helper's own output; on Windows msiexec writes its verbose log apart.
        val log = File(dir, "helper.log")
        val msiLog = File(dir, "msiexec.log")
        val scriptName = if (windows) "install-windows.ps1" else "install-linux.sh"
        val script = File(dir, scriptName)
        val body = javaClass.getResourceAsStream("/update/$scriptName")?.use { it.readBytes() }
            ?: return InstallOutcome.Failed("falta el instalador $scriptName")
        script.writeBytes(body)
        val pid = ProcessHandle.current().pid().toString()
        val command = if (windows) {
            buildList {
                addAll(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass"))
                addAll(listOf("-WindowStyle", "Hidden", "-File", script.absolutePath))
                addAll(listOf("-AppPid", pid, "-Msi", file.absolutePath, "-Log", msiLog.absolutePath, "-Result", result.absolutePath))
                if (relaunch) addAll(listOf("-Relaunch", install.launcher.absolutePath))
            }
        } else {
            val prefix = if (File("/usr/bin/setsid").exists() || File("/bin/setsid").exists()) listOf("setsid") else emptyList()
            prefix + listOf(
                "sh", script.absolutePath, install.kind, pid, file.absolutePath, install.root.absolutePath,
                install.launcher.absolutePath, result.absolutePath, if (relaunch) "1" else "0", log.absolutePath,
            )
        }
        return runCatching {
            ProcessBuilder(command)
                .directory(dir)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .start()
            InstallOutcome.Started
        }.getOrElse { InstallOutcome.Failed(it.message ?: "no se pudo lanzar el instalador") }
    }

    /** The helper ran but the version did not change: say what it reported. */
    override fun previousFailure(versionDir: File): String? {
        val code = runCatching { File(versionDir, RESULT_FILE).readText().trim() }.getOrNull() ?: return null
        return when {
            windows && code == "1602" -> "se canceló la instalación"
            windows -> "msiexec terminó con el código $code"
            install.kind != "tar.gz" && code == "126" -> "no se dio la contraseña"
            else -> "el instalador terminó con el código $code"
        }
    }

    private companion object {
        const val RESULT_FILE = "result"

        /** `%LOCALAPPDATA%` or `$XDG_CACHE_HOME` (`~/.cache`), under `titan-ssh/cache`. */
        fun desktopCacheDirectory(): File {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            val base: File = if (os.contains("win")) {
                System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let(::File)
                    ?: File(System.getProperty("user.home"), "AppData/Local")
            } else {
                System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
                    ?: File(System.getProperty("user.home"), ".cache")
            }
            return File(base, "titan-ssh/cache")
        }
    }
}
