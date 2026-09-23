package io.github.danielperezmartinez.titanssh.ssh

/**
 * Returns the platform's [SshConnector]. Both platforms delegate to the same
 * pure-Java sshj implementation in the `jvmShared` source set; the `expect`/
 * `actual` seam only exists so `commonMain` can obtain it without depending on
 * sshj directly.
 */
expect fun createSshConnector(): SshConnector
