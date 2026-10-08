package com.androidcast

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper

/**
 * Joins a Wi-Fi network without needing the Fire TV settings screens.
 * Uses the legacy WifiManager API, which works on all Fire OS versions
 * because the app targets API 28 (see display/build.gradle.kts).
 */
@Suppress("DEPRECATION")
class WifiSetup(context: Context) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())

    fun connect(ssid: String, password: String?): String {
        if (!wifi.isWifiEnabled) wifi.isWifiEnabled = true

        val config = WifiConfiguration().apply {
            SSID = quote(ssid)
            if (password.isNullOrEmpty()) {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            } else {
                preSharedKey = quote(password)
            }
        }

        val existing = wifi.configuredNetworks?.firstOrNull { it.SSID == quote(ssid) }
        val id = if (existing != null) {
            config.networkId = existing.networkId
            wifi.updateNetwork(config).takeIf { it != -1 } ?: existing.networkId
        } else {
            wifi.addNetwork(config)
        }
        if (id == -1) return "ERR could not add network '$ssid'"

        // Force the switch to the new network now ("disable others")...
        wifi.disconnect()
        wifi.enableNetwork(id, true)
        wifi.reconnect()
        wifi.saveConfiguration()
        // ...then re-enable every other saved network, so the display still joins them
        // automatically wherever it's taken (and falls back if the password was wrong).
        handler.postDelayed({ enableAllSaved() }, 30_000)
        return "OK connecting to '$ssid' - send STATUS in a few seconds to check"
    }

    /**
     * Makes sure Wi-Fi is on and every saved network is allowed to auto-connect.
     * Called at start-up; Android then joins whichever known network is in range.
     */
    fun enableAllSaved() {
        if (!wifi.isWifiEnabled) wifi.isWifiEnabled = true
        val saved = wifi.configuredNetworks ?: return
        for (net in saved) wifi.enableNetwork(net.networkId, false)
        wifi.saveConfiguration()
        if (ipAddress() == null) wifi.reconnect()
    }

    /** The display's IP address on Wi-Fi, or null when not connected. */
    fun ipAddress(): String? {
        if (!wifi.isWifiEnabled) return null
        val info = wifi.connectionInfo ?: return null
        val ip = info.ipAddress
        if (info.networkId == -1 || ip == 0) return null
        return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
    }

    fun forget(ssid: String): String {
        val net = wifi.configuredNetworks?.firstOrNull { it.SSID == quote(ssid) }
            ?: return "ERR no saved network '$ssid'"
        wifi.removeNetwork(net.networkId)
        wifi.saveConfiguration()
        return "OK forgot '$ssid'"
    }

    fun status(): String {
        if (!wifi.isWifiEnabled) return "off"
        val ip = ipAddress() ?: return "not connected"
        return "${wifi.connectionInfo.ssid.trim('"')} ($ip)"
    }

    private fun quote(s: String) = "\"$s\""
}
