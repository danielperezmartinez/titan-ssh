package io.github.danielperezmartinez.titanssh.ssh

/**
 * Builds the platform's persistent [KnownHostsStore] for TOFU host-key
 * verification (ADR-0005). Both platforms are JVM and back it with the shared
 * file store ([FileKnownHostsStore] in `jvmShared`); only the base directory is
 * platform-specific (Android `filesDir`, desktop the OS config dir), mirroring
 * how [io.github.danielperezmartinez.titanssh.config.createConfigStore] picks its location.
 */
expect fun createKnownHostsStore(): KnownHostsStore
