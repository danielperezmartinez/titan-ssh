package io.github.danielperezmartinez.titanssh.keyinstall

/**
 * What runs on the host to install a public key, and how its answers are read
 * (ADR-0030). Pure text in and out, so every case is testable without a server.
 */
object KeyInstallScripts {

    /** Prefix of every line the Unix script reports on. */
    const val MARKER = "TITAN-KEY"

    /**
     * POSIX `sh` script, sent on stdin to `sh -s`, that does what `ssh-copy-id`
     * does: `~/.ssh` 700 and `authorized_keys` 600 (`umask 077`), the line
     * appended once (a key already there, whatever its comment, is left alone),
     * a missing final newline added first, and `restorecon` where SELinux has
     * it. It never touches the home's own permissions; it reports them when
     * they would make `StrictModes` ignore the key.
     *
     * Each outcome is one `TITAN-KEY` line ([parseUnix]); error texts come in
     * English (`LC_ALL=C`) so they can be told apart.
     */
    fun unix(publicLine: String, identity: String): String = """
        LC_ALL=C; export LC_ALL
        umask 077
        say() { printf '$MARKER %s\n' "${'$'}*"; }
        # Some shells (busybox) fail a write to a full disk without a word.
        why() { [ -n "${'$'}1" ] && { echo "${'$'}1"; return; }; [ "${'$'}(df -P . 2>/dev/null | awk 'NR==2 {print ${'$'}4}')" = 0 ] && echo "No space left on device"; }
        key=${shellQuote(publicLine)}
        id=${shellQuote(identity)}
        if [ -z "${'$'}HOME" ] || ! cd "${'$'}HOME" 2>/dev/null; then say FAIL NO_HOME; exit 0; fi
        case "${'$'}(ls -ld . 2>/dev/null | cut -c1-10)" in ?????w????|????????w?) say WARN HOME_WRITABLE;; esac
        if [ -e .ssh ] || [ -L .ssh ]; then
          [ -d .ssh ] || { say FAIL SSH_DIR_NOT_DIR; exit 0; }
          [ -O .ssh ] || { say FAIL SSH_DIR_NOT_OWNED; exit 0; }
        else
          err=${'$'}(mkdir .ssh 2>&1) || { say FAIL WRITE ${'$'}(why "${'$'}err"); exit 0; }
        fi
        chmod 700 .ssh 2>/dev/null || say WARN CHMOD
        f=.ssh/authorized_keys
        if [ -f "${'$'}f" ] && grep -qF "${'$'}id" "${'$'}f" 2>/dev/null; then
          state=PRESENT
        else
          nl=; if [ -s "${'$'}f" ] && [ -n "${'$'}(tail -c 1 "${'$'}f")" ]; then nl=1; fi
          err=${'$'}( { if [ -n "${'$'}nl" ]; then echo; fi; printf '%s\n' "${'$'}key"; } 2>&1 >>"${'$'}f" ) || { say FAIL WRITE ${'$'}(why "${'$'}err"); exit 0; }
          state=ADDED
        fi
        chmod 600 "${'$'}f" 2>/dev/null || say WARN CHMOD
        if command -v restorecon >/dev/null 2>&1; then restorecon -F .ssh "${'$'}f" >/dev/null 2>&1; fi
        say DONE ${'$'}state
        exit 0
    """.trimIndent() + "\n"

    /** What the Unix script reported. */
    sealed interface UnixOutcome {
        data class Done(val alreadyPresent: Boolean, val homeWritable: Boolean) : UnixOutcome
        data class Failed(val problem: KeyInstallProblem, val detail: String?) : UnixOutcome

        /** No report at all: the account did not run the script (no usable shell). */
        data object NotRun : UnixOutcome
    }

