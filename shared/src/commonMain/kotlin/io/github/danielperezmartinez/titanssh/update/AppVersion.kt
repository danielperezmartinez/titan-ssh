package io.github.danielperezmartinez.titanssh.update

/**
 * A release version, `X.Y.Z` or `X.Y.Z-(alpha|beta|rc|canary).N`: the only
 * shapes the root build file accepts ([[Versionado único desde tag de git]]).
 * Ordered like the `versionCode`: canary < alpha < beta < rc < stable, then by
 * N. Canary versions only ever meet other canary versions (ADR-0027).
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** `alpha`, `beta`, `rc`, `canary`, or null for a stable version. */
    val stage: String?,
    val number: Int,
) : Comparable<AppVersion> {

    val isPrerelease: Boolean get() = stage != null

    private val stageRank: Int
        get() = when (stage) {
            "canary" -> -1
            "alpha" -> 0
            "beta" -> 1
            "rc" -> 2
            else -> 3
        }

    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { it.stageRank }, { it.number })

    override fun toString(): String =
        "$major.$minor.$patch" + (stage?.let { "-$it.$number" } ?: "")

    companion object {
        private val PATTERN = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc|canary)\.(\d+))?$""")

        /** Parses [text], with or without a leading `v`; null if it is not a release version. */
        fun parse(text: String): AppVersion? {
            val match = PATTERN.matchEntire(text.trim().removePrefix("v")) ?: return null
            val (x, y, z, stage, n) = match.destructured
            return runCatching {
                AppVersion(x.toInt(), y.toInt(), z.toInt(), stage.ifEmpty { null }, if (stage.isEmpty()) 0 else n.toInt())
            }.getOrNull()
        }
    }
}
