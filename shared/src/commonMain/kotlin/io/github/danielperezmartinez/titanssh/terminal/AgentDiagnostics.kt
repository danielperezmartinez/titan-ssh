package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.ssh.SshSession
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Why resilience level 3 is not in use on a tab, or what limits it (ADR-0009
 * §5 and §7, [[Diagnóstico cuando el nivel 3 no está disponible]]). [code] is a
 * `TITAN_AGENT_ERROR` code from the agent or a client-side one, both listed in
 * [AgentDiagnostics]; [detail] is the technical cause in English, shown only for
 * the codes whose meaning depends on it.
 */
data class AgentIssue(val code: String, val detail: String = "") {
    /** Level 3 still works, but the host will end it at logout (see [AgentDiagnostics.E_SYSTEMD_KILL]). */
    val isWarning: Boolean get() = code == AgentDiagnostics.E_SYSTEMD_KILL
}

/** What an [AgentDeployer] made of a destination. */
sealed interface AgentDeployment {
    /** The agent is installed: launch it with [launch]. [warning] is a non-blocking [AgentIssue]. */
    data class Ready(val launch: AgentLaunch, val warning: AgentIssue? = null) : AgentDeployment

    /** Level 3 cannot run there: the tab degrades to level 2/1 and shows [issue]. */
    data class Unavailable(val issue: AgentIssue) : AgentDeployment
}

/** Output of one `exec`: its stdout, stderr and exit status (null if the server sent none). */
internal data class ExecResult(val stdout: String, val stderr: String, val exitStatus: Int?)

/** Runs [command] on its own exec channel and collects everything it printed. */
internal suspend fun SshSession.execCollect(command: String): ExecResult = coroutineScope {
    val channel = exec(command)
    val err = StringBuilder()
    val errJob = launch { channel.errors.collect { err.append(it.decodeToString()) } }
    val out = StringBuilder()
    channel.output.collect { out.append(it.decodeToString()) }
    errJob.join()
    val status = channel.close()
    ExecResult(out.toString(), err.toString(), status)
}

/**
 * The level-3 diagnostic vocabulary: the error-contract codes, how to read them
 * from the agent (a `TITAN_AGENT_ERROR` line on the front's stderr, or the
 * reason of a `BYE` that refuses the session), the user-facing Spanish text for
 * each, and the systemd `KillUserProcesses` check. Pure, so it is tested
 * headless; the SSH side lives in [AgentTransport] and `AgentInstaller`.
 */
object AgentDiagnostics {

    // --- agent side (agent/cmd/titan-agent/errors.go, internal/session/pty.go) ---

    /** The state directory is not private to the user, or cannot be created. */
    const val E_STATE_DIR = "E_STATE_DIR"

    /** The filesystem refuses the single-instance lock (e.g. a home on NFS). */
    const val E_LOCK = "E_LOCK"

    /** No daemon answered in time after the front launched one. */
    const val E_DAEMON_START = "E_DAEMON_START"

    /** The state file is unreadable, or the daemon refused its token. */
    const val E_AUTH = "E_AUTH"

    /** An agent of an older version runs on the destination; it has to be stopped for this one to start. */
    const val E_AGENT_OUTDATED = "E_AGENT_OUTDATED"

    /** Windows: the SSH session's job kills its processes and will not let the daemon out. */
    const val E_JOB_NO_BREAKAWAY = "E_JOB_NO_BREAKAWAY"

    /** Windows older than 10 1809 / Server 2019: no ConPTY. */
    const val E_NO_CONPTY = "E_NO_CONPTY"

    /** The PTY or its shell could not be started. */
    const val E_PTY = "E_PTY"

    /** Mouse pad: the destination has no input injector yet (only Windows has one). */
    const val E_INPUT_UNSUPPORTED = "E_INPUT_UNSUPPORTED"

    /** Mouse pad: the desktop helper did not start, most often because nobody is signed in. */
    const val E_NO_DESKTOP = "E_NO_DESKTOP"

    /** Mouse pad, Windows: the helper's scheduled task could not be created or run. */
    const val E_DESKTOP_TASK = "E_DESKTOP_TASK"

    // --- client side ---

    /** The destination's OS or architecture has no agent build, or could not be detected. */
    const val E_UNSUPPORTED_TARGET = "E_UNSUPPORTED_TARGET"

    /** The target has a build, but the app could not get it (not bundled and the download failed). */
    const val E_NO_BINARY = "E_NO_BINARY"

    /** Placing the binary on the destination failed (SFTP or the exec fallback). */
    const val E_UPLOAD = "E_UPLOAD"

    /** The installed file does not match the expected SHA-256. */
    const val E_CHECKSUM = "E_CHECKSUM"

    /** The destination will not run the binary (`noexec` home, AppLocker/WDAC). */
    const val E_NOEXEC = "E_NOEXEC"

    /** The agent ended without serving the session, for a reason it did not name. */
    const val E_AGENT_EXIT = "E_AGENT_EXIT"

