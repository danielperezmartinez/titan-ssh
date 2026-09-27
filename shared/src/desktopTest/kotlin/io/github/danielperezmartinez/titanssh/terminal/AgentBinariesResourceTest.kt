package io.github.danielperezmartinez.titanssh.terminal

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies what the `buildAgentBinaries` Gradle task packages (ADR-0010): the six
 * main targets bundled as JVM resources, each an executable of its OS, and the
 * pinned digests of every target, bundled or not. Tolerant of a build made
 * without the Go toolchain: then nothing is bundled and the test only asserts
 * the deployer still constructs (and will degrade at runtime).
 */
class AgentBinariesResourceTest {

    private val bundled = listOf(
        AgentTarget("linux", "amd64"), AgentTarget("linux", "arm64"),
        AgentTarget("darwin", "amd64"), AgentTarget("darwin", "arm64"),
        AgentTarget("windows", "amd64"), AgentTarget("windows", "arm64"),
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun ByteArray.startsWith(vararg magic: Int): Boolean =
        size > magic.size && magic.indices.all { this[it] == magic[it].toByte() }

    @Test
    fun bundles_the_main_targets_as_executables_of_their_os() {
        if (AgentBinaries.checksums.isEmpty()) {
            println("[agent] no bundled binaries (built without Go?); skipping")
            return
        }
        for (target in bundled) {
            val bytes = assertNotNull(AgentBinaries.bundled(target), "${target.slug} should be bundled")
            val isExecutable = when (target.os) {
                "linux" -> bytes.startsWith(0x7F, 'E'.code, 'L'.code, 'F'.code)
                // 64-bit Mach-O, little-endian (MH_MAGIC_64).
                "darwin" -> bytes.startsWith(0xCF, 0xFA, 0xED, 0xFE)
                "windows" -> bytes.startsWith('M'.code, 'Z'.code)
                else -> false
            }
            assertTrue(isExecutable, "${target.fileName} should be an executable for ${target.os}")
            assertEquals(AgentBinaries.checksums[target.fileName], sha256(bytes), "${target.fileName} digest")
        }
    }

    @Test
    fun pins_every_target_and_bundles_only_the_main_ones() {
        if (AgentBinaries.checksums.isEmpty()) return
        assertEquals(
            AgentInstall.SUPPORTED_TARGETS.map { it.fileName }.toSet(),
            AgentBinaries.checksums.keys,
            "every supported target has a pinned digest",
        )
        for (target in AgentInstall.SUPPORTED_TARGETS - bundled.toSet()) {
            assertNull(AgentBinaries.bundled(target), "${target.slug} is downloaded on demand, not bundled")
        }
    }

    @Test
    fun agent_deployer_is_constructible() {
        assertNotNull(createAgentDeployer(), "createAgentDeployer() should return a deployer")
    }
}
