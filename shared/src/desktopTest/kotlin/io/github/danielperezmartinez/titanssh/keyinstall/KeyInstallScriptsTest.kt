package io.github.danielperezmartinez.titanssh.keyinstall

import io.github.danielperezmartinez.titanssh.keyinstall.KeyInstallScripts.UnixOutcome
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Unix script and the readers of what hosts answer (ADR-0030). The script
 * runs for real with this machine's `/bin/sh` on a temporary home; the
 * opt-in [KeyInstallIntegrationTest] runs it on a server.
 */
class KeyInstallScriptsTest {

    private val line = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIKey titan-ssh"
    private val identity = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIKey"

    private val sh = File("/bin/sh").takeIf { it.canExecute() }

    /** Runs the script as `sh -s` with [home] as HOME; its stdout. */
    private fun runScript(home: File, publicLine: String = line): String {
        val process = ProcessBuilder(sh!!.path, "-s").apply { environment()["HOME"] = home.path }.start()
        process.outputStream.use { it.write(KeyInstallScripts.unix(publicLine, identity).toByteArray()) }
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return out
    }

    private fun home(): File = Files.createTempDirectory("titan-home").toFile().apply {
        Files.setPosixFilePermissions(toPath(), PosixFilePermissions.fromString("rwx------"))
        deleteOnExit()
    }