    /**
     * Warning, Linux only: systemd has `KillUserProcesses=yes` and the user has
     * no linger, so the daemon (and tmux) die when the last session closes.
     */
    const val E_SYSTEMD_KILL = "E_SYSTEMD_KILL"

    /** Marker of the agent's stderr contract line: `TITAN_AGENT_ERROR <code> <message>`. */
    const val CONTRACT_PREFIX = "TITAN_AGENT_ERROR"

    /**
     * Prints `kill=<KillUserProcesses>` and `Linger=<yes|no>` for the current
     * user; nothing useful without systemd. Asks logind over D-Bus and falls
     * back to `logind.conf` (a drop-in overrides the main file). Wrapped in
     * `sh -c` because the login shell may not be POSIX (fish).
     */
    val SYSTEMD_PROBE: String = "sh -c " + AgentInstall.posixQuote(
        "k=\$(busctl get-property org.freedesktop.login1 /org/freedesktop/login1 " +
            "org.freedesktop.login1.Manager KillUserProcesses 2>/dev/null); " +
            "[ -n \"\$k\" ] || k=\$(cat /etc/systemd/logind.conf /etc/systemd/logind.conf.d/*.conf 2>/dev/null " +
            "| sed -n 's/^[[:space:]]*KillUserProcesses=//p' | tail -n 1); " +
            "echo \"kill=\$k\"; loginctl show-user \"\$(id -un)\" -p Linger 2>/dev/null",
    )

    /** Keeps the user's processes alive after logout; polkit usually lets a user do it for themselves. */
    const val ENABLE_LINGER: String = "loginctl enable-linger"

    /** Exit statuses of `sh` for a command it found but could not run (126) or did not find (127). */
    private val NOT_RUNNABLE = setOf(126, 127)

    /** Parses `<code> <message>` (the tail of the contract line and a `BYE` reason). */
    fun parseReason(reason: String): AgentIssue {
        val text = reason.trim()
        val code = text.substringBefore(' ')
        return if (CODE.matches(code)) {
            AgentIssue(code, text.substringAfter(' ', "").trim())
        } else {
            AgentIssue(E_AGENT_EXIT, text)
        }
    }

