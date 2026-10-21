package io.github.danielperezmartinez.titanssh.keyinstall

import io.github.danielperezmartinez.titanssh.config.Host
import io.github.danielperezmartinez.titanssh.config.HostAuth
import io.github.danielperezmartinez.titanssh.config.HostKeyPolicy
import io.github.danielperezmartinez.titanssh.config.jumpEndpoint
import io.github.danielperezmartinez.titanssh.ssh.HostKeyInfo
import io.github.danielperezmartinez.titanssh.ssh.HostKeyRejection
import io.github.danielperezmartinez.titanssh.ssh.HostKeyVerifier
import io.github.danielperezmartinez.titanssh.ssh.KnownHostsStore
import io.github.danielperezmartinez.titanssh.ssh.KnownHostsVerifier
import io.github.danielperezmartinez.titanssh.ssh.SshAuthFailed
import io.github.danielperezmartinez.titanssh.ssh.SshConnectFailed
import io.github.danielperezmartinez.titanssh.ssh.SshConnector
import io.github.danielperezmartinez.titanssh.ssh.SshCredentials
import io.github.danielperezmartinez.titanssh.ssh.SshEndpoint
import io.github.danielperezmartinez.titanssh.ssh.SshException
import io.github.danielperezmartinez.titanssh.ssh.SshHardwareKeyUnavailable
import io.github.danielperezmartinez.titanssh.ssh.SshHop
import io.github.danielperezmartinez.titanssh.ssh.SshHostKeyRejected
import io.github.danielperezmartinez.titanssh.ssh.SshNoCommonAlgorithm
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import io.github.danielperezmartinez.titanssh.ssh.SshSftp
import io.github.danielperezmartinez.titanssh.ssh.authorizedKeyIdentity
import io.github.danielperezmartinez.titanssh.ssh.trust
import io.github.danielperezmartinez.titanssh.terminal.AgentInstall
import io.github.danielperezmartinez.titanssh.terminal.CredentialResolver
import io.github.danielperezmartinez.titanssh.terminal.wipe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** The steps of an installation, in order, as the editor lists them. */
enum class KeyInstallStep(val label: String) {
    KEY("Preparar la clave"),
    CONNECT("Conectar con la contraseña"),
    DETECT("Reconocer el sistema del host"),
    WRITE("Añadir la clave a authorized_keys"),
    VERIFY("Entrar solo con la clave"),
    SAVE("Guardar el host con la clave"),
}

/**
 * A host key seen for the first time during an installation, waiting for the
 * user (TOFU, ADR-0005). [jumpHost] names the jump host it belongs to, `null`
 * for the destination. The connection waits until [accept] or [reject].
 */
class HostKeyQuestion internal constructor(
    val info: HostKeyInfo,
    val jumpHost: String?,
    private val answer: CompletableDeferred<Boolean>,
) {
    fun accept() { answer.complete(true) }
    fun reject() { answer.complete(false) }
}

/** Where a step is. */
enum class StepState { RUNNING, DONE, FAILED }

/** How an installation ended. */
sealed interface KeyInstallResult {
    /**
     * The key is on the host and logs in. [file] is where it went, [system]
     * what the host is (`Unix`, `Windows`), and [notes] what the user should
     * know although it worked.
     */
    data class Installed(
        val alreadyPresent: Boolean,
        val system: String,
        val file: String,
        val notes: List<String> = emptyList(),
    ) : KeyInstallResult

    /** [problem] stopped it at [step]; [detail] is what the server or the host said. */
    data class Failed(val step: KeyInstallStep, val problem: KeyInstallProblem, val detail: String? = null) :
        KeyInstallResult
}

/**
 * Installs a public key on a host, as `ssh-copy-id` does, and checks that it
 * logs in (ADR-0030): connect with a password used for this only, put the line
 * in the file the host's sshd reads, then connect again with the key alone.
 * It never changes the configuration: the caller switches the host to the key
 * once [install] returns [KeyInstallResult.Installed].
 *
 * Trust in the server works as for any connection: each jump host and the
 * destination are checked under their own known_hosts policy, a new key asks
 * the user through a [HostKeyQuestion], and a changed one stops everything
 * before the password is sent.
 */