    /** Reads the script's [stdout] (and [stderr], for an expired password). */
    fun parseUnix(stdout: String, stderr: String = ""): UnixOutcome {
        val reports = stdout.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("$MARKER ") }
            .map { it.removePrefix("$MARKER ").split(' ', limit = 3) }
            .toList()
        if (reports.isEmpty()) {
            if (passwordExpired(stdout + "\n" + stderr)) {
                return UnixOutcome.Failed(KeyInstallProblem.PASSWORD_EXPIRED, null)
            }
            return UnixOutcome.NotRun
        }
        val homeWritable = reports.any { it[0] == "WARN" && it.getOrNull(1) == "HOME_WRITABLE" }
        reports.firstOrNull { it[0] == "FAIL" }?.let { fail ->
            val detail = fail.getOrNull(2)?.trim()?.ifEmpty { null }
            val problem = when (fail.getOrNull(1)) {
                "NO_HOME" -> KeyInstallProblem.NO_HOME
                "SSH_DIR_NOT_DIR" -> KeyInstallProblem.SSH_DIR_NOT_DIR
                "SSH_DIR_NOT_OWNED" -> KeyInstallProblem.SSH_DIR_NOT_OWNED
                "WRITE" -> writeProblem(detail.orEmpty())
                else -> KeyInstallProblem.UNEXPECTED
            }
            return UnixOutcome.Failed(problem, detail)
        }
        val done = reports.firstOrNull { it[0] == "DONE" }
            // It started but did not finish: the connection dropped under it.
            ?: return UnixOutcome.Failed(KeyInstallProblem.CONNECTION_LOST, null)
        return UnixOutcome.Done(alreadyPresent = done.getOrNull(1) == "PRESENT", homeWritable = homeWritable)
    }

    /** Which problem a failed write is, from the error the system gave (in English). */
    fun writeProblem(error: String): KeyInstallProblem = when {
        error.contains("Read-only file system", ignoreCase = true) -> KeyInstallProblem.READ_ONLY
        error.contains("No space left", ignoreCase = true) ||
            error.contains("quota", ignoreCase = true) -> KeyInstallProblem.DISK_FULL
        else -> KeyInstallProblem.FILE_NOT_WRITABLE
    }

    /** Whether the server stopped the command to make the user change an expired password. */
    fun passwordExpired(text: String): Boolean =
        PASSWORD_EXPIRED.containsMatchIn(text)

    private val PASSWORD_EXPIRED =
        Regex("password (has )?expired|change your password|password change required", RegexOption.IGNORE_CASE)

    /**
     * [existing] `authorized_keys` content with [publicLine] appended, or `null`
     * when a line for the same key ([identity]) is already there. Keeps the
     * file's own line ending and adds a missing final one first. For hosts
     * written over SFTP (Windows, and Unix accounts without a shell).
     */
    fun appendedAuthorizedKeys(existing: String, publicLine: String, identity: String): String? {
        if (existing.lineSequence().any { identityOf(it) == identity }) return null
        val eol = if (existing.contains("\r\n")) "\r\n" else "\n"
        val head = if (existing.isEmpty() || existing.endsWith("\n")) existing else existing + eol
        return head + publicLine + eol
    }

    private fun identityOf(line: String): String? =
        io.github.danielperezmartinez.titanssh.ssh.authorizedKeyIdentity(line)

    /** Whether `whoami /groups` lists the local Administrators group (by its SID). */
    fun isWindowsAdministrator(whoamiGroups: String): Boolean = whoamiGroups.contains(ADMINISTRATORS_SID)

    /**
     * Whether Win32-OpenSSH reads an administrator's keys from
     * `administrators_authorized_keys`: what its default `sshd_config` does with
     * a `Match Group administrators` block. A config that could not be read
     * ([sshdConfig] `null`) is taken as the default.
     */
    fun windowsUsesAdministratorsFile(sshdConfig: String?): Boolean {
        if (sshdConfig == null) return true
        return sshdConfig.lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("#") }
            .any { ADMIN_MATCH.matches(it) }
    }

    private val ADMIN_MATCH = Regex("match\\s+group\\s+\"?administrators\"?\\s*", RegexOption.IGNORE_CASE)

    /** The local Administrators group, the same in every language. */
    const val ADMINISTRATORS_SID = "S-1-5-32-544"

    /** SYSTEM. */
    const val SYSTEM_SID = "S-1-5-18"

    /**
     * Leaves [windowsPath] readable and writable only by Administrators and
     * SYSTEM, without inherited entries: what Win32-OpenSSH requires of
     * `administrators_authorized_keys`. Valid in cmd and PowerShell alike.
     */
    fun windowsAdministratorsAcl(windowsPath: String): String =
        "icacls \"$windowsPath\" /inheritance:r /grant \"*$ADMINISTRATORS_SID:F\" /grant \"*$SYSTEM_SID:F\""

    /** [text] as one single-quoted `sh` word. */
    fun shellQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"
}
