package io.github.danielperezmartinez.titanssh.update

import java.io.File

/** How this install is updated: the per-platform half of the updater (ADR-0025 §3). */
interface UpdatePlatform {
    /** The [UpdateArtifact.kind] this install takes. */
    val artifactKind: String

    /** Where downloads wait to be installed. */
    val cacheDir: File

    /**
     * True on Android: an automatic install runs while the app is in the
     * background with no tab open. False on the desktop: it runs at exit.
     */
    val installsInBackground: Boolean

    /** Android: titan-ssh is not yet allowed to install apps. */
    fun needsPermission(): Boolean = false

    fun requestPermission() = Unit

    /**
     * Installs [file], the verified package of [version] whose digest is
     * [sha256]. On the desktop it starts a helper that waits for the app to
     * exit, checks [sha256] once more right before installing, installs and,
     * with [relaunch], opens the new version; the caller then exits the app.
     */
    suspend fun install(file: File, sha256: String, version: String, relaunch: Boolean): InstallOutcome

    /**
     * The result the desktop helper left in [versionDir] after a previous
     * attempt, or null if there was none or it worked.
     */
    fun previousFailure(versionDir: File): String? = null
}

sealed interface InstallOutcome {
    /** The install is under way; on the desktop the app must exit now. */
    data object Started : InstallOutcome

    /** Android: the system waits for the user to confirm. */
    data object AwaitingUser : InstallOutcome

    /** Android: the permission to install apps is missing. */
    data object NeedsPermission : InstallOutcome

    data class Failed(val message: String) : InstallOutcome
}
