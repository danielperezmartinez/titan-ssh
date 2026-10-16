package io.github.danielperezmartinez.titanssh.update

import io.github.danielperezmartinez.titanssh.config.UpdatePolicy
import kotlinx.coroutines.flow.StateFlow

/** What the updater is doing (ADR-0025), for Ajustes, Acerca de and the header. */
sealed interface UpdateState {
    /** The updater does not run in this install, and why (development build, Flatpak…). */
    data class Unavailable(val reason: String) : UpdateState

    /** Nothing checked yet. */
    data object Idle : UpdateState

    data object Checking : UpdateState

    /** The last check found nothing newer than the running version. */
    data object UpToDate : UpdateState

    /** [progress] in 0..1, or null while the size is unknown. */
    data class Downloading(val version: String, val progress: Float?) : UpdateState

    /**
     * [version] is downloaded and verified. [needsPermission]: Android has to be
     * allowed to install apps from titan-ssh first. [needsConfirmation]: the
     * system is waiting for the user to confirm the install. [lastError]: why
     * the last attempt to install it failed, if one did.
     */
    data class Ready(
        val version: String,
        val needsPermission: Boolean = false,
        val needsConfirmation: Boolean = false,
        val lastError: String? = null,
    ) : UpdateState

    data class Installing(val version: String) : UpdateState

    /** The last check or install failed; the next periodic check tries again. */
    data class Failed(val message: String) : UpdateState
}

/**
 * The in-app updater (ADR-0025): polls the GitHub Releases, downloads the
 * newest version (betas included) when its manifest is signed by a key of the
 * app, and installs it according to the [UpdatePolicy], never closing an open
 * tab. One per process ([AppUpdates]).
 */
interface AppUpdater {
    val state: StateFlow<UpdateState>

    /**
     * Where an automatic install happens: true on Android (in the background
     * with no tab open), false on the desktop (when the app exits).
     */
    val installsInBackground: Boolean

    /** Starts the periodic checks. Idempotent. */
    fun start()

    fun checkNow()

    fun setPolicy(policy: UpdatePolicy)

    /** How many tabs are open: an automatic install waits for none. */
    fun setOpenTabs(count: Int)

    /** Whether the app is on screen; Android installs in the background. */
    fun setForeground(visible: Boolean)

    /** The user asked to install the ready update now: the app restarts. */
    fun installNow()

    /** Android: opens the system setting that lets titan-ssh install apps. */
    fun requestInstallPermission()

    /**
     * The desktop app is about to exit. With [UpdatePolicy.AUTO] and an update
     * ready, starts the installer, which runs once the app is gone.
     */
    fun beforeExit()
}

/** The process-wide updater, created on first use. */
object AppUpdates {
    val updater: AppUpdater by lazy { createAppUpdater() }

    /**
     * Ends the app, set by the desktop entry point: installing an update on
     * the desktop starts the installer and then exits.
     */
    var exitApp: (() -> Unit)? = null
}

/** The platform updater, or one stuck in [UpdateState.Unavailable] where none runs. */
internal expect fun createAppUpdater(): AppUpdater

/** An updater that never runs, for installs another channel updates. */
class UnavailableUpdater(reason: String) : AppUpdater {
    override val state: StateFlow<UpdateState> =
        kotlinx.coroutines.flow.MutableStateFlow(UpdateState.Unavailable(reason))
    override val installsInBackground: Boolean = false

    override fun start() = Unit
    override fun checkNow() = Unit
    override fun setPolicy(policy: UpdatePolicy) = Unit
    override fun setOpenTabs(count: Int) = Unit
    override fun setForeground(visible: Boolean) = Unit
    override fun installNow() = Unit
    override fun requestInstallPermission() = Unit
    override fun beforeExit() = Unit
}
