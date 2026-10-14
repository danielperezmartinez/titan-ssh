package io.github.danielperezmartinez.titanssh.terminal

import kotlinx.coroutines.flow.Flow

/**
 * Emits when the platform reports a usable network, so tabs waiting to
 * reconnect try at once instead of at the end of their backoff
 * ([[Reconexión que no se rinde tras un corte largo]]). Platforms without such a
 * signal return an empty flow and rely on the backoff alone.
 */
expect fun networkRestored(): Flow<Unit>

/**
 * Emits whether the platform blocks this app's network access: Android does so
 * for an app in the background or behind a locked screen, without freezing it
 * (ADR-0023). Tabs do not try to connect while it is blocked. Platforms that
 * never block an app return an empty flow.
 */
expect fun networkBlocked(): Flow<Boolean>
