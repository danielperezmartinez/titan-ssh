package im.gar.titanssh.ssh

/**
 * Single `actual` for both JVM targets. `jvmSharedMain` is shared by the Android
 * and desktop targets, so one implementation here satisfies the `expect` for all
 * of them without per-platform duplication.
 */
actual fun createSshConnector(): SshConnector = SshjConnector()
