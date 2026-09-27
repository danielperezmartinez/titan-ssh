package io.github.danielperezmartinez.titanssh.terminal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** No portable JVM signal for network changes: desktop tabs rely on the backoff. */
actual fun networkRestored(): Flow<Unit> = emptyFlow()
