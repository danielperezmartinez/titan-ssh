package io.github.danielperezmartinez.titanssh.terminal

import kotlin.io.encoding.Base64

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

    /** Sets the environment variable [key] to [value], taken literally, for the rest of the shell. */
    fun export(shell: RemoteShell, key: String, value: String): String = when (shell) {
        RemoteShell.POSIX -> "export $key=${posixQuote(value)}"
        RemoteShell.CMD -> "set $key=${cmdEscape(value)}"
        RemoteShell.POWERSHELL -> "\$env:$key = ${powerShellQuote(value)}"
    }

    /** Whether [shell] can run a [hiddenInput] block; `cmd.exe` cannot read input without echoing it. */
    fun supportsHiddenInput(shell: RemoteShell): Boolean = shell != RemoteShell.CMD

    /**
     * A line that makes [shell] read the next block of input without echoing it,
     * then run it as if it had been typed. It prints [hiddenInputReadyPattern]
     * once echo is off: the block ([hiddenInputBlock]) must not be sent before.
     * Neither the block nor its lines reach the screen or the shell's history.
     * If echo cannot be turned off the shell reads nothing and never prints the
     * marker. Not for [RemoteShell.CMD] ([supportsHiddenInput]).
     */
    fun hiddenInput(shell: RemoteShell, token: String): String = when (shell) {
        // The block arrives as `printf %b` escapes on lines of its own, ended by
        // the token, and `eval` runs it once echo is back on. The variable is
        // unset by the code eval runs, before the block itself. The line ends
        // in the token, so a terminal that scrolls a long line sideways still
        // shows it in the part left on screen; `&& :` keeps eval's status.
        RemoteShell.POSIX ->
            "stty -echo && { printf '%s:%s\\n' '$token' 'hidden'; __titan_b=; " +
                "while IFS= read -r __titan_l && [ \"\$__titan_l\" != '$token' ]; do __titan_b=\$__titan_b\$__titan_l; done; " +
                "stty echo; unset __titan_l; eval \"unset __titan_b; \$(printf '%b' \"\$__titan_b\")\"; } && : '$token'"
        // ReadKey(true) does not echo. The block arrives as base64 ended by `.`.
        // `$?` after Invoke-Expression does not reflect the block, so the block
        // records its own and a failure is replayed as the line's last status.
        // The line ends in the token so a console hides all of its rows.
        RemoteShell.POWERSHELL ->
            "\$__titanB = New-Object System.Text.StringBuilder; Write-Output ('$token' + ':hidden'); " +
                "while ((\$__titanK = [Console]::ReadKey(\$true).KeyChar) -ne '.') { [void]\$__titanB.Append(\$__titanK) }; " +
                "\$__titanS = \$__titanB.ToString(); Remove-Variable __titanB, __titanK; " +
                "Invoke-Expression ('Remove-Variable __titanS; ' + " +
                "[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(\$__titanS)) + [char]10 + '\$__titanRan = \$?'); " +
                "if (-not \$__titanRan) { Write-Error '' -ErrorAction Ignore } # $token"
        RemoteShell.CMD -> error("cmd.exe cannot read input without echoing it")
    }

    /** Matches what [hiddenInput] prints once echo is off; its echoed line never does. */
    fun hiddenInputReadyPattern(token: String): Regex = Regex("${Regex.escape(token)}:hidden")

    /** [block] encoded for the [hiddenInput] line started with [token]. */
    fun hiddenInputBlock(shell: RemoteShell, token: String, block: String): String = when (shell) {
        RemoteShell.POSIX -> posixHiddenLines(block).joinToString("") { it + "\n" } + token + "\n"
        RemoteShell.POWERSHELL -> Base64.encode(block.encodeToByteArray()) + "."
        RemoteShell.CMD -> error("cmd.exe cannot read input without echoing it")
    }

    /**
     * [block] as `printf %b` escapes split into short lines. Every control
     * character is escaped, so the terminal's line editing never acts on one,
     * and no line comes near the length a terminal line can hold. Neither an
     * escape nor a surrogate pair is split across two lines.
     */
    private fun posixHiddenLines(block: String): List<String> {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        var i = 0
        while (i < block.length) {
            val ch = block[i]
            val pair = ch.isHighSurrogate() && i + 1 < block.length && block[i + 1].isLowSurrogate()
            val piece = when {
                pair -> block.substring(i, i + 2)
                ch == '\\' -> "\\\\"
                ch == '\n' -> "\\n"
                ch < ' ' || ch == '\u007F' -> "\\0" + ch.code.toString(8).padStart(3, '0')
                else -> ch.toString()
            }
            i += if (pair) 2 else 1
            if (line.length + piece.length > HIDDEN_LINE_LENGTH) {
                lines += line.toString()
                line.clear()
            }
            line.append(piece)
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines
    }

    /**
     * [s] escaped for an unquoted `cmd.exe` command line: a caret before each
     * character the parser acts on, and before each `%` so no variable is
     * expanded (`^%PATH^%` names no variable and stays as typed).
     */
    private fun cmdEscape(s: String): String = buildString {
        for (ch in s) {
            if (ch in "^&|<>()\"%") append('^')
            append(ch)
        }
    }

    private const val HIDDEN_LINE_LENGTH = 512

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
