package io.github.danielperezmartinez.titanssh.keyinstall

import io.github.danielperezmartinez.titanssh.config.Host
import io.github.danielperezmartinez.titanssh.config.HostAuth
import io.github.danielperezmartinez.titanssh.config.HostKeyPolicy
import io.github.danielperezmartinez.titanssh.ssh.HostKeyInfo
import io.github.danielperezmartinez.titanssh.ssh.HostKeyVerifier
import io.github.danielperezmartinez.titanssh.ssh.InMemoryKnownHostsStore
import io.github.danielperezmartinez.titanssh.ssh.KnownHostEntry
import io.github.danielperezmartinez.titanssh.ssh.SftpEntry
import io.github.danielperezmartinez.titanssh.ssh.SshAuthFailed
import io.github.danielperezmartinez.titanssh.ssh.SshConnectFailed
import io.github.danielperezmartinez.titanssh.ssh.SshConnectionState
import io.github.danielperezmartinez.titanssh.ssh.SshConnector
import io.github.danielperezmartinez.titanssh.ssh.SshCredentials
import io.github.danielperezmartinez.titanssh.ssh.SshEndpoint
import io.github.danielperezmartinez.titanssh.ssh.SshExecChannel
import io.github.danielperezmartinez.titanssh.ssh.SshHop
import io.github.danielperezmartinez.titanssh.ssh.SshHostKeyRejected
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import io.github.danielperezmartinez.titanssh.ssh.SshSftp
import io.github.danielperezmartinez.titanssh.ssh.SshSftpFailed
import io.github.danielperezmartinez.titanssh.ssh.SshShell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

/**
 * The installation's flow and its catalog of errors (ADR-0030) against fake
 * servers: what the host answers is scripted, so Windows and jump hosts are
 * covered without one. The Unix script itself is tested in
 * [KeyInstallScriptsTest] and against a real sshd in [KeyInstallIntegrationTest].
 */
class KeyInstallerTest {

    private val line = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIKey titan-ssh"
    private val identity = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIKey"
    private val keyAuth = HostAuth.SoftwareKey(secretRef = "k")
    private val hostKey = HostKeyInfo("srv", 22, "ssh-ed25519", "SHA256:x", "AAAA")

    private fun host(policy: HostKeyPolicy = HostKeyPolicy.TOFU, jump: String? = null) = Host(
        id = "h", alias = "srv", hostname = "srv", username = "u", auth = HostAuth.Password("p"),
        hostKeyPolicy = policy, proxyJumpHostId = jump,
    )

    private val bastion = Host(id = "b", alias = "bastion", hostname = "bastion", username = "j", auth = HostAuth.Password("jp"))

    /** In-memory files over SFTP, at SFTP paths. */
    private class FakeSftp(val home: String, val files: MutableMap<String, String> = mutableMapOf()) : SshSftp {
        val dirs = mutableSetOf<String>()
        var denyWrites = false
        override suspend fun canonicalize(path: String) = home
        override suspend fun size(path: String) = files[path]?.length?.toLong()
        override suspend fun list(directory: String) = emptyList<String>()
        override suspend fun stat(path: String): SftpEntry? = when {
            path in dirs -> SftpEntry(path.substringAfterLast('/'), path, SftpEntry.Kind.DIRECTORY, null, null, null)
            path in files -> SftpEntry(path.substringAfterLast('/'), path, SftpEntry.Kind.FILE, null, null, null)
            else -> null
        }
        override suspend fun mkdirs(directory: String) { dirs += directory }
        override suspend fun write(path: String, bytes: ByteArray) {
            if (denyWrites) throw SshSftpFailed("Permission denied")
            files[path] = bytes.decodeToString()
        }
        override suspend fun read(path: String) = files[path]?.encodeToByteArray() ?: throw SshSftpFailed("No such file")
        override suspend fun chmod(path: String, mode: Int) {}
        override suspend fun rename(from: String, to: String) {}
        override suspend fun remove(path: String) {}
        override suspend fun close() {}
    }

