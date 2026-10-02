package io.github.danielperezmartinez.titanssh

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

actual fun isScreenCaptureControlSupported(): Boolean = true

/**
 * Sets or clears `FLAG_SECURE` on the hosting activity's window. The activity
 * sets it before its first frame, so the window starts protected and only a
 * loaded config that allows capture clears it.
 */
@Composable
actual fun ScreenCapturePolicy(allowed: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity, allowed) {
        if (allowed) {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
