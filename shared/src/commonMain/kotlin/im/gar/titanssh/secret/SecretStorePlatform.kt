package im.gar.titanssh.secret

/**
 * Builds the [SecretStore] backed by the running platform's native secret store.
 *
 * This is the `expect`/`actual` seam mandated by ADR-0001: `commonMain` only
 * knows the [SecretStore] contract; each platform provides its own `actual`
 * (Android KeyStore, Windows Credential Store, Linux Secret Service).
 *
 * Platform dependencies that cannot be expressed in common code (notably the
 * Android [android.content.Context]) are supplied by the platform entrypoint
 * before this is called; see each `actual` for how.
 */
expect fun createSecretStore(): SecretStore
