package de.gurum.besseresser.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Prüft vor KI-Anfragen, ob eine aktive Internetverbindung besteht.
 * Als Interface abstrahiert für deterministische Unit-Tests ohne Android-Framework.
 */
fun interface NetworkConnectivityChecker {
    fun isInternetAvailable(): Boolean
}

class AndroidNetworkConnectivityChecker(
    private val context: Context
) : NetworkConnectivityChecker {

    override fun isInternetAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
