package im.gar.titanssh.config

import im.gar.titanssh.secret.AuthMethod
import im.gar.titanssh.secret.KeyStorage
import im.gar.titanssh.secret.SshKeyType
import im.gar.titanssh.secret.byPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Pure-logic tests for the config model, resolution and the controller. */
class ConfigModelTest {

    private fun host(id: String, groupId: String? = null, proxy: String? = null) = Host(
        id = id,
        alias = "alias-$id",
        hostname = "$id.example.net",
        port = 22,
        username = "root",
        auth = HostAuth.HardwareKey(alias = "hw-$id"),
        groupId = groupId,
        proxyJumpHostId = proxy,
    )

    @Test
    fun resolve_applies_session_overrides_over_host_defaults() {
        val h = host("h1")
        val s = Session(id = "s1", name = "sess", hostId = "h1", usernameOverride = "deploy", portOverride = 2222)
        val cfg = TitanConfig(hosts = listOf(h), sessions = listOf(s))

        val resolved = cfg.resolve(s)

        assertEquals("h1.example.net", resolved.endpoint.host)
        assertEquals(2222, resolved.endpoint.port)
        assertEquals("deploy", resolved.endpoint.username)
        assertNull(resolved.proxyJump)
    }

    @Test
    fun resolve_uses_host_defaults_when_session_does_not_override() {
        val h = host("h1")
        val s = Session(id = "s1", name = "sess", hostId = "h1")
        val cfg = TitanConfig(hosts = listOf(h), sessions = listOf(s))

        val resolved = cfg.resolve(s)

        assertEquals(22, resolved.endpoint.port)
        assertEquals("root", resolved.endpoint.username)
    }

    @Test
    fun resolve_finds_proxy_jump_host() {
        val bastion = host("bastion")
        val h = host("h1", proxy = "bastion")
        val s = Session(id = "s1", name = "sess", hostId = "h1")
        val cfg = TitanConfig(hosts = listOf(bastion, h), sessions = listOf(s))

        assertEquals("bastion", cfg.resolve(s).proxyJump?.id)
    }

    @Test
    fun resolve_throws_for_missing_host() {
        val s = Session(id = "s1", name = "orphan", hostId = "ghost")
        val cfg = TitanConfig(sessions = listOf(s))
        assertFailsWith<ConfigResolutionException> { cfg.resolve(s) }
    }

    @Test
    fun appearance_merges_session_over_host_over_default() {
        val default = TerminalAppearance(fontFamily = "JetBrains Mono", fontSize = 13, colorTheme = "ansi")
        val hostAp = TerminalAppearance(fontSize = 15)
        val sessionAp = TerminalAppearance(colorTheme = "solarized")

        val merged = default.mergedWith(hostAp).mergedWith(sessionAp)

        assertEquals("JetBrains Mono", merged.fontFamily)
        assertEquals(15, merged.fontSize)
        assertEquals("solarized", merged.colorTheme)
    }

    @Test
    fun auth_maps_to_runtime_method_with_preference() {
        val pwd = HostAuth.Password("ref.pwd").toAuthMethod()
        val soft = HostAuth.SoftwareKey(SshKeyType.RSA_3072_PLUS, "ref.key").toAuthMethod()
        val hw = HostAuth.HardwareKey(SshKeyType.ED25519, "alias").toAuthMethod()

        assertTrue(pwd is AuthMethod.Password)
        assertTrue(soft is AuthMethod.PublicKey && soft.storage == KeyStorage.SOFTWARE_FALLBACK)
        assertTrue(hw is AuthMethod.PublicKey && hw.storage == KeyStorage.HARDWARE_NON_EXPORTABLE)

        // The hardware ed25519 key is preferred over software RSA over password.
        val ordered = listOf(pwd, soft, hw).byPreference()
        assertEquals(hw, ordered.first())
        assertEquals(pwd, ordered.last())
    }

    @Test
    fun scriptsFor_returns_only_enabled_in_phase_order() {
        val session = Session(
            id = "s", name = "s", hostId = "h",
            scripts = listOf(
                SessionScript(id = "1", label = "a", phase = ScriptPhase.ON_SHELL_START),
                SessionScript(id = "2", label = "b", phase = ScriptPhase.ON_SHELL_START, enabled = false),
                SessionScript(id = "3", label = "c", phase = ScriptPhase.POST_INIT),
            ),
        )
        val onStart = session.scriptsFor(ScriptPhase.ON_SHELL_START)
        assertEquals(listOf("1"), onStart.map { it.id })
    }

    @Test
    fun controller_duplicate_creates_independent_copy() {
        val h = host("h1")
        val s = Session(
            id = "s1", name = "orig", hostId = "h1",
            scripts = listOf(SessionScript(id = "sc1", label = "one")),
            tunnels = listOf(Tunnel(id = "t1", type = TunnelType.LOCAL, listenPort = 80)),
        )
        val controller = ConfigController(FakeConfigStore(TitanConfig(hosts = listOf(h), sessions = listOf(s))), CoroutineScope(Dispatchers.Unconfined))

        val copy = controller.duplicateSession("s1")

        assertNotNull(copy)
        assertTrue(copy.id != "s1")
        assertTrue(copy.scripts.single().id != "sc1")
        assertTrue(copy.tunnels.single().id != "t1")
        assertEquals("orig (copia)", copy.name)
        assertEquals(2, controller.state.value.sessions.size)
    }

    @Test
    fun controller_delete_host_clears_proxy_reference() {
        val bastion = host("bastion")
        val h = host("h1", proxy = "bastion")
        val controller = ConfigController(FakeConfigStore(TitanConfig(hosts = listOf(bastion, h))), CoroutineScope(Dispatchers.Unconfined))

        controller.deleteHost("bastion")

        val hosts = controller.state.value.hosts
        assertEquals(1, hosts.size)
        assertNull(hosts.single().proxyJumpHostId)
    }

    @Test
    fun controller_delete_group_detaches_members() {
        val g = Group(id = "g1", name = "proj")
        val h = host("h1", groupId = "g1")
        val s = Session(id = "s1", name = "s", hostId = "h1", groupId = "g1")
        val controller = ConfigController(FakeConfigStore(TitanConfig(hosts = listOf(h), sessions = listOf(s), groups = listOf(g))), CoroutineScope(Dispatchers.Unconfined))

        controller.deleteGroup("g1")

        assertTrue(controller.state.value.groups.isEmpty())
        assertNull(controller.state.value.hosts.single().groupId)
        assertNull(controller.state.value.sessions.single().groupId)
    }
}

/** In-memory [ConfigStore] for controller tests. */
private class FakeConfigStore(private var config: TitanConfig) : ConfigStore {
    override suspend fun load(): TitanConfig = config
    override suspend fun save(config: TitanConfig) {
        this.config = config
    }
}
