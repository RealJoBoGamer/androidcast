package com.androidcast.controller

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/** What this phone knows about its own Wi-Fi, plus passwords you've sent to displays before. */
class PhoneWifi(private val context: Context) {

    private val prefs = context.getSharedPreferences("wifi", Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.any {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    /** Android only reveals the network name with location permission (and location turned on). */
    fun hasLocationPermission() =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Name of the Wi-Fi network the phone is on, or null if unknown. */
    @Suppress("DEPRECATION")
    fun currentSsid(): String? {
        if (!isOnWifi() || !hasLocationPermission()) return null
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ssid = wm.connectionInfo?.ssid?.trim('"') ?: return null
        return ssid.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
    }

    /**
     * Android never lets apps read saved Wi-Fi passwords, so we remember the ones you type
     * here (stored only in this app's private storage on the phone).
     */
    fun savedPassword(ssid: String): String? = prefs.getString("pw:$ssid", null)

    fun savePassword(ssid: String, password: String) = prefs.edit().putString("pw:$ssid", password).apply()

    var askedForLocation: Boolean
        get() = prefs.getBoolean("asked_location", false)
        set(v) = prefs.edit().putBoolean("asked_location", v).apply()
}
