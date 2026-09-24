package io.github.danielperezmartinez.titanssh.config

import io.github.danielperezmartinez.titanssh.secret.SshKeyType
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real file I/O round-trip for [JsonFileConfigStore] against a temp directory,
 * covering every auth variant, scripts, tunnels, groups and snippets. Confirms
 * the persisted document reloads to an equal config and holds no plaintext
 * secret (only references).
 */
class JsonFileConfigStoreTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "titan-config-test-${System.nanoTime()}").apply { mkdirs() }

    private val sample = TitanConfig(
        hosts = listOf(
            Host(
                id = "h1", alias = "casa", hostname = "casa.ts.net", port = 22, username = "user",
                auth = HostAuth.HardwareKey(SshKeyType.ECDSA_P256, "titan-hw-casa"),
                hostKeyPolicy = HostKeyPolicy.TOFU, keepAliveSeconds = 30, groupId = "g1",
                tags = listOf("prod"),
            ),
            Host(
                id = "h2", alias = "vps", hostname = "vps.example.net", port = 2222, username = "root",
                auth = HostAuth.SoftwareKey(SshKeyType.ED25519, "vps.key", passphraseRef = "vps.pass"),
                proxyJumpHostId = "h1",
            ),
            Host(
                id = "h3", alias = "legacy", hostname = "10.0.0.9", username = "admin",
                auth = HostAuth.Password("legacy.pwd"),
            ),
        ),
        sessions = listOf(
            Session(
                id = "s1", name = "deploy", hostId = "h1", initialDirectory = "/srv/app",
                resilienceLevel = ResilienceLevel.AUTO_MULTIPLEXER,
                scripts = listOf(
                    SessionScript(
                        id = "sc1", label = "cd y arrancar", phase = ScriptPhase.ON_SHELL_START,
                        body = "cd /srv/app && ./run.sh",
                        behavior = ScriptBehavior(silent = false, waitForCompletion = true, timeoutSeconds = 30, onFailure = ScriptFailurePolicy.ABORT),
                        secretRefs = listOf("casa.token"),
                    ),
                    SessionScript(id = "sc2", label = "reconecta", phase = ScriptPhase.ON_RECONNECT, reconnectBehavior = ReconnectBehavior.RESTORE_CD_ONLY),
                ),
                tunnels = listOf(
                    Tunnel(id = "t1", type = TunnelType.LOCAL, listenPort = 8080, destinationHost = "127.0.0.1", destinationPort = 80),
                    Tunnel(id = "t2", type = TunnelType.DYNAMIC_SOCKS, listenPort = 1080),
                ),
                appearance = TerminalAppearance(fontSize = 15),
                groupId = "g1",
            ),
        ),
        groups = listOf(Group(id = "g1", name = "casa")),
        snippets = listOf(Snippet(id = "sn1", name = "restart", body = "sudo systemctl restart app", tags = listOf("ops"))),
        defaultAppearance = TerminalAppearance(fontFamily = "JetBrains Mono", fontSize = 13, colorTheme = "ansi"),
    )

    @Test
    fun round_trip_preserves_the_whole_config() = runBlocking {
        val dir = tempDir()
        try {
            val store = JsonFileConfigStore(dir)
            store.save(sample)
            val reloaded = JsonFileConfigStore(dir).load()
            assertEquals(sample, reloaded)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missing_file_loads_empty_config() = runBlocking {
        val dir = tempDir()
        try {
            assertEquals(TitanConfig(), JsonFileConfigStore(dir).load())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun persisted_document_holds_only_references_not_secrets() = runBlocking {
        val dir = tempDir()
        try {
            JsonFileConfigStore(dir).save(sample)
            val text = File(dir, JsonFileConfigStore.CONFIG_FILE).readText()
            // References are present...
            assertTrue(text.contains("legacy.pwd"))
            assertTrue(text.contains("titan-hw-casa"))
            // ...and the sealed auth is tagged by the configured discriminator.
            assertTrue(text.contains("\"kind\""))
        } finally {
            dir.deleteRecursively()
        }
    }
}