    private fun modeOf(file: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))

    @Test
    fun creates_ssh_dir_and_file_with_strict_modes() {
        sh ?: return
        val home = home()
        assertEquals(UnixOutcome.Done(alreadyPresent = false, homeWritable = false), KeyInstallScripts.parseUnix(runScript(home)))
        val keys = File(home, ".ssh/authorized_keys")
        assertEquals("$line\n", keys.readText())
        assertEquals("rwx------", modeOf(File(home, ".ssh")))
        assertEquals("rw-------", modeOf(keys))
    }

    @Test
    fun a_key_already_there_is_not_added_twice_whatever_its_comment() {
        sh ?: return
        val home = home()
        File(home, ".ssh").mkdir()
        File(home, ".ssh/authorized_keys").writeText("ssh-rsa AAAAother a\n$identity old-comment\n")
        assertEquals(UnixOutcome.Done(alreadyPresent = true, homeWritable = false), KeyInstallScripts.parseUnix(runScript(home)))
        assertEquals("ssh-rsa AAAAother a\n$identity old-comment\n", File(home, ".ssh/authorized_keys").readText())
    }

    @Test
    fun appends_after_a_last_line_without_newline_and_fixes_modes() {
        sh ?: return
        val home = home()
        val dir = File(home, ".ssh").apply { mkdir() }
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        val keys = File(dir, "authorized_keys").apply { writeText("ssh-rsa AAAAother a") }
        Files.setPosixFilePermissions(keys.toPath(), PosixFilePermissions.fromString("rw-rw-rw-"))
        assertEquals(UnixOutcome.Done(alreadyPresent = false, homeWritable = false), KeyInstallScripts.parseUnix(runScript(home)))
        assertEquals("ssh-rsa AAAAother a\n$line\n", keys.readText())
        assertEquals("rwx------", modeOf(dir))
        assertEquals("rw-------", modeOf(keys))
    }

    @Test
    fun reports_a_home_others_can_write_without_touching_it() {
        sh ?: return
        val home = home()
        Files.setPosixFilePermissions(home.toPath(), PosixFilePermissions.fromString("rwxrwxr-x"))
        assertEquals(UnixOutcome.Done(alreadyPresent = false, homeWritable = true), KeyInstallScripts.parseUnix(runScript(home)))
        assertEquals("rwxrwxr-x", modeOf(home))
    }

    @Test
    fun an_ssh_file_that_is_not_a_folder_stops_it() {
        sh ?: return
        val home = home()
        File(home, ".ssh").writeText("x")
        assertEquals(UnixOutcome.Failed(KeyInstallProblem.SSH_DIR_NOT_DIR, null), KeyInstallScripts.parseUnix(runScript(home)))
    }

    @Test
    fun a_file_it_cannot_write_is_reported_with_the_system_error() {
        sh ?: return
        if (System.getProperty("user.name") == "root") return // root writes anyway
        val home = home()
        val dir = File(home, ".ssh").apply { mkdir() }
        File(dir, "authorized_keys").writeText("")
        Files.setPosixFilePermissions(File(dir, "authorized_keys").toPath(), setOf(PosixFilePermission.OWNER_READ))
        // chmod 600 runs after the write, so the read-only file stays so.
        val outcome = KeyInstallScripts.parseUnix(runScript(home))
        outcome as UnixOutcome.Failed
        assertEquals(KeyInstallProblem.FILE_NOT_WRITABLE, outcome.problem)
        assertTrue(outcome.detail!!.contains("Permission denied"), outcome.detail)
    }

    @Test
    fun a_missing_home_is_reported() {
        sh ?: return
        val gone = File(home(), "nope")
        assertEquals(UnixOutcome.Failed(KeyInstallProblem.NO_HOME, null), KeyInstallScripts.parseUnix(runScript(gone)))
    }

    @Test
    fun quotes_survive_in_the_line() {
        sh ?: return
        val home = home()
        val odd = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIKey it's \$HOME `x`"
        KeyInstallScripts.parseUnix(runScript(home, odd))
        assertEquals("$odd\n", File(home, ".ssh/authorized_keys").readText())
    }

    @Test
    fun reads_failures_and_silence() {
        assertEquals(UnixOutcome.NotRun, KeyInstallScripts.parseUnix("This account is currently not available.\n"))
        assertEquals(
            UnixOutcome.Failed(KeyInstallProblem.PASSWORD_EXPIRED, null),
            KeyInstallScripts.parseUnix("", "WARNING: Your password has expired.\nPassword change required but no TTY available.\n"),
        )
        assertEquals(
            UnixOutcome.Failed(KeyInstallProblem.READ_ONLY, "sh: can't create .ssh/authorized_keys: Read-only file system"),
            KeyInstallScripts.parseUnix("TITAN-KEY FAIL WRITE sh: can't create .ssh/authorized_keys: Read-only file system\n"),
        )
        assertEquals(KeyInstallProblem.DISK_FULL, KeyInstallScripts.writeProblem("write error: No space left on device"))
        assertEquals(KeyInstallProblem.DISK_FULL, KeyInstallScripts.writeProblem("Disk quota exceeded"))
        assertEquals(KeyInstallProblem.FILE_NOT_WRITABLE, KeyInstallScripts.writeProblem("Operation not permitted"))
        // Cut off before the end: the connection dropped.
        assertEquals(
            UnixOutcome.Failed(KeyInstallProblem.CONNECTION_LOST, null),
            KeyInstallScripts.parseUnix("TITAN-KEY WARN HOME_WRITABLE\n"),
        )
        // Noise around the report (a chatty .profile) does not matter.
        assertEquals(
            UnixOutcome.Done(alreadyPresent = true, homeWritable = false),
            KeyInstallScripts.parseUnix("Welcome!\nTITAN-KEY DONE PRESENT\nbye\n"),
        )
    }

    @Test
    fun appends_over_sftp_without_duplicates_and_with_the_file_line_ending() {
        assertEquals("$line\n", KeyInstallScripts.appendedAuthorizedKeys("", line, identity))
        assertEquals("a\n$line\n", KeyInstallScripts.appendedAuthorizedKeys("a", line, identity))
        assertEquals("a\r\nb\r\n$line\r\n", KeyInstallScripts.appendedAuthorizedKeys("a\r\nb\r\n", line, identity))
        assertNull(KeyInstallScripts.appendedAuthorizedKeys("x\r\n$identity other\r\n", line, identity))
    }

    @Test
    fun reads_windows_administrators_and_their_sshd_config() {
        assertTrue(KeyInstallScripts.isWindowsAdministrator("BUILTIN\\Administradores Alias S-1-5-32-544 Grupo obligatorio"))
        assertFalse(KeyInstallScripts.isWindowsAdministrator("BUILTIN\\Users Alias S-1-5-32-545"))
        val default = "AuthorizedKeysFile .ssh/authorized_keys\n\nMatch Group administrators\n" +
            "       AuthorizedKeysFile __PROGRAMDATA__/ssh/administrators_authorized_keys\n"
        assertTrue(KeyInstallScripts.windowsUsesAdministratorsFile(default))
        assertFalse(KeyInstallScripts.windowsUsesAdministratorsFile(default.replace("Match", "#Match")))
        assertTrue(KeyInstallScripts.windowsUsesAdministratorsFile(null))
        assertEquals(
            "icacls \"C:\\ProgramData\\ssh\\administrators_authorized_keys\" /inheritance:r /grant \"*S-1-5-32-544:F\" /grant \"*S-1-5-18:F\"",
            KeyInstallScripts.windowsAdministratorsAcl("C:\\ProgramData\\ssh\\administrators_authorized_keys"),
        )
    }
}