    private class Exec(val out: String, val err: String = "", val status: Int? = 0) : SshExecChannel {
        override val output: Flow<ByteArray> = flowOf(out.encodeToByteArray())
        override val errors: Flow<ByteArray> = if (err.isEmpty()) emptyFlow() else flowOf(err.encodeToByteArray())
        override suspend fun send(data: ByteArray) {}
        override suspend fun close(): Int? = status
    }

    private class FakeSession(val sftp: SshSftp?, val answer: (String) -> Exec) : SshSession {
        val commands = mutableListOf<String>()
        override val state = MutableStateFlow(SshConnectionState.CONNECTED).asStateFlow()
        override suspend fun openShell(columns: Int, rows: Int): SshShell = error("no shell")
        override suspend fun exec(command: String): SshExecChannel { commands += command; return answer(command) }
        override suspend fun openSftp(): SshSftp? = sftp
        override suspend fun close() {}
    }

    /** Answers each connection in turn: a session, or a failure to throw. */
    private class FakeConnector(vararg plan: Any) : SshConnector {
        private val plan = plan.toMutableList()
        val credentials = mutableListOf<SshCredentials>()
        var verifyWith: HostKeyInfo? = null
        override suspend fun connect(
            endpoint: SshEndpoint,
            credentials: SshCredentials,
            hostKeyVerifier: HostKeyVerifier,
            keepAliveSeconds: Int,
            via: List<SshHop>,
        ): SshSession {
            this.credentials += credentials
            verifyWith?.let { if (!hostKeyVerifier.verify(it)) throw SshHostKeyRejected("rejected") }
            return when (val next = plan.removeFirst()) {
                is Throwable -> throw next
                else -> next as SshSession
            }
        }
    }

    private fun installer(connector: SshConnector, store: InMemoryKnownHostsStore = InMemoryKnownHostsStore()) =
        KeyInstaller(connector, store) { SshCredentials.Password("jump".toCharArray()) }

    private fun install(
        connector: SshConnector,
        host: Host = host(),
        jumps: List<Host> = emptyList(),
        store: InMemoryKnownHostsStore = InMemoryKnownHostsStore(),
        answer: Boolean = true,
    ): KeyInstallResult = runBlocking {
        installer(connector, store).install(
            host, jumps, "pw".toCharArray(), line, keyAuth,
            askHostKey = { if (answer) it.accept() else it.reject() },
        )
    }

    private fun unix(done: String = "TITAN-KEY DONE ADDED\n") = FakeSession(FakeSftp("/home/u")) { Exec(done) }

    @Test
    fun unix_install_runs_the_script_and_then_logs_in_with_the_key() {
        val connector = FakeConnector(unix(), unix())
        val result = install(connector)
        assertEquals(KeyInstallResult.Installed(false, "Unix", "~/.ssh/authorized_keys"), result)
        assertIs<SshCredentials.Password>(connector.credentials[0])
        // The second connection is the check: with the key's credentials, not the password.
        assertEquals(2, connector.credentials.size)
    }

    @Test
    fun the_password_is_copied_and_wiped_never_the_callers_array() = runBlocking {
        val connector = FakeConnector(unix(), unix())
        val password = "pw".toCharArray()
        installer(connector).install(host(), emptyList(), password, line, keyAuth, askHostKey = { it.accept() })
        assertEquals("pw", String(password))
        assertEquals("  ", String((connector.credentials[0] as SshCredentials.Password).password))
    }

    @Test
    fun a_new_host_key_asks_and_is_remembered() {
        val store = InMemoryKnownHostsStore()
        val connector = FakeConnector(unix(), unix()).apply { verifyWith = hostKey }
        assertIs<KeyInstallResult.Installed>(install(connector, store = store))
        assertEquals(1, runBlocking { store.entriesFor("srv", 22) }.size)
    }