class KeyInstaller(
    private val connector: SshConnector,
    private val knownHostsStore: KnownHostsStore,
    /** Live credentials of a jump host's own auth (the SecretStore). */
    private val credentials: suspend (HostAuth) -> SshCredentials,
) {

    /**
     * Installs [publicLine] for [host]'s user, reaching it through [jumps]
     * (first hop first). [password] is copied for each attempt and never kept;
     * the caller wipes its own array. [keyAuth] is the key's auth as the host
     * will hold it, used to check the key logs in.
     *
     * @param askHostKey shows the user a host key seen for the first time; the
     *   question stays open until answered, even past a handshake that gave up.
     */
    suspend fun install(
        host: Host,
        jumps: List<Host>,
        password: CharArray,
        publicLine: String,
        keyAuth: HostAuth,
        askHostKey: (HostKeyQuestion) -> Unit,
        onStep: (KeyInstallStep, StepState) -> Unit = { _, _ -> },
    ): KeyInstallResult {
        val identity = authorizedKeyIdentity(publicLine)
            ?: return KeyInstallResult.Failed(KeyInstallStep.KEY, KeyInstallProblem.UNEXPECTED, "Not a public key line")
        val endpoint = SshEndpoint(host.hostname, host.port, host.username)
        var step = KeyInstallStep.CONNECT
        fun fail(problem: KeyInstallProblem, detail: String? = null): KeyInstallResult {
            onStep(step, StepState.FAILED)
            return KeyInstallResult.Failed(step, problem, detail)
        }

        onStep(step, StepState.RUNNING)
        val session = try {
            connect(endpoint, host, jumps, askHostKey) { SshCredentials.Password(password.copyOf()) }
        } catch (e: Refused) {
            return fail(e.problem, e.detail)
        }
        var written: Written
        try {
            onStep(step, StepState.DONE)
            step = KeyInstallStep.DETECT
            onStep(step, StepState.RUNNING)
            val sftp = runCatching { session.openSftp() }.getOrNull()
            try {
                val home = sftp?.let { runCatching { it.canonicalize(".") }.getOrNull() }
                val windows = home != null && AgentInstall.isWindowsSftpPath(home)
                onStep(step, StepState.DONE)
                step = KeyInstallStep.WRITE
                onStep(step, StepState.RUNNING)
                written = if (windows) {
                    writeWindows(session, sftp, home, publicLine, identity)
                } else {
                    writeUnix(session, sftp, home, publicLine, identity)
                }
            } finally {
                runCatching { sftp?.close() }
            }
        } catch (e: Refused) {
            return fail(e.problem, e.detail)
        } catch (e: SshException) {
            return fail(KeyInstallProblem.CONNECTION_LOST, e.message)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            return fail(KeyInstallProblem.CONNECTION_LOST, "The host stopped answering")
        } finally {
            runCatching { session.close() }
        }
        onStep(step, StepState.DONE)

        step = KeyInstallStep.VERIFY
        onStep(step, StepState.RUNNING)
        try {
            connect(endpoint, host, jumps, askHostKey) { credentials(keyAuth) }.close()
        } catch (e: Refused) {
            // An auth refusal is read in password terms; here it was the key's.
            if (e.problem !in PASSWORD_REFUSALS) return fail(e.problem, e.detail)
            return when {
                e.allowedMethods.isNotEmpty() && "publickey" !in e.allowedMethods -> fail(KeyInstallProblem.PUBKEY_DISABLED)
                e.partialSuccess -> fail(
                    KeyInstallProblem.KEY_REJECTED,
                    "El servidor acepta la clave, pero exige además otro método (AuthenticationMethods).",
                )
                written.homeWritable -> fail(KeyInstallProblem.STRICT_MODES_HOME)
                else -> fail(KeyInstallProblem.KEY_REJECTED)
            }
        }
        onStep(step, StepState.DONE)
        val notes = buildList {
            if (written.homeWritable) {
                add("El grupo u otros pueden escribir en tu carpeta personal del host. Este servidor lo admite, pero conviene chmod go-w ~.")
            }
        }
        return KeyInstallResult.Installed(written.alreadyPresent, written.system, written.file, notes)
    }

    /** A step's outcome on the host. */
    private class Written(val alreadyPresent: Boolean, val system: String, val file: String, val homeWritable: Boolean = false)

    /** A connection that did not get through, already in catalog terms. */
    private class Refused(
        val problem: KeyInstallProblem,
        val detail: String? = null,
        val allowedMethods: List<String> = emptyList(),
        val partialSuccess: Boolean = false,
    ) : Exception(problem.title)

    // --- Connecting --------------------------------------------------------

    /**
     * Connects to [endpoint] with [targetCredentials] through [jumps], asking
     * about new host keys. A prompt answered after the handshake gave up (the
     * user took longer than sshj or the server waits) trusts the key and
     * connects once more, as a session tab does.
     */
    private suspend fun connect(
        endpoint: SshEndpoint,
        host: Host,
        jumps: List<Host>,
        askHostKey: (HostKeyQuestion) -> Unit,
        targetCredentials: suspend () -> SshCredentials,
    ): SshSession {
        repeat(2) {
            var lastPrompted: Trust? = null
            val jumpTrusts = jumps.map { jump ->
                Trust(jump.hostKeyPolicy, jump.alias.ifBlank { jump.hostname }, askHostKey) { lastPrompted = it }
            }
            val trust = Trust(host.hostKeyPolicy, null, askHostKey) { lastPrompted = it }
            val creds = mutableListOf<SshCredentials>()
            try {
                try {
                    jumps.forEach { creds += credentials(it.auth) }
                } catch (e: CredentialResolver.MissingSecret) {
                    throw Refused(KeyInstallProblem.JUMP_CREDENTIALS_MISSING, e.message)
                }
                creds += try {
                    targetCredentials()
                } catch (e: CredentialResolver.MissingSecret) {
                    throw Refused(KeyInstallProblem.KEY_UNAVAILABLE, e.message)
                }
                return connector.connect(
                    endpoint = endpoint,
                    credentials = creds.last(),
                    hostKeyVerifier = trust.verifier,
                    keepAliveSeconds = host.keepAliveSeconds,
                    via = jumps.mapIndexed { i, jump ->
                        SshHop(jump.jumpEndpoint, creds[i], jumpTrusts[i].verifier, jump.keepAliveSeconds)
                    },
                )
            } catch (_: SshHostKeyRejected) {
                val rejected = (jumpTrusts + trust).firstOrNull { it.rejection != null }
                throw hostKeyRefusal(rejected)
            } catch (e: SshConnectFailed) {
                val pending = lastPrompted
                if (pending == null || !pending.prompted.isCompleted) throw unreachable(e, endpoint, jumps)
                if (!pending.answer.await()) throw Refused(KeyInstallProblem.HOST_KEY_DECLINED, pending.name)
                pending.verified.await()
                knownHostsStore.trust(pending.prompted.await())
                // and try again, now with the key trusted
            } catch (e: SshNoCommonAlgorithm) {
                throw Refused(KeyInstallProblem.NO_COMMON_ALGORITHM, e.message)
            } catch (e: SshHardwareKeyUnavailable) {
                throw Refused(KeyInstallProblem.KEY_UNAVAILABLE, e.message)
            } catch (e: SshAuthFailed) {
                throw authRefusal(e, endpoint, jumps)
            } finally {
                creds.forEach { it.wipe() }
            }
        }
        throw Refused(KeyInstallProblem.UNREACHABLE, "The host key was trusted, but the connection failed again")
    }

    /** Trust in one host's key during one attempt, with what happened to it. */
    private inner class Trust(
        policy: HostKeyPolicy,
        /** The jump host's name, or null for the destination. */
        val name: String?,
        askHostKey: (HostKeyQuestion) -> Unit,
        onPrompt: (Trust) -> Unit,
    ) {
        val prompted = CompletableDeferred<HostKeyInfo>()
        val answer = CompletableDeferred<Boolean>()
        val verified = CompletableDeferred<Unit>()
        var rejection: HostKeyRejection? = null
            private set

        private val knownHosts = KnownHostsVerifier(
            store = knownHostsStore,
            acceptNewHosts = policy == HostKeyPolicy.TOFU,
            onRejected = { rejection = it },
        ) { info ->
            prompted.complete(info)
            onPrompt(this)
            // The answer outlives a verifier that sshj interrupts meanwhile.
            askHostKey(HostKeyQuestion(info, name, answer))
            answer.await()
        }

        val verifier = object : HostKeyVerifier {
            override suspend fun verify(info: HostKeyInfo): Boolean =
                try {
                    knownHosts.verify(info)
                } finally {
                    verified.complete(Unit)
                }

            override suspend fun knownKeyTypes(host: String, port: Int): List<String> =
                knownHosts.knownKeyTypes(host, port)
        }
    }

    private fun hostKeyRefusal(trust: Trust?): Refused {
        val who = trust?.name?.let { "Salto $it" }
        return when (trust?.rejection) {
            is HostKeyRejection.Changed -> Refused(KeyInstallProblem.HOST_KEY_CHANGED, who)
            is HostKeyRejection.NotTrusted -> Refused(KeyInstallProblem.HOST_KEY_NOT_TRUSTED, who)
            is HostKeyRejection.Declined, null -> Refused(KeyInstallProblem.HOST_KEY_DECLINED, who)
        }
    }

    private fun unreachable(e: SshConnectFailed, endpoint: SshEndpoint, jumps: List<Host>): Refused {
        if (jumps.isEmpty()) return Refused(KeyInstallProblem.UNREACHABLE, rootMessage(e))
        val jump = jumps.firstOrNull { it.hostname == e.host }
        return if (jump != null || e.host == endpoint.host) {
            Refused(KeyInstallProblem.JUMP_UNREACHABLE, (jump?.let { "Salto ${it.alias.ifBlank { it.hostname }}: " } ?: "") + rootMessage(e))
        } else {
            Refused(KeyInstallProblem.UNREACHABLE, rootMessage(e))
        }
    }

    private fun authRefusal(e: SshAuthFailed, endpoint: SshEndpoint, jumps: List<Host>): Refused {
        val jump = jumps.firstOrNull { it.hostname == e.host && e.host != endpoint.host }
        if (jump != null) return Refused(KeyInstallProblem.JUMP_AUTH_FAILED, "Salto ${jump.alias.ifBlank { jump.hostname }}")
        val methods = e.allowedMethods
        val offered = methods.joinToString(", ").ifEmpty { null }?.let { "El servidor ofrece: $it" }
        val problem = when {
            e.passwordChangeRequested -> KeyInstallProblem.PASSWORD_EXPIRED
            e.partialSuccess || e.unansweredPrompts.isNotEmpty() -> KeyInstallProblem.SECOND_FACTOR
            tooManyAttempts(e) -> KeyInstallProblem.TOO_MANY_ATTEMPTS
            methods.isNotEmpty() && "password" !in methods && "keyboard-interactive" !in methods ->
                KeyInstallProblem.PASSWORD_NOT_OFFERED
            else -> KeyInstallProblem.WRONG_PASSWORD
        }
        val detail = when (problem) {
            KeyInstallProblem.SECOND_FACTOR -> e.unansweredPrompts.joinToString(" · ").ifEmpty { null }
                ?.let { "Pregunta: $it" } ?: offered
            KeyInstallProblem.PASSWORD_NOT_OFFERED -> offered
            else -> null
        }
        return Refused(problem, detail, methods, e.partialSuccess)
    }

    private fun tooManyAttempts(e: Throwable): Boolean =
        generateSequence(e as Throwable?) { it.cause }.any { it.message?.contains("too many", ignoreCase = true) == true }

    private fun rootMessage(e: Throwable): String? =
        generateSequence(e) { it.cause }.last().message ?: e.message

    // --- Writing -----------------------------------------------------------

    /**
     * Unix: the [KeyInstallScripts.unix] script on `sh -s`, or, for an account
     * that runs no commands, the same steps over SFTP.
     */
    private suspend fun writeUnix(
        session: SshSession,
        sftp: SshSftp?,
        home: String?,
        publicLine: String,
        identity: String,
    ): Written {
        val ran = runCatching { run(session, "sh -s", KeyInstallScripts.unix(publicLine, identity)) }.getOrNull()
        val outcome = ran?.let { KeyInstallScripts.parseUnix(it.stdout, it.stderr) } ?: KeyInstallScripts.UnixOutcome.NotRun
        return when (outcome) {
            is KeyInstallScripts.UnixOutcome.Done ->
                Written(outcome.alreadyPresent, "Unix", "~/.ssh/authorized_keys", outcome.homeWritable)
            is KeyInstallScripts.UnixOutcome.Failed -> throw Refused(outcome.problem, outcome.detail)
            KeyInstallScripts.UnixOutcome.NotRun -> {
                if (sftp == null || home == null) throw Refused(KeyInstallProblem.NO_SHELL)
                writeOverSftp(sftp, "${home.trimEnd('/')}/.ssh", publicLine, identity, unix = true)
                    .let { Written(it, "Unix (SFTP)", "~/.ssh/authorized_keys") }
            }
        }
    }

    /**
     * Windows (Win32-OpenSSH): an administrator's key goes to
     * `%ProgramData%\ssh\administrators_authorized_keys` with its strict ACL,
     * unless the host's `sshd_config` dropped that rule; anyone else's to
     * `%USERPROFILE%\.ssh\authorized_keys`. Written over SFTP, never by a
     * shell redirection, which would change the file's encoding.
     */
    private suspend fun writeWindows(
        session: SshSession,
        sftp: SshSftp,
        home: String,
        publicLine: String,
        identity: String,
    ): Written {
        val admin = KeyInstallScripts.isWindowsAdministrator(run(session, "whoami /groups").stdout)
        val programData = run(session, "cmd /c echo %ProgramData%").stdout.trim()
            .takeIf { it.isNotEmpty() && '%' !in it } ?: "C:\\ProgramData"
        val sshDir = AgentInstall.windowsToSftpPath("$programData\\ssh")
        val sshdConfig = runCatching { sftp.read("$sshDir/sshd_config").decodeToString() }.getOrNull()
        if (admin && KeyInstallScripts.windowsUsesAdministratorsFile(sshdConfig)) {
            val file = "$sshDir/administrators_authorized_keys"
            val existing = try {
                if (sftp.stat(file) != null) sftp.read(file).decodeToString() else ""
            } catch (e: SshException) {
                throw Refused(KeyInstallProblem.WINDOWS_ADMIN_DENIED, e.message)
            }
            val updated = KeyInstallScripts.appendedAuthorizedKeys(existing, publicLine, identity)
            if (updated != null) {
                try {
                    sftp.write(file, updated.encodeToByteArray())
                } catch (e: SshException) {
                    throw Refused(KeyInstallProblem.WINDOWS_ADMIN_DENIED, e.message)
                }
            }
            val windowsPath = "$programData\\ssh\\administrators_authorized_keys"
            val acl = run(session, KeyInstallScripts.windowsAdministratorsAcl(windowsPath))
            if (acl.exitStatus != 0) {
                throw Refused(KeyInstallProblem.WINDOWS_ACL_FAILED, (acl.stderr + acl.stdout).trim().ifEmpty { null })
            }
            return Written(updated == null, "Windows (administrador)", windowsPath)
        }
        val present = writeOverSftp(sftp, "${home.trimEnd('/')}/.ssh", publicLine, identity, unix = false)
        return Written(present, "Windows", "%USERPROFILE%\\.ssh\\authorized_keys")
    }

    /**
     * Puts [publicLine] in `<sshDir>/authorized_keys` over SFTP, creating the
     * folder; on Unix with the modes `StrictModes` wants. Whether it was
     * already there.
     */
    private suspend fun writeOverSftp(sftp: SshSftp, sshDir: String, publicLine: String, identity: String, unix: Boolean): Boolean {
        val file = "$sshDir/authorized_keys"
        try {
            when (val dir = sftp.stat(sshDir)) {
                null -> sftp.mkdirs(sshDir)
                else -> if (!dir.isDirectory) throw Refused(KeyInstallProblem.SSH_DIR_NOT_DIR)
            }
            if (unix) runCatching { sftp.chmod(sshDir, MODE_0700) }
            val existing = if (sftp.stat(file) != null) sftp.read(file).decodeToString() else ""
            val updated = KeyInstallScripts.appendedAuthorizedKeys(existing, publicLine, identity)
            if (updated != null) sftp.write(file, updated.encodeToByteArray())
            if (unix) runCatching { sftp.chmod(file, MODE_0600) }
            return updated == null
        } catch (e: Refused) {
            throw e
        } catch (e: SshException) {
            val message = e.message.orEmpty()
            throw Refused(KeyInstallScripts.writeProblem(message), message.ifEmpty { null })
        }
    }

    private class Ran(val stdout: String, val stderr: String, val exitStatus: Int?)

    /** Runs [command] with [stdin], collecting both outputs. */
    private suspend fun run(session: SshSession, command: String, stdin: String? = null): Ran =
        withTimeout(COMMAND_TIMEOUT_MILLIS) {
            coroutineScope {
                val channel = session.exec(command)
                val err = async { channel.errors.fold(StringBuilder()) { acc, chunk -> acc.append(chunk.decodeToString()) } }
                if (stdin != null) launch { channel.send(stdin.encodeToByteArray()) }
                val out = channel.output.fold(StringBuilder()) { acc, chunk -> acc.append(chunk.decodeToString()) }
                val status = channel.close()
                Ran(out.toString(), err.await().toString(), status)
            }
        }

    private companion object {
        /** What [authRefusal] can make of a refused authentication. */
        val PASSWORD_REFUSALS = setOf(
            KeyInstallProblem.WRONG_PASSWORD,
            KeyInstallProblem.PASSWORD_NOT_OFFERED,
            KeyInstallProblem.SECOND_FACTOR,
            KeyInstallProblem.PASSWORD_EXPIRED,
        )
        const val MODE_0700 = 0x1C0
        const val MODE_0600 = 0x180
        const val COMMAND_TIMEOUT_MILLIS = 60_000L
    }
}
