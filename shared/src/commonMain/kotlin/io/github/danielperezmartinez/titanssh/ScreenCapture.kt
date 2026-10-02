package io.github.danielperezmartinez.titanssh

import androidx.compose.runtime.Composable

/**
 * Whether this platform can keep the app's window out of screenshots, screen
 * recordings and the recents thumbnail (Android `FLAG_SECURE`). The desktop
 * window systems offer no equivalent, so the setting is not shown there.
 */
expect fun isScreenCaptureControlSupported(): Boolean

/**
 * Applies [allowed] (`AppSettings.allowScreenCapture`) to the window hosting the
 * composition while it is shown. A no-op where [isScreenCaptureControlSupported]
 * is false.
 */
@Composable
expect fun ScreenCapturePolicy(allowed: Boolean)
