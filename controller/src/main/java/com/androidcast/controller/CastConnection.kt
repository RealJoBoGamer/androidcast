package com.androidcast.controller

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * A Bluetooth serial connection to the AndroidCast display app. Blocking:
 * only use it from a background thread, one command at a time.
 */
class CastConnection private constructor(private val socket: BluetoothSocket) : Closeable {

    private val input = socket.inputStream.buffered(16 * 1024)
    private val output = socket.outputStream

    /** Sends one command; returns all reply lines (the last starts with OK or ERR). */
    fun command(line: String): List<String> {
        send(line)
        val lines = mutableListOf<String>()
        while (true) {
            val reply = readLine()
            lines += reply
            if (reply.startsWith("OK") || reply.startsWith("ERR")) return lines
        }
    }

    /** Streams a file to the display using the UPLOAD command. Returns the final OK/ERR line. */
    fun upload(name: String, size: Long, data: InputStream, progress: (sent: Long) -> Unit): String {
        send("UPLOAD ${quote(name)} $size")
        val ready = readLine()
        if (ready != "READY") return ready
        val buf = ByteArray(16 * 1024)
        var sent = 0L
        while (sent < size) {
            val n = data.read(buf, 0, minOf(buf.size.toLong(), size - sent).toInt())
            if (n == -1) throw IOException("file ended early")
            output.write(buf, 0, n)
            sent += n
            progress(sent)
        }
        output.flush()
        return readLine()
    }

    private fun send(line: String) {
        output.write((line + "\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun readLine(): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) throw IOException("display disconnected")
            if (b == '\n'.code) return buf.toString("UTF-8")
            if (b != '\r'.code) buf.write(b)
        }
    }

    override fun close() {
        try { socket.close() } catch (_: IOException) {}
    }

    companion object {
        /** Standard Serial Port Profile UUID, as registered by the display app. */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        @SuppressLint("MissingPermission")
        fun open(device: BluetoothDevice): CastConnection {
            BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
            var lastError: IOException? = null
            val factories = listOf<() -> BluetoothSocket>(
                { device.createRfcommSocketToServiceRecord(SPP_UUID) },
                { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            )
            for (make in factories) {
                val socket = make()
                try {
                    socket.connect()
                    val conn = CastConnection(socket)
                    val greeting = conn.readLine()
                    if (!greeting.contains("AndroidCast")) {
                        conn.close()
                        throw IOException("that device isn't running AndroidCast")
                    }
                    return conn
                } catch (e: IOException) {
                    lastError = e
                    try { socket.close() } catch (_: IOException) {}
                }
            }
            throw lastError ?: IOException("could not connect")
        }

        /** Quotes an argument the way the display's command parser expects. */
        fun quote(arg: String): String =
            if (arg.isNotEmpty() && arg.none { it == ' ' || it == '"' || it == '\\' }) arg
            else "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