    @Test
    fun a_declined_or_changed_host_key_stops_before_the_password() {
        val declined = FakeConnector(unix()).apply { verifyWith = hostKey }
        val d = install(declined, answer = false) as KeyInstallResult.Failed
        assertEquals(KeyInstallProblem.HOST_KEY_DECLINED, d.problem)
        assertEquals(false, d.problem.passwordSent)

        val changed = FakeConnector(unix()).apply { verifyWith = hostKey }
        val store = InMemoryKnownHostsStore(listOf(KnownHostEntry("srv", 22, "ssh-ed25519", "BBBB")))
        assertEquals(KeyInstallProblem.HOST_KEY_CHANGED, (install(changed, store = store) as KeyInstallResult.Failed).problem)

        val strict = FakeConnector(unix()).apply { verifyWith = hostKey }
        val s = install(strict, host = host(HostKeyPolicy.STRICT)) as KeyInstallResult.Failed
        assertEquals(KeyInstallProblem.HOST_KEY_NOT_TRUSTED, s.problem)
    }

    private fun refusedWith(e: Throwable, jumps: List<Host> = emptyList(), host: Host = host()) =
        (install(FakeConnector(e), host = host, jumps = jumps) as KeyInstallResult.Failed)

    @Test
    fun password_refusals_are_told_apart() {
        val noPassword = refusedWith(SshAuthFailed("x", host = "srv", allowedMethods = listOf("publickey")))
        assertEquals(KeyInstallProblem.PASSWORD_NOT_OFFERED, noPassword.problem)
        assertEquals("El servidor ofrece: publickey", noPassword.detail)
        assertEquals(
            KeyInstallProblem.WRONG_PASSWORD,
            refusedWith(SshAuthFailed("x", host = "srv", allowedMethods = listOf("publickey", "password"))).problem,
        )
        val otp = refusedWith(SshAuthFailed("x", host = "srv", unansweredPrompts = listOf("Verification code:")))
        assertEquals(KeyInstallProblem.SECOND_FACTOR, otp.problem)
        assertEquals("Pregunta: Verification code:", otp.detail)
        assertEquals(KeyInstallProblem.SECOND_FACTOR, refusedWith(SshAuthFailed("x", host = "srv", partialSuccess = true)).problem)
        assertEquals(
            KeyInstallProblem.PASSWORD_EXPIRED,
            refusedWith(SshAuthFailed("x", host = "srv", passwordChangeRequested = true)).problem,
        )
        assertEquals(
            KeyInstallProblem.TOO_MANY_ATTEMPTS,
            refusedWith(SshAuthFailed("x", Exception("Too many authentication failures"), host = "srv")).problem,
        )
        assertEquals(KeyInstallProblem.UNREACHABLE, refusedWith(SshConnectFailed("x", Exception("Connection refused"), "srv")).problem)
    }

    @Test
    fun jump_host_failures_name_the_jump() {
        val jumps = listOf(bastion)
        val auth = refusedWith(SshAuthFailed("x", host = "bastion"), jumps, host(jump = "b"))
        assertEquals(KeyInstallProblem.JUMP_AUTH_FAILED, auth.problem)
        assertEquals("Salto bastion", auth.detail)
        val down = refusedWith(SshConnectFailed("x", Exception("timeout"), "bastion"), jumps, host(jump = "b"))
        assertEquals(KeyInstallProblem.JUMP_UNREACHABLE, down.problem)
        assertEquals(KeyInstallProblem.JUMP_UNREACHABLE, refusedWith(SshConnectFailed("x", null, "srv"), jumps, host(jump = "b")).problem)
    }

    @Test
    fun the_key_check_says_why_the_copied_key_does_not_log_in() {
        fun verifyFails(e: Throwable, done: String = "TITAN-KEY DONE ADDED\n") =
            (install(FakeConnector(unix(done), e)) as KeyInstallResult.Failed).also { assertEquals(KeyInstallStep.VERIFY, it.step) }

        assertEquals(KeyInstallProblem.PUBKEY_DISABLED, verifyFails(SshAuthFailed("x", host = "srv", allowedMethods = listOf("password"))).problem)
        assertEquals(
            KeyInstallProblem.STRICT_MODES_HOME,
            verifyFails(SshAuthFailed("x", host = "srv", allowedMethods = listOf("publickey")), "TITAN-KEY WARN HOME_WRITABLE\nTITAN-KEY DONE ADDED\n").problem,
        )
        assertEquals(KeyInstallProblem.KEY_REJECTED, verifyFails(SshAuthFailed("x", host = "srv", allowedMethods = listOf("publickey"))).problem)
        assertEquals(KeyInstallProblem.KEY_REJECTED, verifyFails(SshAuthFailed("x", host = "srv", partialSuccess = true)).problem)
    }

