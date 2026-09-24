// Root build file. Plugins are declared here (applied in modules) so their
// versions resolve from the version catalog once, project-wide.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
}

// --- Single app version (Android, desktop and the level-3 agent) --------------
//
// One source for every artifact (ADR-0011 §3). Resolution order:
//   1. `-PtitanVersion=X.Y.Z[-pre]` (CI passes the tag without its leading `v`);
//   2. the `vX.Y.Z[-pre]` git tag pointing exactly at HEAD;
//   3. the development value DEV_VERSION, which no release can be mistaken for.
//
// Accepted form: SemVer core `X.Y.Z` plus an optional pre-release `alpha.N`,
// `beta.N` or `rc.N` (N in 1..29). Anything else fails the build, so a typo in a
// tag never ships a package with a nonsensical version.
//
// Derived values (published through rootProject.extra):
//   titanVersion         full string, shown in the app (versionName, About).
//   titanVersionCode     Android versionCode, strictly increasing across every
//                        release including pre-releases:
//                          X*1_000_000 + Y*10_000 + Z*100 + S
//                        where S = N (alpha), 30+N (beta), 60+N (rc), 99 (stable),
//                        so 0.1.0-beta.1 < 0.1.0-rc.1 < 0.1.0 < 0.1.1-beta.1.
//                        Limits: Y, Z <= 99 (X is bounded by the MSI below).
//                        The dev build uses 1, below any release.
//   titanPackageVersion  `X.Y.Z` for jpackage (no suffix allowed).
//   titanMsiVersion      `X.Y.(Z*100+S)`: Windows Installer only compares the
//                        first three fields and needs them numeric, so the
//                        pre-release rank goes into the third one; otherwise
//                        0.1.0-beta.2 could not upgrade 0.1.0-beta.1.
//                        Limits: X, Y <= 255 and Z*100+S <= 65535 (Z <= 654).
//   titanDebVersion      `X.Y.Z~pre`: Debian sorts `~` before the empty string,
//                        so a pre-release orders below its final version.
val DEV_VERSION = "0.0.0-dev"

val titanVersion: String = (findProperty("titanVersion") as String?)?.removePrefix("v")
    ?: gitExactTag()?.removePrefix("v")
    ?: DEV_VERSION

fun gitExactTag(): String? = try {
    val process = ProcessBuilder("git", "describe", "--tags", "--exact-match", "--match", "v[0-9]*", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(false)
        .start()
    val out = process.inputStream.bufferedReader().readText().trim()
    process.errorStream.readBytes()
    if (process.waitFor() == 0 && out.isNotEmpty()) out else null
} catch (_: Exception) {
    null
}

val versionRegex = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.(\d+))?$""")

if (titanVersion == DEV_VERSION) {
    extra["titanVersionCode"] = 1
    extra["titanPackageVersion"] = "0.0.0"
    extra["titanMsiVersion"] = "0.0.0"
    extra["titanDebVersion"] = "0.0.0~dev"
} else {
    val match = versionRegex.matchEntire(titanVersion)
        ?: throw GradleException("Invalid titanVersion '$titanVersion': expected X.Y.Z or X.Y.Z-(alpha|beta|rc).N")
    val (major, minor, patch) = match.groupValues.subList(1, 4).map { it.toInt() }
    val preKind = match.groupValues[4]
    val preNumber = match.groupValues[5].toIntOrNull()
    val rank = when (preKind) {
        "" -> 99
        else -> {
            require(preNumber in 1..29) { "Pre-release number in '$titanVersion' must be 1..29" }
            preNumber!! + when (preKind) { "alpha" -> 0; "beta" -> 30; else -> 60 }
        }
    }
    // Tightest of the versionCode and MSI limits documented above.
    require(major <= 255 && minor <= 99 && patch <= 99) {
        "titanVersion '$titanVersion' is out of range (X <= 255, Y <= 99, Z <= 99)"
    }
    extra["titanVersionCode"] = major * 1_000_000 + minor * 10_000 + patch * 100 + rank
    extra["titanPackageVersion"] = "$major.$minor.$patch"
    extra["titanMsiVersion"] = "$major.$minor.${patch * 100 + rank}"
    extra["titanDebVersion"] = if (preKind.isEmpty()) "$major.$minor.$patch" else "$major.$minor.$patch~$preKind.$preNumber"
}
extra["titanVersion"] = titanVersion

val versionValues = listOf("titanVersion", "titanVersionCode", "titanPackageVersion", "titanMsiVersion", "titanDebVersion")
    .associateWith { extra[it].toString() }
tasks.register("printVersion") {
    description = "Prints the resolved app version and every value derived from it."
    val values = versionValues
    doLast { values.forEach { (k, v) -> println("$k=$v") } }
}
