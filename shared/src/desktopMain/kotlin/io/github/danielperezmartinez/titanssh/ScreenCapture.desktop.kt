package io.github.danielperezmartinez.titanssh

import androidx.compose.runtime.Composable

actual fun isScreenCaptureControlSupported(): Boolean = false

@Composable
actual fun ScreenCapturePolicy(allowed: Boolean) = Unit
