package com.androidcast

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager

/**
 * Joins a Wi-Fi network without needing the Fire TV settings screens.
 * Uses the legacy WifiManager API, which works on all Fire OS versions
 * because the app targets API 28 (see display/build.gradle.kts).
 */
@Suppress("DEPRECATION")
class WifiSetup(context: Context) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

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

        wifi.disconnect()
        wifi.enableNetwork(id, true)
        wifi.reconnect()
        wifi.saveConfiguration()
        return "OK connecting to '$ssid' - send STATUS in a few seconds to check"
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
        val info = wifi.connectionInfo ?: return "not connected"
        val ip = info.ipAddress
        if (info.networkId == -1 || ip == 0) return "not connected"
        val ipText = "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
        return "${info.ssid.trim('"')} ($ipText)"
    }

    private fun quote(s: String) = "\"$s\""
}
