package im.gar.titanssh.terminal

/**
 * Whether the app is running on Android, so the "Sesiones" UI can pick the
 * platform-appropriate terminal affordances: an accessory key bar plus a
 * full-screen tab on Android, a two-pane split on desktop (task criteria).
 */
expect fun isAndroidRuntime(): Boolean
