package io.github.danielperezmartinez.titanssh.terminal

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import io.github.danielperezmartinez.titanssh.config.AndroidConfigContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Emits each time Android has a default network again (Wi-Fi back, airplane
 * mode off, a switch to mobile data). Needs `ACCESS_NETWORK_STATE`; reuses the
 * application context the entrypoint installs for the config store.
 */
actual fun networkRestored(): Flow<Unit> = callbackFlow {
    val connectivity = AndroidConfigContext.require()
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            trySend(Unit)
        }
    }
    connectivity.registerDefaultNetworkCallback(callback)
    awaitClose { connectivity.unregisterNetworkCallback(callback) }
}
