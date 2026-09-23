package io.github.danielperezmartinez.titanssh.terminal

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies the level-3 agent binary is packaged as a JVM resource by the
 * `buildAgentBinaries` Gradle task and is loadable by [AgentBinaries] (ADR-0008
 * §5). Tolerant of a build made without the Go toolchain: then no binary is
 * bundled and the test only asserts the deployer still constructs (and will
 * degrade at runtime).
 */
class AgentBinariesResourceTest {

    @Test
    fun bundled_linux_amd64_binary_is_loadable_when_built() {
        val bytes = AgentBinaries.load(AgentTarget("linux", "amd64"))
        if (bytes == null) {
            println("[agent] no bundled binary (built without Go?); skipping ELF check")
            return
        }
        // ELF magic: 0x7F 'E' 'L' 'F'.
        assertTrue(bytes.size > 4, "binary should be non-trivial")
        assertTrue(
            bytes[0] == 0x7F.toByte() &&
                bytes[1] == 'E'.code.toByte() &&
                bytes[2] == 'L'.code.toByte() &&
                bytes[3] == 'F'.code.toByte(),
            "bundled linux binary should be an ELF executable",
        )
    }

    @Test
    fun agent_deployer_is_constructible() {
        assertNotNull(createAgentDeployer(), "createAgentDeployer() should return a deployer")
    }
}