    @Test
    fun a_failure_the_script_reports_stops_at_the_write() {
        val r = install(FakeConnector(unix("TITAN-KEY FAIL SSH_DIR_NOT_OWNED\n"))) as KeyInstallResult.Failed
        assertEquals(KeyInstallStep.WRITE, r.step)
        assertEquals(KeyInstallProblem.SSH_DIR_NOT_OWNED, r.problem)
    }

    @Test
    fun an_account_without_a_shell_gets_the_key_over_sftp() {
        val sftp = FakeSftp("/home/u")
        val noShell = FakeSession(sftp) { Exec("This account is currently not available.\n", status = 1) }
        val result = install(FakeConnector(noShell, unix()))
        assertEquals(KeyInstallResult.Installed(false, "Unix (SFTP)", "~/.ssh/authorized_keys"), result)
        assertEquals("$line\n", sftp.files["/home/u/.ssh/authorized_keys"])

        val nothing = FakeSession(null) { Exec("") }
        assertEquals(KeyInstallProblem.NO_SHELL, (install(FakeConnector(nothing)) as KeyInstallResult.Failed).problem)
    }

    private fun windows(sftp: FakeSftp, admin: Boolean, aclStatus: Int = 0) = FakeSession(sftp) { command ->
        when {
            command == "whoami /groups" -> Exec(if (admin) "BUILTIN\\Administrators Alias S-1-5-32-544" else "BUILTIN\\Users Alias S-1-5-32-545")
            command.startsWith("cmd /c echo") -> Exec("C:\\ProgramData\r\n")
            command.startsWith("icacls") -> Exec("processed file", status = aclStatus)
            else -> Exec("", status = 1)
        }
    }

    @Test
    fun windows_user_key_goes_to_the_profile() {
        val sftp = FakeSftp("/C:/Users/u")
        val session = windows(sftp, admin = false)
        val result = install(FakeConnector(session, unix()))
        assertEquals(KeyInstallResult.Installed(false, "Windows", "%USERPROFILE%\\.ssh\\authorized_keys"), result)
        assertEquals("$line\n", sftp.files["/C:/Users/u/.ssh/authorized_keys"])
        assertTrue(session.commands.none { it.startsWith("icacls") })
    }

    @Test
    fun windows_administrator_key_goes_to_programdata_with_its_acl() {
        val sftp = FakeSftp("/C:/Users/u", mutableMapOf("/C:/ProgramData/ssh/administrators_authorized_keys" to "$identity old\r\n"))
        val session = windows(sftp, admin = true)
        val result = install(FakeConnector(session, unix()))
        assertEquals(
            KeyInstallResult.Installed(true, "Windows (administrador)", "C:\\ProgramData\\ssh\\administrators_authorized_keys"),
            result,
        )
        assertTrue(session.commands.any { it.startsWith("icacls \"C:\\ProgramData\\ssh\\administrators_authorized_keys\"") })
    }

    @Test
    fun windows_administrator_whose_sshd_config_drops_the_rule_uses_the_profile() {
        val sftp = FakeSftp("/C:/Users/u", mutableMapOf("/C:/ProgramData/ssh/sshd_config" to "PubkeyAuthentication yes\n"))
        val result = install(FakeConnector(windows(sftp, admin = true), unix()))
        assertEquals("Windows", (result as KeyInstallResult.Installed).system)
    }

    @Test
    fun windows_administrator_write_and_acl_failures() {
        val denied = FakeSftp("/C:/Users/u").apply { denyWrites = true }
        assertEquals(
            KeyInstallProblem.WINDOWS_ADMIN_DENIED,
            (install(FakeConnector(windows(denied, admin = true))) as KeyInstallResult.Failed).problem,
        )
        val acl = install(FakeConnector(windows(FakeSftp("/C:/Users/u"), admin = true, aclStatus = 5))) as KeyInstallResult.Failed
        assertEquals(KeyInstallProblem.WINDOWS_ACL_FAILED, acl.problem)
        assertEquals("processed file", acl.detail)
    }
}
