package io.github.danielperezmartinez.titanssh.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.danielperezmartinez.titanssh.config.AndroidConfigContext
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes

internal actual fun createAppUpdater(): AppUpdater {
    val context = AndroidConfigContext.require()
    return buildUpdater(
        platform = { AndroidUpdatePlatform(context) },
        unavailableReason = { if (installedFromPlay(context)) "Google Play actualiza esta instalación." else null },
    )
}

private fun installedFromPlay(context: Context): Boolean {
    val installer = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()
    return installer == "com.android.vending"
}

/**
 * Android half of the updater: installs the verified APK through
 * [PackageInstaller]. The system checks that it is signed with the same key as
 * the installed app. The first update always needs the user to confirm it;
 * from Android 12 on, later ones may go through without asking
 * ([PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED]).
 */
internal class AndroidUpdatePlatform(private val context: Context) : UpdatePlatform {
    override val artifactKind: String = "apk"
    // Not cacheDir: the system trims it when storage runs low, and a download
    // may have to wait there until the app goes to the background.
    override val cacheDir: File = context.noBackupFilesDir
    override val installsInBackground: Boolean = true

    /** The system's confirmation screen, kept while the app was in the background. */
    @Volatile private var confirmation: Intent? = null
    @Volatile private var session: Int = -1
    @Volatile private var result: CompletableDeferred<InstallOutcome>? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onStatus(intent)
    }

    init {
        val filter = IntentFilter(ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    override fun needsPermission(): Boolean = !context.packageManager.canRequestPackageInstalls()

    override fun requestPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    // The system itself refuses an APK not signed with the installed app's key.
    override suspend fun install(file: File, sha256: String, version: String, relaunch: Boolean): InstallOutcome {
        if (needsPermission()) return InstallOutcome.NeedsPermission
        // A confirmation already waiting: show it (only works with the app on screen).
        confirmation?.let { waiting ->
            if (runCatching { context.startActivity(waiting) }.isSuccess) {
                confirmation = null
                return InstallOutcome.AwaitingUser
            }
        }
        val outcome = CompletableDeferred<InstallOutcome>()
        result = outcome
        val failure = withContext(Dispatchers.IO) { runCatching { commit(file) }.exceptionOrNull() }
        if (failure != null) return InstallOutcome.Failed(failure.message ?: "no se pudo preparar la instalación")
        // A silent update ends this process before any answer arrives. With
        // no answer at all, the next tap on install starts over.
        return withTimeoutOrNull(2.minutes) { outcome.await() } ?: InstallOutcome.AwaitingUser
    }

    private fun commit(file: File) {
        val installer = context.packageManager.packageInstaller
        // Forget the previous session first: abandoning it sends its own
        // "aborted" status, which must not be taken for this install's.
        session = -1
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        session = id
        installer.openSession(id).use { s ->
            s.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val status = Intent(ACTION_STATUS).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(context, id, status, flags).intentSender)
        }
    }

    private fun onStatus(intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -2) != session) return
        val outcome = when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmIntent(intent) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Refused while in the background: it waits for the user's tap.
                if (runCatching { context.startActivity(confirm) }.isFailure) confirmation = confirm
                else confirmation = null
                InstallOutcome.AwaitingUser
            }
            PackageInstaller.STATUS_SUCCESS -> InstallOutcome.Started
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallOutcome.Failed("se canceló la instalación")
            else -> InstallOutcome.Failed(
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "el sistema rechazó la instalación ($status)",
            )
        }
        result?.complete(outcome)
    }

    /**
     * The system's confirmation screen from a status broadcast. From Android 13
     * the receiver is not exported, so only the system reaches it. Before, a
     * forged broadcast must not be able to make the app start any activity:
     * the screen is used only if it opens a system app.
     */
    private fun confirmIntent(intent: Intent): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        }
        @Suppress("DEPRECATION")
        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return null
        val target = confirm.resolveActivity(context.packageManager) ?: return null
        val app = runCatching { context.packageManager.getApplicationInfo(target.packageName, 0) }.getOrNull() ?: return null
        return confirm.takeIf { app.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
    }

    private companion object {
        const val ACTION_STATUS = "io.github.danielperezmartinez.titanssh.update.INSTALL_STATUS"
    }
}
