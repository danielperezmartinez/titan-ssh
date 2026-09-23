package io.github.danielperezmartinez.titanssh.terminal

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wraps a live shell in a terminal multiplexer for resilience level 2
 * ([[Resiliencia nivel 2 auto-tmux o screen]], ADR-0003): when the destination
 * already has tmux or screen, the session runs *inside* it, so the remote process
 * survives a dropped connection and reconnecting re-attaches to the same session
 * instead of a fresh shell.
 *
 * It talks to the shell only through a [ShellIo] (send + the broadcast output
 * tee), so it owns no SSH state and is unit-testable with a fake shell. Detection
 * and existence checks use a sentinel `printf` whose echoed command line cannot
 * match the result pattern (the value sits between two random tokens; the echoed
 * source shows the literal `$(…)` instead), the same trick as [ScriptRunner].
 *
 * Entering uses `new-session -A` / `-xRR` (attach-or-create) with a name derived
 * from the session id, so both the first connect and every reconnect converge on
 * the same multiplexer session.
 */
class TerminalMultiplexer(
    private val io: ShellIo,
    private val timeoutMillis: Long = 8_000,
) {
    enum class Kind { TMUX, SCREEN, NONE }

    /** Whether entering attached to a pre-existing session or created a new one. */
    enum class Attach { CREATED, ATTACHED }

    /** Detects an available multiplexer on the remote, preferring tmux. */
    suspend fun detect(): Kind {
        val value = probe(
            "command -v tmux >/dev/null 2>&1 && echo tmux || " +
                "(command -v screen >/dev/null 2>&1 && echo screen || echo none)",
        )
        return when (value) {
            "tmux" -> Kind.TMUX
            "screen" -> Kind.SCREEN
            else -> Kind.NONE
        }
    }

    /** True if a multiplexer session named [name] already exists on the remote. */
    suspend fun sessionExists(kind: Kind, name: String): Boolean {
        val check = when (kind) {
            Kind.TMUX -> "tmux has-session -t ${singleQuote(name)} >/dev/null 2>&1 && echo yes || echo no"
            // A screen session shows as "<pid>.<name>" in `screen -ls`.
            Kind.SCREEN -> "screen -ls 2>/dev/null | grep -q ${singleQuote("." + name + "\t")} && echo yes || echo no"
            Kind.NONE -> return false
        }
        return probe(check) == "yes"
    }

    /**
     * Enters (attach-or-create) the named multiplexer session with `exec`, so the
     * multiplexer *is* the session and exiting it ends the SSH shell. Call
     * [sessionExists] first to learn whether this was a fresh session.
     */
    suspend fun enter(kind: Kind, name: String) {
        val command = when (kind) {
            Kind.TMUX -> "exec tmux new-session -A -s ${singleQuote(name)}"
            Kind.SCREEN -> "exec screen -xRR -S ${singleQuote(name)}"
            Kind.NONE -> return
        }
        io.send(command + "\n")
    }

    /** Stable, shell-safe multiplexer session name for a config session id. */
    fun sessionName(sessionId: String): String {
        val safe = sessionId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .ifEmpty { "session" }
        return "titan-$safe"
    }

    /**
     * Sends [command] wrapped so its stdout is framed by a random token, and waits
     * for the framed value to echo back. Returns the captured value, or `null` on
     * timeout.
     */
    private suspend fun probe(command: String): String? = coroutineScope {
        val token = "TITANMUX_${randomToken()}"
        val regex = Regex("${Regex.escape(token)}:([^:\\n]*):${Regex.escape(token)}")
        val seen = MutableStateFlow("")
        val collector = launch {
            io.output.collect { chunk -> seen.update { trim(it + chunk) } }
        }
        try {
            // A separate printf so the value comes from the command substitution,
            // never from the echoed source line (which shows the literal `$(…)`).
            io.send("printf '%s:%s:%s\\n' '$token' \"\$($command)\" '$token'\n")
            withTimeoutOrNull(timeoutMillis) {
                var value: String? = null
                seen.first { s -> regex.find(s)?.also { value = it.groupValues[1] } != null }
                value
            }
        } finally {
            collector.cancel()
        }
    }

    private companion object {
        const val MAX_SEEN = 8_192

        fun trim(s: String): String = if (s.length <= MAX_SEEN) s else s.takeLast(MAX_SEEN)

        fun singleQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        fun randomToken(): String =
            kotlin.random.Random.nextLong().toULong().toString(16).padStart(16, '0')
    }
}
