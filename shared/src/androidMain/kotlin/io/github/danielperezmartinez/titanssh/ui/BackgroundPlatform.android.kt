package io.github.danielperezmartinez.titanssh.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.danielperezmartinez.titanssh.terminal.KeepAliveRequest

/**
 * How the shared code talks to the app's foreground service (ADR-0023), which
 * lives in the app module next to its notification resources.
 */
object KeepAliveServiceContract {
    /** The service class, started by name from here. */
    const val SERVICE_CLASS = "io.github.danielperezmartinez.titanssh.android.KeepAliveService"

    /** Int extra: how many tabs are connected, for the notification. */
    const val EXTRA_CONNECTED_TABS = "connectedTabs"
}

/**
 * Follows the hosting activity's lifecycle: visible from `ON_START` to
 * `ON_STOP`. Locking the screen stops the activity, so it counts as the
 * background too.
 */
@Composable
actual fun AppVisibilityEffect(onVisible: (Boolean) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onVisible)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> callback(true)
                Lifecycle.Event.ON_STOP -> callback(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/** From `ON_START` to `ON_STOP`, like [AppVisibilityEffect]: locking the screen stops the activity. */
@Composable
actual fun appOnScreen(): Boolean {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    return state.isAtLeast(Lifecycle.State.STARTED)
}

/**
 * Starts the foreground service when [request] first needs it and the app is
 * in front (Android 12+ refuses it from the background), updates its count
 * while it runs, and stops it when no tab needs it or the UI goes away. Asks
 * once for the notification permission (Android 13+); the service works
 * without it, the notification just does not show.
 *
 * The notification is posted again each time the app comes to the front: a
 * permission granted after the service started shows it only then, and the
 * count, which does not change while the UI is stopped, is brought up to date.
 */
@Composable
actual fun KeepAliveService(request: KeepAliveRequest) {
    val context = LocalContext.current
    val app = context.applicationContext
    LaunchedEffect(request) {
        if (request.needed) {
            context.findActivity()?.let(::askNotificationsOnce)
            KeepAliveServiceControl.show(app, request.connectedTabs)
        } else {
            KeepAliveServiceControl.stop(app)
        }
    }
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(request)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && current.needed) {
                KeepAliveServiceControl.show(app, current.connectedTabs)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            KeepAliveServiceControl.stop(app)
        }
    }
}

private object KeepAliveServiceControl {
    private var running = false

    fun show(context: Context, connectedTabs: Int) {
        val intent = Intent()
            .setClassName(context.packageName, KeepAliveServiceContract.SERVICE_CLASS)
            .putExtra(KeepAliveServiceContract.EXTRA_CONNECTED_TABS, connectedTabs)
        // Starting it is only allowed with the app in front; once it runs, the
        // app counts as foreground and a plain start just updates it.
        running = runCatching {
            if (running) context.startService(intent) else context.startForegroundService(intent)
        }.isSuccess || running
    }

    fun stop(context: Context) {
        if (!running) return
        running = false
        context.stopService(Intent().setClassName(context.packageName, KeepAliveServiceContract.SERVICE_CLASS))
    }
}

private const val PREFS = "titan-keepalive"
private const val ASKED_NOTIFICATIONS = "askedNotifications"

/** Shows the system's notification permission prompt the first time only. */
private fun askNotificationsOnce(activity: Activity) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
    val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (prefs.getBoolean(ASKED_NOTIFICATIONS, false)) return
    prefs.edit().putBoolean(ASKED_NOTIFICATIONS, true).apply()
    activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
