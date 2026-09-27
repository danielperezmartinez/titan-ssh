package io.github.danielperezmartinez.titanssh.terminal

import kotlinx.coroutines.flow.Flow

/**
 * Emits when the platform reports a usable network, so tabs waiting to
 * reconnect try at once instead of at the end of their backoff
 * ([[Reconexión que no se rinde tras un corte largo]]). Platforms without such a
 * signal return an empty flow and rely on the backoff alone.
 */
expect fun networkRestored(): Flow<Unit>
