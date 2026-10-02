package io.github.danielperezmartinez.titanssh.config

import io.github.danielperezmartinez.titanssh.secret.AuthMethod
import io.github.danielperezmartinez.titanssh.secret.KeyStorage
import io.github.danielperezmartinez.titanssh.secret.SshKeyType
import io.github.danielperezmartinez.titanssh.secret.byPreference
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
        assertEquals(emptyList(), resolved.jumps)
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

        assertEquals(listOf("bastion"), cfg.resolve(s).jumps.map { it.id })
    }

    @Test
    fun resolve_orders_a_jump_chain_first_hop_first() {
        val outer = host("outer")
        val inner = host("inner", proxy = "outer")
        val h = host("h1", proxy = "inner")
        val s = Session(id = "s1", name = "sess", hostId = "h1")
        val cfg = TitanConfig(hosts = listOf(h, inner, outer), sessions = listOf(s))

        assertEquals(listOf("outer", "inner"), cfg.resolve(s).jumps.map { it.id })
    }

    @Test
    fun resolve_throws_for_unknown_jump_host() {
        val h = host("h1", proxy = "ghost")
        val s = Session(id = "s1", name = "sess", hostId = "h1")
        val cfg = TitanConfig(hosts = listOf(h), sessions = listOf(s))

        assertFailsWith<ConfigResolutionException> { cfg.resolve(s) }
    }

    @Test
    fun resolve_throws_for_a_jump_loop() {
        val a = host("a", proxy = "b")
        val b = host("b", proxy = "a")
        val h = host("h1", proxy = "a")
        val s = Session(id = "s1", name = "sess", hostId = "h1")
        val cfg = TitanConfig(hosts = listOf(h, a, b), sessions = listOf(s))

        assertFailsWith<ConfigResolutionException> { cfg.resolve(s) }
    }

    @Test
    fun local_and_socks_tunnels_listen_on_the_network_beyond_loopback() {
        fun t(type: TunnelType, listen: String) = Tunnel("t", type, listenHost = listen, listenPort = 1080)
        for (loopback in listOf("127.0.0.1", "127.10.0.1", "localhost", "::1", "[::1]", "", " ")) {
            assertEquals(false, t(TunnelType.DYNAMIC_SOCKS, loopback).listensOnNetwork, loopback)
        }
        for (network in listOf("0.0.0.0", "::", "192.168.1.20", "127.0.0.256", "my-pc", "*")) {
            assertEquals(true, t(TunnelType.DYNAMIC_SOCKS, network).listensOnNetwork, network)
            assertEquals(true, t(TunnelType.LOCAL, network).listensOnNetwork, network)
        }
        assertEquals(false, t(TunnelType.REMOTE, "0.0.0.0").listensOnNetwork, "a remote forward listens on the server")
    }

    @Test
    fun jump_options_leave_out_the_host_and_those_that_jump_through_it() {
        val h = host("h1")
        val via = host("via", proxy = "h1")
        val other = host("other")
        val cfg = TitanConfig(hosts = listOf(h, via, other))

        assertEquals(listOf("other"), cfg.hosts.filter { cfg.canJumpThrough("h1", it) }.map { it.id })
        assertEquals(listOf("h1", "via", "other"), cfg.hosts.filter { cfg.canJumpThrough(null, it) }.map { it.id })
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
    fun controller_delete_group_detaches_only_the_members_of_its_list() {
        // The same id in both lists: deleting the hosts group leaves the
        // sessions group and its members alone.
        val h = host("h1", groupId = "g1")
        val s = Session(id = "s1", name = "s", hostId = "h1", groupId = "g1")
        val cfg = TitanConfig(
            hosts = listOf(h),
            sessions = listOf(s),
            hostGroups = listOf(Group(id = "g1", name = "proj")),
            sessionGroups = listOf(Group(id = "g1", name = "proj")),
        )
        val controller = ConfigController(FakeConfigStore(cfg), CoroutineScope(Dispatchers.Unconfined))

        controller.deleteGroup(GroupScope.HOSTS, "g1")

        assertTrue(controller.state.value.hostGroups.isEmpty())
        assertNull(controller.state.value.hosts.single().groupId)
        assertEquals(cfg.sessionGroups, controller.state.value.sessionGroups)
        assertEquals("g1", controller.state.value.sessions.single().groupId)
    }

    private val restart = LibraryScript(
        id = "lib1", name = "restart", body = "systemctl restart app",
        behavior = ScriptBehavior(timeoutSeconds = 5), envVars = mapOf("A" to "1"), secretRefs = listOf("tok"),
    )

    private fun reference(id: String, phase: ScriptPhase = ScriptPhase.POST_INIT, enabled: Boolean = true) =
        SessionScript(id = id, label = "", phase = phase, enabled = enabled, libraryScriptId = restart.id)

    @Test
    fun resolve_fills_library_references_and_drops_dangling_ones() {
        val own = SessionScript(id = "own", label = "own", body = "echo own")
        val s = Session(
            id = "s1", name = "s", hostId = "h1",
            scripts = listOf(reference("r1", enabled = false), own, SessionScript(id = "r2", label = "x", libraryScriptId = "gone")),
        )
        val cfg = TitanConfig(hosts = listOf(host("h1")), sessions = listOf(s), scripts = listOf(restart))

        val scripts = cfg.resolve(s).session.scripts

        assertEquals(listOf("r1", "own"), scripts.map { it.id })
        val filled = scripts.first()
        // Content from the library; phase, enabled flag and link from the reference.
        assertEquals("restart", filled.label)
        assertEquals("systemctl restart app", filled.body)
        assertEquals(restart.behavior, filled.behavior)
        assertEquals(restart.envVars, filled.envVars)
        assertEquals(restart.secretRefs, filled.secretRefs)
        assertEquals(ScriptPhase.POST_INIT, filled.phase)
        assertEquals(false, filled.enabled)
        assertEquals("lib1", filled.libraryScriptId)
        assertEquals(own, scripts[1])
    }

    @Test
    fun editing_the_library_changes_every_session_that_uses_it() {
        val a = Session(id = "a", name = "a", hostId = "h1", scripts = listOf(reference("ra")))
        val b = Session(id = "b", name = "b", hostId = "h1", scripts = listOf(reference("rb", ScriptPhase.ON_DEMAND)))
        val controller = ConfigController(
            FakeConfigStore(TitanConfig(hosts = listOf(host("h1")), sessions = listOf(a, b), scripts = listOf(restart))),
            CoroutineScope(Dispatchers.Unconfined),
        )

        controller.upsertLibraryScript(restart.copy(body = "systemctl reload app"))

        val cfg = controller.state.value
        assertEquals(listOf("a", "b"), cfg.sessionsUsing("lib1").map { it.id })
        assertEquals("systemctl reload app", cfg.resolve(a).session.scripts.single().body)
        assertEquals("systemctl reload app", cfg.resolve(b).session.scripts.single().body)
    }

    @Test
    fun deleting_a_used_library_script_leaves_an_own_copy_in_each_session() {
        val other = SessionScript(id = "o", label = "o", body = "echo o")
        val s = Session(id = "s1", name = "s", hostId = "h1", scripts = listOf(reference("r1"), other))
        val controller = ConfigController(
            FakeConfigStore(TitanConfig(hosts = listOf(host("h1")), sessions = listOf(s), scripts = listOf(restart))),
            CoroutineScope(Dispatchers.Unconfined),
        )
        val before = controller.state.value.resolve(s).session.scripts

        controller.deleteLibraryScript("lib1")

        val cfg = controller.state.value
        assertTrue(cfg.scripts.isEmpty())
        val scripts = cfg.sessions.single().scripts
        assertNull(scripts.first().libraryScriptId)
        // What runs is unchanged, apart from the link itself.
        assertEquals(before.map { it.copy(libraryScriptId = null) }, cfg.resolve(cfg.sessions.single()).session.scripts)
        assertEquals(other, scripts[1])
    }

    @Test
    fun on_demand_menu_lists_the_session_scripts_then_the_rest_of_the_library() {
        val deploy = LibraryScript(id = "lib2", name = "deploy", body = "./deploy.sh")
        val s = Session(
            id = "s1", name = "s", hostId = "h1",
            scripts = listOf(
                SessionScript(id = "own", label = "logs", body = "tail -f log", phase = ScriptPhase.ON_DEMAND),
                reference("r1", ScriptPhase.ON_DEMAND),
                SessionScript(id = "off", label = "off", phase = ScriptPhase.ON_DEMAND, enabled = false),
                SessionScript(id = "start", label = "start", phase = ScriptPhase.ON_SHELL_START),
            ),
        )
        val cfg = TitanConfig(hosts = listOf(host("h1")), sessions = listOf(s), scripts = listOf(restart, deploy))

        val menu = cfg.onDemandScripts(s)

        // restart is already listed by the session, so only deploy is added.
        assertEquals(listOf("logs", "restart", "deploy"), menu.map { it.label })
        assertEquals("./deploy.sh", menu.last().body)
        assertTrue(menu.all { it.phase == ScriptPhase.ON_DEMAND })
    }
}

/** In-memory [ConfigStore] for controller tests. */
internal class FakeConfigStore(private var config: TitanConfig) : ConfigStore {
    override suspend fun load(): TitanConfig = config
    override suspend fun save(config: TitanConfig) {
        this.config = config
    }
}
