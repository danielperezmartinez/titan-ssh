package io.github.danielperezmartinez.titanssh.update

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Linux installer helper must not inherit what jpackage's launcher sets in
 * the app's process: a relaunched launcher that sees it takes itself for a JVM
 * restart and exits without opening the app.
 */
class DesktopUpdatePlatformTest {

    private val root = File("/opt/titan-ssh")
    private val appDir = File(root, "lib/app").absolutePath

    @Test
    fun drops_the_launcher_marker_and_the_app_dir() {
        val env = mutableMapOf(
            "_JPACKAGE_LAUNCHER" to "1234",
            "LD_LIBRARY_PATH" to "${File.pathSeparator}$appDir",
            "DISPLAY" to ":0",
        )
        DesktopUpdatePlatform.withoutLauncherMarks(env, root)
        assertEquals(mapOf("DISPLAY" to ":0"), env)
    }

    @Test
    fun keeps_the_users_own_library_path() {
        val env = mutableMapOf("LD_LIBRARY_PATH" to "/usr/local/lib${File.pathSeparator}$appDir")
        DesktopUpdatePlatform.withoutLauncherMarks(env, root)
        assertEquals("/usr/local/lib", env["LD_LIBRARY_PATH"])
    }

    @Test
    fun leaves_an_environment_without_marks_alone() {
        val env = mutableMapOf("HOME" to "/tmp")
        DesktopUpdatePlatform.withoutLauncherMarks(env, root)
        assertEquals(mapOf("HOME" to "/tmp"), env)
        assertNull(env["LD_LIBRARY_PATH"])
    }
}
