package io.github.danielperezmartinez.titanssh.terminal

/**
 * Platform factory for the level-3 [AgentDeployer] (ADR-0008, ADR-0010). Both
 * actuals build on `bundledAgentDeployer` in `jvmShared`: the main targets'
 * `titan-agent` binaries ship as resources, the rest are downloaded from the
 * version's GitHub Release and checked against a digest pinned in the app; the
 * platform only picks the download cache directory. The returned deployer
 * self-degrades (yields nothing, so the tab falls back to level 2/1) when no
 * binary is available for the destination's OS/arch — e.g. a build made
 * without the Go toolchain.
 *
 * Wired into [SessionManager] at app startup ([[Resiliencia nivel 3 agente propio
 * en el destino]]).
 */
expect fun createAgentDeployer(): AgentDeployer?
