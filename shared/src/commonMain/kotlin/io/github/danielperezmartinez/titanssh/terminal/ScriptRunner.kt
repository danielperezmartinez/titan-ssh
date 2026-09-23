package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.ScriptFailurePolicy
import io.github.danielperezmartinez.titanssh.config.SessionScript
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Outcome of one executed unit (a script, or the initial `cd`). */
data class ScriptOutcome(
    val scriptId: String,
    val status: RunStatus,
    /** Exit code of the command when it was awaited; `null` otherwise. */
    val exitCode: Int? = null,
    val detail: String? = null,
)

/** Terminal status of a single unit of work in a start-script run. */
enum class RunStatus {
    /** Fire-and-forget: sent to the shell without waiting for it to finish. */
    SENT,

    /** Awaited and finished with exit code 0. */
    COMPLETED,

    /** Awaited and finished with a non-zero exit code. */
    FAILED,

    /** Awaited but the completion sentinel (or expect pattern) never arrived. */
    TIMED_OUT,

    /** Not run because an earlier unit failed with an ABORT policy. */
    ABORTED,

    /** Skipped before sending (e.g. a referenced secret was missing). */
    SKIPPED,
}

/**
 * Executes a session's start scripts over a live shell at connect time
 * ([[Scripts de inicio por sesión]]). It only sends and observes text through a
 * [ShellIo]; it owns no SSH state, which makes it unit-testable with a fake IO.
 *
 * ## What it honors (v1, on connect)
 * - **Order and phase:** the caller passes the enabled scripts already ordered by
 *   phase; [run] also sends the session's initial `cd` first.
 * - **`${ref}` placeholders:** substituted client-side from the script's resolved
 *   [SessionScript.secretRefs] (from the SecretStore, never inlined in config).
 *   Any other `${...}` is left untouched for the remote shell to expand.
 * - **`envVars`:** exported (`export K=V`) before the body.
 * - **Behavior:** `delaySeconds` (wait before running), `expectPattern` (wait for
 *   a pattern before sending), `waitForCompletion` + `timeoutSeconds` (await a
 *   completion sentinel that also carries `$?`), and `onFailure` (CONTINUE vs
 *   ABORT the rest of the chain).
 *
 * ## Known limitation
 * `silent` cannot be enforced over a shared PTY (the remote echoes input); it is
 * accepted but not yet suppressed. Awaited commands must return to a prompt —
 * long-running/interactive bodies should use fire-and-forget or `expectPattern`.
 */
