package io.github.danielperezmartinez.titanssh.terminal

/**
 * The commands the start-script automation types into a live shell, written in
 * the syntax of the destination's [RemoteShell]. A POSIX shell and a Windows
 * one (`cmd.exe`, PowerShell) differ in how they change directory, report the
 * last exit status and export a variable, and a Windows console (ConPTY) takes
 * only a carriage return as Enter: a bare line feed is not.
 */
object ShellSyntax {

    /**
     * Run on an `exec` channel, it tells the three shells apart in one round
     * trip: `cmd.exe` expands `%OS%` (`Windows_NT`), PowerShell prints it
     * literally but expands `$PSHOME` (its install directory, on a line of its
     * own), and a POSIX shell expands neither to anything but an empty string.
     */
    const val PROBE: String = "echo %OS% \$PSHOME"

    /** Reads the output of [PROBE]; anything unrecognized is a POSIX shell. */
    fun parseProbe(output: String): RemoteShell {
        val tokens = output.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            tokens.firstOrNull() == "Windows_NT" -> RemoteShell.CMD
            tokens.firstOrNull() == "%OS%" && tokens.size >= 2 && tokens[1] != "\$PSHOME" -> RemoteShell.POWERSHELL
            else -> RemoteShell.POSIX
        }
    }

    /** What ends a typed line: `\n` for a POSIX PTY, `\r` for a Windows console. */
    fun enter(shell: RemoteShell): String = if (shell == RemoteShell.POSIX) "\n" else "\r"

    /** [text] with every line ended by [enter] rather than `\n`. */
    fun lines(shell: RemoteShell, text: String): String =
        if (shell == RemoteShell.POSIX) text + "\n"
        else text.replace("\r\n", "\n").split('\n').joinToString("") { it + "\r" }

    /** Changes to [directory], taken literally (no variable or `~` expansion on POSIX). */
    fun cd(shell: RemoteShell, directory: String): String = when (shell) {
        RemoteShell.POSIX -> "cd -- ${posixQuote(directory)}"
        // `/d` also switches drive. A Windows path cannot hold a double quote.
        RemoteShell.CMD -> "cd /d \"${directory.replace("\"", "")}\""
        RemoteShell.POWERSHELL -> "Set-Location -LiteralPath ${powerShellQuote(directory)}"
    }

    /** Sets the environment variable [key] to [value] for the rest of the shell. */
    fun export(shell: RemoteShell, key: String, value: String): String = when (shell) {
        RemoteShell.POSIX -> "export $key=${posixQuote(value)}"
        RemoteShell.CMD -> "set \"$key=$value\""
        RemoteShell.POWERSHELL -> "\$env:$key = ${powerShellQuote(value)}"
    }

    /**
     * Prints `<token>:<status>:<token>`, where status is the exit status of the
     * previous line (0 on success). The echoed command itself never matches
     * [sentinelPattern]: its tokens are not next to a number.
     */
    fun sentinel(shell: RemoteShell, token: String): String = when (shell) {
        RemoteShell.POSIX -> "printf '%s:%s:%s\\n' '$token' \"\$?\" '$token'"
        RemoteShell.CMD -> "echo $token:%errorlevel%:$token"
        // `$?` must be read first, before any other statement resets it.
        RemoteShell.POWERSHELL ->
            "\$__titanOk = \$?; \$__titanCode = if (\$__titanOk) { 0 } elseif (\$LASTEXITCODE) { \$LASTEXITCODE } else { 1 }; " +
                "Write-Output ('$token' + ':' + \$__titanCode + ':' + '$token')"
    }

    /** Matches the output of [sentinel] for [token], capturing the status. */
    fun sentinelPattern(token: String): Regex =
        Regex("${Regex.escape(token)}:(-?\\d+):${Regex.escape(token)}")

    /** Single-quotes [s] for POSIX shells, escaping embedded single quotes. */
    fun posixQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun powerShellQuote(s: String): String = "'" + s.replace("'", "''") + "'"
}
