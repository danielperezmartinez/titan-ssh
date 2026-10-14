package io.github.danielperezmartinez.titanssh.terminal

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import io.github.danielperezmartinez.titanssh.config.AndroidConfigContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Emits each time Android has a default network again (Wi-Fi back, airplane
 * mode off, a switch to mobile data). Needs `ACCESS_NETWORK_STATE`; reuses the
 * application context the entrypoint installs for the config store.
 */
actual fun networkRestored(): Flow<Unit> = callbackFlow {
    val connectivity = connectivity()
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            trySend(Unit)
        }
    }
    connectivity.registerDefaultNetworkCallback(callback)
    awaitClose { connectivity.unregisterNetworkCallback(callback) }
}

/**
 * Whether Android blocks the app's default network: in the background, behind
 * a locked screen or in Doze, unless a foreground service keeps it (ADR-0023).
 * Android reports it from API 29; before that the flow never emits.
 */
actual fun networkBlocked(): Flow<Boolean> = callbackFlow {
    val connectivity = connectivity()
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            trySend(blocked)
        }

        override fun onLost(network: Network) {
            // No default network is not a block: attempts fail on their own.
            trySend(false)
        }
    }
    connectivity.registerDefaultNetworkCallback(callback)
    awaitClose { connectivity.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()

private fun connectivity(): ConnectivityManager =
    AndroidConfigContext.require().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
