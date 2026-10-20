package io.github.danielperezmartinez.titanssh.update

import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * `titan-update.json`, published with every GitHub Release next to its Ed25519
 * signature `titan-update.json.sig` (ADR-0025 §1). The release workflow writes
 * it; the app trusts a package only through it. [channel] is part of what is
 * signed, so a canary manifest can never pass for a stable one (ADR-0027);
 * manifests written before it existed are all stable.
 */
@Serializable
data class UpdateManifest(
    val schema: Int,
    val version: String,
    val artifacts: List<UpdateArtifact>,
    val channel: String = UpdateChannel.STABLE.id,
)

/** Which app a build is and which Releases it updates from (ADR-0027). */
enum class UpdateChannel(val id: String) {
    /** Stable versions only (`X.Y.Z`), from Releases that are not pre-releases. */
    STABLE("stable"),

    /** `X.Y.Z-canary.N` versions, only from the rolling [CANARY_TAG] Release. */
    CANARY("canary");

    fun accepts(version: AppVersion): Boolean = when (this) {
        STABLE -> !version.isPrerelease
        CANARY -> version.stage == "canary"
    }

    companion object {
        /** The fixed tag of the canary Release, replaced on every canary build. */
        const val CANARY_TAG = "canary"

        fun of(id: String): UpdateChannel = entries.firstOrNull { it.id == id } ?: STABLE
    }
}

/** One package of the Release: [kind] is `msi`, `deb`, `rpm`, `tar.gz` or `apk`. */
@Serializable
data class UpdateArtifact(
    val kind: String,
    val name: String,
    val size: Long,
    val sha256: String,
)

/** The fields of the GitHub Releases API the updater reads. */
@Serializable
internal data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
internal data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0,
)

internal object UpdateFiles {
    const val MANIFEST = "titan-update.json"
    const val SIGNATURE = "titan-update.json.sig"

    /** Asset names are plain file names: nothing that could leave the update folder. */
    private val SAFE_NAME = Regex("""^[A-Za-z0-9][A-Za-z0-9._+-]*$""")

    fun isSafeName(name: String): Boolean = name.length <= 200 && SAFE_NAME.matches(name)

    val json = Json { ignoreUnknownKeys = true }

    /** A Releases list, or the single Release that `releases/tags/<tag>` answers with. */
    fun decodeReleases(text: String): List<GithubRelease> =
        when (val element = json.parseToJsonElement(text)) {
            is JsonObject -> listOf(json.decodeFromJsonElement(GithubRelease.serializer(), element))
            else -> json.decodeFromJsonElement(ListSerializer(GithubRelease.serializer()), element)
        }

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256Hex(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest())
    }

    fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

/** Checks the raw 64-byte Ed25519 signature of a manifest against the app's keys. */
internal object UpdateSignature {
    fun verify(message: ByteArray, signature: ByteArray, keys: List<ByteArray>): Boolean {
        if (signature.size != Ed25519PublicKeyParameters.KEY_SIZE * 2) return false
        return keys.any { key ->
            runCatching {
                val signer = Ed25519Signer()
                signer.init(false, Ed25519PublicKeyParameters(key, 0))
                signer.update(message, 0, message.size)
                signer.verifySignature(signature)
            }.getOrDefault(false)
        }
    }
}
