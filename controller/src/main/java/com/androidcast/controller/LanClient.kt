package com.androidcast.controller

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fast transfers straight to the display over the local Wi-Fi network, using the
 * address and token the display handed out over Bluetooth (LAN command).
 */
class LanClient(context: Context, val ip: String, private val port: Int, private val token: String) {

    private val connectivity = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** True if the display answers at its address, i.e. we're on the same network. */
    fun ping(): Boolean = try {
        val c = open("/ping", timeoutMs = 2000)
        val ok = c.responseCode == 200 && c.inputStream.use { it.readBytes() }.decodeToString() == "AndroidCast"
        c.disconnect()
        ok
    } catch (e: IOException) {
        false
    }

    fun upload(name: String, size: Long, data: InputStream, progress: (sent: Long) -> Unit): String {
        val c = open("/files/" + Uri.encode(name), timeoutMs = 10_000)
        try {
            c.requestMethod = "PUT"
            c.doOutput = true
            c.setFixedLengthStreamingMode(size)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.outputStream.use { out ->
                val buf = ByteArray(64 * 1024)
                var sent = 0L
                while (sent < size) {
                    val n = data.read(buf, 0, minOf(buf.size.toLong(), size - sent).toInt())
                    if (n == -1) throw IOException("file ended early")
                    out.write(buf, 0, n)
                    sent += n
                    progress(sent)
                }
            }
            val stream = if (c.responseCode < 400) c.inputStream else c.errorStream
            return stream?.use { it.readBytes().decodeToString() } ?: "ERR HTTP ${c.responseCode}"
        } finally {
            c.disconnect()
        }
    }

    fun thumbnail(name: String, width: Int): ByteArray? = try {
        val c = open("/thumb/" + Uri.encode(name) + "?w=$width", timeoutMs = 10_000)
        try {
            if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
        } finally {
            c.disconnect()
        }
    } catch (e: IOException) {
        null
    }

    private fun open(path: String, timeoutMs: Int): HttpURLConnection {
        val url = URL("http://$ip:$port$path")
        // Go out over Wi-Fi even if Android prefers mobile data (e.g. Wi-Fi without internet).
        val c = (wifiNetwork()?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = 30_000
        c.setRequestProperty("X-AndroidCast-Token", token)
        return c
    }

    @Suppress("DEPRECATION")
    private fun wifiNetwork(): Network? = connectivity.allNetworks.firstOrNull {
        connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
}
