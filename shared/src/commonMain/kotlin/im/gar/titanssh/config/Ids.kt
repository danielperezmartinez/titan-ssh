package im.gar.titanssh.config

import kotlin.random.Random

/**
 * Generates opaque, collision-resistant ids for config entities. Ids are stable
 * once assigned and are used as references (a session points to its host by id,
 * a host to its ProxyJump host by id), so they must not be reused or reordered.
 *
 * A random 64-bit suffix is enough for a single user's local config; this avoids
 * needing a platform UUID in commonMain.
 */
object Ids {
    fun newId(prefix: String): String {
        val suffix = Random.nextLong().toULong().toString(16).padStart(16, '0')
        return "$prefix-$suffix"
    }

    fun host(): String = newId("host")
    fun session(): String = newId("session")
    fun group(): String = newId("group")
    fun snippet(): String = newId("snippet")
    fun script(): String = newId("script")
    fun tunnel(): String = newId("tunnel")
}
