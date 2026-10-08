package com.androidcast

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Classic Bluetooth serial (RFCOMM / SPP) server. Phones and laptops connect to
 * it and send one text command per line (see [CommandProcessor]). It uses the
 * standard Serial Port Profile UUID so generic "Bluetooth serial terminal" apps
 * can talk to it with no custom phone app.
 *
 * Several clients may be connected at once (e.g. a phone and a laptop).
 */
class BluetoothControlServer(private val processor: CommandProcessor) {

    @Volatile var status: String = "starting"
        private set
    @Volatile private var running = false
    @Volatile private var serverSocket: BluetoothServerSocket? = null

    val deviceName: String
        get() = try {
            BluetoothAdapter.getDefaultAdapter()?.name ?: "?"
        } catch (e: SecurityException) {
            "?"
        }

    fun start() {
        if (running) return
        running = true
        Thread(::acceptLoop, "bt-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
    }

    private fun acceptLoop() {
        while (running) {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                status = "no Bluetooth hardware"
                return
            }
            try {
                if (!adapter.isEnabled) {
                    status = "Bluetooth is off - turning it on"
                    @Suppress("DEPRECATION") adapter.enable()
                    Thread.sleep(3000)
                    continue
                }
                // "Insecure" so clients don't strictly need to be paired first;
                // paired clients connecting securely are accepted as well.
                val ss = adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                serverSocket = ss
                status = "ready"
                while (running) {
                    val socket = ss.accept()
                    Thread({ serve(socket) }, "bt-client").apply { isDaemon = true }.start()
                }
            } catch (e: Exception) {
                Log.w(TAG, "accept loop error, retrying", e)
                status = "error: ${e.message} (retrying)"
                try { serverSocket?.close() } catch (_: IOException) {}
                try { Thread.sleep(3000) } catch (_: InterruptedException) {}
            }
        }
        status = "stopped"
    }

    private fun serve(socket: BluetoothSocket) {
        val who = try { socket.remoteDevice?.name ?: socket.remoteDevice?.address } catch (e: SecurityException) { null }
        Log.i(TAG, "client connected: $who")
        try {
            socket.use {
                val input = socket.inputStream.buffered(64 * 1024)
                val out = socket.outputStream
                send(out, "OK AndroidCast ${BuildConfig.VERSION_NAME} ready - send HELP for commands")
                while (running) {
                    val line = readLine(input) ?: break
                    if (line.isBlank()) continue
                    val reply = try {
                        processor.handle(line.trim(), input) { progress -> send(out, progress) }
                    } catch (e: IOException) {
                        throw e
                    } catch (e: Exception) {
                        "ERR ${e.message ?: e.javaClass.simpleName}"
                    }
                    send(out, reply)
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "client $who disconnected: ${e.message}")
        }
    }

    private fun send(out: OutputStream, text: String) {
        synchronized(out) {
            out.write((text.replace("\n", "\r\n") + "\r\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    companion object {
        private const val TAG = "AndroidCastBT"
        const val SERVICE_NAME = "AndroidCast"
        /** Standard Serial Port Profile UUID. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val MAX_LINE = 4096

        /**
         * Reads one line as raw bytes (not via a Reader) so the stream stays
         * positioned exactly at the start of any binary upload that follows.
         */
        fun readLine(input: InputStream): String? {
            val buf = java.io.ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8")
                if (b == '\n'.code) break
                if (b != '\r'.code && buf.size() < MAX_LINE) buf.write(b)
            }
            return buf.toString("UTF-8")
        }
    }
}
