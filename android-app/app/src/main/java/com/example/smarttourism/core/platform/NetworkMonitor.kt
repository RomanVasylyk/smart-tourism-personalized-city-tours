package com.example.smarttourism.core.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

data class NetworkSnapshot(
    val isOnline: Boolean,
    val isUnmetered: Boolean
)

object NetworkMonitor {
    private val OfflineSnapshot = NetworkSnapshot(isOnline = false, isUnmetered = false)

    fun isNetworkAvailable(context: Context): Boolean = snapshot(context).isOnline

    fun snapshot(context: Context): NetworkSnapshot {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return OfflineSnapshot
        val capabilities = connectivityManager.activeNetwork
            ?.let { network -> connectivityManager.getNetworkCapabilities(network) }
        return snapshotOf(capabilities)
    }

    fun observe(context: Context): Flow<NetworkSnapshot> = callbackFlow {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (connectivityManager == null) {
            trySend(OfflineSnapshot)
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(snapshotOf(networkCapabilities))
            }

            override fun onLost(network: Network) {
                trySend(OfflineSnapshot)
            }
        }
        trySend(snapshot(context))
        connectivityManager.registerDefaultNetworkCallback(callback)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    private fun snapshotOf(capabilities: NetworkCapabilities?): NetworkSnapshot {
        if (capabilities == null) {
            return OfflineSnapshot
        }
        val online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return NetworkSnapshot(isOnline = online, isUnmetered = online && unmetered)
    }
}
