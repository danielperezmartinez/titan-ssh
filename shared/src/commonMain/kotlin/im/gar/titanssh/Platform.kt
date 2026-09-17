package im.gar.titanssh

/**
 * Minimal expect/actual seam that proves the per-platform source sets compile and
 * link. Real platform services (notably `SecretStore`, see ADR-0001) will follow
 * this same expect/actual pattern; this one only reports the running platform.
 */
expect fun platformName(): String