    /** The last `TITAN_AGENT_ERROR` line in [stderr], if any. */
    fun parseContract(stderr: String): AgentIssue? =
        stderr.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("$CONTRACT_PREFIX ") }
            ?.let { parseReason(it.removePrefix(CONTRACT_PREFIX)) }

    /**
     * Why an agent front that never answered `HELLO_OK` ended, from its
     * [stderr] and [exitStatus]. Null when there is nothing to go on (no exit
     * status: the connection dropped), which the tab treats as a drop.
     */
    fun classifyFrontExit(stderr: String, exitStatus: Int?): AgentIssue? {
        parseContract(stderr)?.let { return it }
        if (exitStatus == null) return null
        val detail = stderr.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: "exit status $exitStatus"
        return if (exitStatus in NOT_RUNNABLE) AgentIssue(E_NOEXEC, detail) else AgentIssue(E_AGENT_EXIT, detail)
    }

    /**
     * Reads the [SYSTEMD_PROBE] output: the [E_SYSTEMD_KILL] warning when logind
     * kills the user's processes at logout and the user has no linger. A host
     * without systemd, or with an answer it cannot read, gives no warning.
     */
    fun parseSystemdProbe(output: String): AgentIssue? {
        var kill: Boolean? = null
        var linger: Boolean? = null
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("kill=") -> kill = parseBool(line.removePrefix("kill="))
                line.startsWith("Linger=") -> linger = parseBool(line.removePrefix("Linger="))
            }
        }
        return if (kill == true && linger == false) {
            AgentIssue(E_SYSTEMD_KILL, "KillUserProcesses=yes and Linger=no")
        } else {
            null
        }
    }

    /** `b true` (busctl), `yes`/`no` (loginctl, logind.conf) and the other systemd booleans. */
    private fun parseBool(value: String): Boolean? =
        when (value.trim().removePrefix("b ").trim().lowercase()) {
            "true", "yes", "1", "on" -> true
            "false", "no", "0", "off" -> false
            else -> null
        }

    /**
     * The user-facing explanation of [issue]: what happened and what to do, in
     * Spanish, with the code at the end so it can be looked up.
     */
    fun describe(issue: AgentIssue): String {
        if (issue.code == E_SYSTEMD_KILL) {
            return "Aviso del nivel 3: systemd cerrará el agente (y tmux) al salir de la última sesión, " +
                "porque KillUserProcesses=yes y tu usuario no tiene linger. " +
                "Actívalo con loginctl enable-linger. ($E_SYSTEMD_KILL)"
        }
        return render("Nivel 3 no disponible: ", issue)
    }

    /** [describe] for a mouse pad tab, which needs the agent to inject its input (ADR-0016). */
    fun describeMousepad(issue: AgentIssue): String = render("El mouse pad no está disponible: ", issue)

    private fun render(prefix: String, issue: AgentIssue): String {
        val (what, todo) = explain(issue)
        val unknown = todo == UNKNOWN_TODO
        val detail = issue.detail.takeIf { it.isNotBlank() && (unknown || issue.code in SHOWS_DETAIL) }
        return buildString {
            append(prefix).append(what).append(". ").append(todo)
            if (detail != null) append(" Detalle: ").append(detail)
            append(" (").append(issue.code).append(')')
        }
    }

    private fun explain(issue: AgentIssue): Pair<String, String> =
        when (issue.code) {
            E_UNSUPPORTED_TARGET ->
                "el sistema o la arquitectura del destino no tiene agente" to
                    "Hay agente para Linux, macOS, FreeBSD y Windows 10 1809 o posterior de 64 bits."
            E_NO_BINARY ->
                "no se pudo obtener el agente para este destino" to
                    "La app lo descarga del GitHub Release de su versión: comprueba la conexión a Internet " +
                    "del dispositivo y abre la sesión de nuevo."
            E_UPLOAD ->
                "no se pudo copiar el agente al destino" to
                    "Comprueba el espacio libre y los permisos de ~/.local/share/titan-ssh " +
                    "(%LOCALAPPDATA%\\titan-ssh en Windows)."
            E_CHECKSUM ->
                "el agente copiado no coincide con el esperado" to
                    "Suele ser un disco lleno o una copia cortada. Se reintenta al abrir la sesión de nuevo."
            E_NOEXEC ->
                "el destino no deja ejecutar el agente" to
                    "Suele ser un home montado con noexec, o AppLocker o WDAC en Windows. " +
                    "Quien administre el destino tiene que permitirlo."
            E_AGENT_EXIT ->
                "el agente terminó sin abrir la sesión" to
                    "Abre la sesión de nuevo; si se repite, revisa el detalle."
            E_STATE_DIR ->
                "el directorio de estado del agente no es seguro" to
                    "Tiene que ser tuyo y sin permisos para el grupo ni para otros: " +
                    "chmod 700 ~/.local/state/titan-ssh (%LOCALAPPDATA%\\titan-ssh en Windows)."
            E_LOCK ->
                "el sistema de ficheros del home no admite el candado del agente" to
                    "Pasa con algunos home en NFS. Quien administre el destino puede activar el bloqueo " +
                    "de ficheros, o apuntar XDG_STATE_HOME a un disco local."
            E_DAEMON_START ->
                "el agente no llegó a arrancar en el destino" to
                    "Abre la sesión de nuevo; si se repite, el destino puede estar limitando los procesos " +
                    "en segundo plano."
            E_AUTH ->
                "no se pudo autenticar con el agente del destino" to
                    "Abre la sesión de nuevo en unos segundos. Si se repite, detén el agente desde su " +
                    "panel (Ver el agente del destino). No borres agent.json: el agente lo necesita."
            E_AGENT_OUTDATED ->
                "en el destino sigue en marcha un agente muy antiguo, que esta versión ya no puede detener" to
                    "Ciérralo a mano en el destino (es el proceso agent-<versión>-… del usuario; el error dice su PID) " +
                    "o reinicia el destino: la próxima conexión arranca la versión actual."
            E_JOB_NO_BREAKAWAY ->
                "el servidor SSH de Windows cierra todos los procesos al acabar la sesión" to
                    "Actualiza el OpenSSH del destino (funciona con OpenSSH_for_Windows 10.0p2)."
            E_NO_CONPTY ->
                "el Windows del destino no tiene ConPTY" to
                    "Hace falta Windows 10 1809 o Windows Server 2019, o posterior."
            E_PTY ->
                "el agente no pudo abrir un terminal" to
                    "Comprueba que la shell de tu usuario en el destino arranca."
            E_INPUT_UNSUPPORTED ->
                "el destino todavía no admite el mouse pad" to
                    "Por ahora solo funciona con destinos Windows."
            E_NO_DESKTOP ->
                "no hay nadie con la sesión iniciada en el escritorio del destino" to
                    "Inicia sesión en Windows en ese PC (también vale por escritorio remoto) y reconecta."
            E_DESKTOP_TASK ->
                "no se pudo preparar la tarea programada que lanza el ayudante de escritorio" to
                    "Revisa el detalle. La tarea se puede quitar desde el panel del agente."
            else ->
                "el agente devolvió un error desconocido" to UNKNOWN_TODO
        }

    private const val UNKNOWN_TODO = "Revisa el detalle."

    /** The codes whose message depends on the agent's own words (unknown codes show it too). */
    private val SHOWS_DETAIL = setOf(E_AGENT_EXIT, E_PTY, E_UPLOAD, E_DAEMON_START, E_DESKTOP_TASK, E_NO_DESKTOP)

    /** A contract code: `E_` and upper-case words. */
    private val CODE = Regex("^E_[A-Z0-9_]+$")
}
