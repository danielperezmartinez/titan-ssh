package io.github.danielperezmartinez.titanssh.terminal

/**
 * Platform factory for the level-3 [AgentDeployer] (ADR-0008). The `jvmShared`
 * actual bundles the `titan-agent` binaries as resources and installs the right
 * one on the destination on first use. The returned deployer self-degrades
 * (yields no path, so the tab falls back to level 2/1) when no binary is bundled
 * for the destination's OS/arch — e.g. a build made without the Go toolchain.
 *
 * Wired into [SessionManager] at app startup ([[Resiliencia nivel 3 agente propio
 * en el destino]]).
 */
expect fun createAgentDeployer(): AgentDeployer?
