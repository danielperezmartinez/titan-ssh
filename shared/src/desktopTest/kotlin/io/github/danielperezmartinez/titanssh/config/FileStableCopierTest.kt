package io.github.danielperezmartinez.titanssh.config

import io.github.danielperezmartinez.titanssh.secret.SecretRef
import io.github.danielperezmartinez.titanssh.secret.SecretStore
import io.github.danielperezmartinez.titanssh.secret.SshKeyType
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * [FileStableCopier] (ADR-0027 §4): the canary gets the stable app's
 * configuration, `known_hosts` and, when it may, the secrets they refer to;
 * the stable app's files are only ever read.
 */
class FileStableCopierTest {

    private val root: File = Files.createTempDirectory("stable-copy").toFile()
    private val stableDir = File(root, "titan-ssh").apply { mkdirs() }
    private val canaryDir = File(root, "titan-ssh-canary")

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private class FakeSecretStore(initial: Map<String, String> = emptyMap()) : SecretStore {
        val map = initial.mapValues { it.value.encodeToByteArray() }.toMutableMap()
        override suspend fun put(ref: SecretRef, secret: ByteArray) { map[ref.value] = secret.copyOf() }
        override suspend fun get(ref: SecretRef): ByteArray? = map[ref.value]?.copyOf()
        override suspend fun remove(ref: SecretRef) { map.remove(ref.value) }
        override suspend fun contains(ref: SecretRef): Boolean = map.containsKey(ref.value)
    }

    private val stableConfig = TitanConfig(
        hosts = listOf(
            Host(id = "h1", alias = "casa", hostname = "casa.example", username = "u", auth = HostAuth.HardwareKey(SshKeyType.ECDSA_P256, "hw")),
            Host(id = "h2", alias = "vps", hostname = "vps.example", username = "u", auth = HostAuth.SoftwareKey(SshKeyType.ED25519, "vps.key", "vps.pass")),
            Host(id = "h3", alias = "old", hostname = "old.example", username = "u", auth = HostAuth.Password("old.pwd")),
        ),
        scripts = listOf(LibraryScript(id = "s1", name = "deploy", body = "echo", secretRefs = listOf("deploy.token"))),
    )

    private fun copier(secrets: Pair<SecretStore, SecretStore>?) = FileStableCopier(
        configDir = canaryDir,
        knownHosts = File(canaryDir, "known_hosts"),
        readStable = {
            File(stableDir, JsonFileConfigStore.CONFIG_FILE).takeIf { it.isFile }?.let {
                StableSnapshot(it.readBytes(), File(stableDir, "known_hosts").takeIf { f -> f.isFile }?.readBytes())
            } ?: StableUnavailable(NO_STABLE_CONFIG)
        },
        secrets = secrets,
    )

    @Test
    fun copies_the_config_known_hosts_and_secrets() = runBlocking {
        JsonFileConfigStore(stableDir).save(stableConfig)
        File(stableDir, "known_hosts").writeText("[vps.example]:22 ssh-ed25519 AAAA\n")
        val before = stableDir.walk().filter { it.isFile }.associate { it.path to it.readText() }
        val stableSecrets = FakeSecretStore(mapOf("vps.key" to "KEY", "vps.pass" to "PASS", "deploy.token" to "TOKEN"))
        val canarySecrets = FakeSecretStore()
        val copier = copier(stableSecrets to canarySecrets)
        assertFalse(copier.hasOwnConfig())

        val result = copier.copy()

        assertEquals(StableCopyResult.Copied(hosts = 3, sessions = 0, secrets = 3, missingSecrets = 1), result)
        assertTrue(copier.hasOwnConfig())
        assertEquals(stableConfig, JsonFileConfigStore(canaryDir).load())
        assertEquals("[vps.example]:22 ssh-ed25519 AAAA\n", File(canaryDir, "known_hosts").readText())
        assertEquals(setOf("vps.key", "vps.pass", "deploy.token"), canarySecrets.map.keys)
        assertEquals("TOKEN", canarySecrets.map.getValue("deploy.token").decodeToString())
        assertEquals(before, stableDir.walk().filter { it.isFile }.associate { it.path to it.readText() }, "the stable app's files are untouched")
        assertFalse(File(canaryDir, "stable-copy").exists())
    }

    @Test
    fun without_secrets_only_the_config_comes_along() = runBlocking {
        JsonFileConfigStore(stableDir).save(stableConfig)
        val result = copier(secrets = null).copy()
        assertEquals(StableCopyResult.Copied(hosts = 3, sessions = 0, secrets = 0, missingSecrets = 0), result)
        assertNull(File(canaryDir, "known_hosts").takeIf { it.exists() })
    }

    @Test
    fun an_unreadable_or_missing_stable_config_changes_nothing() = runBlocking {
        val own = TitanConfig(hosts = listOf(Host(id = "c", alias = "mine", hostname = "m", username = "u", auth = HostAuth.Password("p"))))
        JsonFileConfigStore(canaryDir).save(own)

        assertEquals(StableCopyResult.NotCopied(NO_STABLE_CONFIG), copier(null).copy())
        File(stableDir, JsonFileConfigStore.CONFIG_FILE).writeText("{ not json")
        assertIs<StableCopyResult.NotCopied>(copier(null).copy())

        assertEquals(own, JsonFileConfigStore(canaryDir).load())
    }
}