class ScriptRunner(
    private val io: ShellIo,
    /** Resolves a secret ref to its value, or `null` if absent. */
    private val resolveSecret: suspend (ref: String) -> String?,
    private val defaultTimeoutSeconds: Int = 30,
) {
    private val placeholder = Regex("""\$\{([^}]+)}""")

    /**
     * Runs [initialDirectory]'s `cd` (if any) then [scripts] in order, returning
     * one [ScriptOutcome] per unit. Stops early only when a unit fails under an
     * ABORT policy; remaining units are reported as [RunStatus.ABORTED].
     */
    suspend fun run(
        scripts: List<SessionScript>,
        initialDirectory: String?,
    ): List<ScriptOutcome> = coroutineScope {
        val outcomes = mutableListOf<ScriptOutcome>()
        if (initialDirectory == null && scripts.isEmpty()) return@coroutineScope outcomes

        // Accumulate the decoded output so expect/sentinel waits can match against
        // everything seen so far. Trimmed so a long session cannot grow it without
        // bound; sentinels always sit at the tail, so trimming the head is safe.
        val seen = MutableStateFlow("")
        val collector = launch {
            io.output.collect { chunk -> seen.update { trim(it + chunk) } }
        }
        try {
            var aborted = false

            if (initialDirectory != null && initialDirectory.isNotBlank()) {
                val cd = "cd -- ${singleQuote(initialDirectory)}"
                val outcome = awaitCommand("__cd__", cd, timeoutMs(null), seen)
                outcomes += outcome
                if (outcome.isFailure()) aborted = true
            }

            for (script in scripts) {
                if (aborted) {
                    outcomes += ScriptOutcome(script.id, RunStatus.ABORTED)
                    continue
                }
                val outcome = execute(script, seen)
                outcomes += outcome
                if (outcome.isFailure() &&
                    script.behavior.onFailure == ScriptFailurePolicy.ABORT
                ) {
                    aborted = true
                }
            }
        } finally {
            collector.cancel()
        }
        outcomes
    }

    private suspend fun execute(script: SessionScript, seen: StateFlow<String>): ScriptOutcome {
        val behavior = script.behavior

        // Resolve the secrets this script needs; a missing one skips the script
        // (respecting ABORT via the caller) rather than sending a broken command.
        val secrets = mutableMapOf<String, String>()
        for (ref in script.secretRefs) {
            val value = resolveSecret(ref)
                ?: return ScriptOutcome(
                    script.id, RunStatus.SKIPPED, detail = "Missing secret '$ref'",
                )
            secrets[ref] = value
        }

        behavior.delaySeconds?.let { if (it > 0) delay(it * 1000L) }

        behavior.expectPattern?.takeIf { it.isNotEmpty() }?.let { pattern ->
            val arrived = withTimeoutOrNull(timeoutMs(behavior.timeoutSeconds)) {
                seen.first { it.contains(pattern) }
                true
            } ?: false
            if (!arrived) {
                return ScriptOutcome(
                    script.id, RunStatus.TIMED_OUT,
                    detail = "Pattern '$pattern' not seen",
                )
            }
        }

        val block = buildString {
            for ((key, raw) in script.envVars) {
                append("export ").append(key).append('=')
                append(singleQuote(render(raw, secrets)))
                append('\n')
            }
            append(render(script.body, secrets))
        }

        if (!behavior.waitForCompletion) {
            io.send(block + "\n")
            return ScriptOutcome(script.id, RunStatus.SENT)
        }
        return awaitCommand(script.id, block, timeoutMs(behavior.timeoutSeconds), seen)
    }

    /**
     * Sends [command], then a sentinel line carrying `$?`, and waits for that
     * sentinel to echo back so the next command runs only once this one returned.
     */
    private suspend fun awaitCommand(
        id: String,
        command: String,
        timeoutMs: Long,
        seen: StateFlow<String>,
    ): ScriptOutcome {
        val token = "__TITAN_${randomToken()}__"
        io.send(command + "\n")
        // A separate line so `$?` reflects `command`, not the printf itself. The
        // echoed printf line can't match the pattern (its tokens aren't adjacent
        // to a number), only its actual output can.
        io.send("printf '%s:%s:%s\\n' '$token' \"\$?\" '$token'\n")

        val regex = Regex("${Regex.escape(token)}:(-?\\d+):${Regex.escape(token)}")
        val match = withTimeoutOrNull(timeoutMs) {
            var found: MatchResult? = null
            seen.first { s -> regex.find(s)?.also { found = it } != null }
            found
        } ?: return ScriptOutcome(id, RunStatus.TIMED_OUT, detail = "No completion")

        val code = match.groupValues[1].toIntOrNull()
        return if (code == 0) {
            ScriptOutcome(id, RunStatus.COMPLETED, exitCode = 0)
        } else {
            ScriptOutcome(id, RunStatus.FAILED, exitCode = code)
        }
    }

    private fun render(text: String, secrets: Map<String, String>): String =
        placeholder.replace(text) { m -> secrets[m.groupValues[1]] ?: m.value }

    private fun timeoutMs(seconds: Int?): Long = (seconds ?: defaultTimeoutSeconds) * 1000L

    private fun ScriptOutcome.isFailure(): Boolean =
        status == RunStatus.FAILED || status == RunStatus.TIMED_OUT || status == RunStatus.SKIPPED

    private companion object {
        const val MAX_SEEN = 32_768

        fun trim(s: String): String = if (s.length <= MAX_SEEN) s else s.takeLast(MAX_SEEN)

        /** Single-quotes [s] for POSIX shells, escaping embedded single quotes. */
        fun singleQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        fun randomToken(): String =
            kotlin.random.Random.nextLong().toULong().toString(16).padStart(16, '0')
    }
}
