package com.androidcast

import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Tiny HTTP server for fast transfers when the controller is on the same Wi-Fi.
 * Every request must carry the token the controller got over Bluetooth (LAN command):
 *
 *   GET /ping                      -> "AndroidCast"
 *   PUT /files/<name>              -> body is the file (Content-Length required)
 *   GET /thumb/<name>?w=<width>    -> JPEG preview
 */
class LanServer(private val app: AndroidCastApp, private val processor: CommandProcessor) {

    /** Port we're listening on, or 0 if the server isn't running. */
    @Volatile var port = 0
        private set

    fun start() {
        Thread(::acceptLoop, "lan-accept").apply { isDaemon = true }.start()
    }

    private fun acceptLoop() {
        val server = try {
            ServerSocket(DEFAULT_PORT)
        } catch (e: IOException) {
            try { ServerSocket(0) } catch (e2: IOException) { Log.w(TAG, "no LAN server", e2); return }
        }
        port = server.localPort
        Log.i(TAG, "LAN server on port $port")
        while (true) {
            val socket = try { server.accept() } catch (e: IOException) { continue }
            Thread({ serve(socket) }, "lan-client").apply { isDaemon = true }.start()
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.use {
                socket.soTimeout = 30_000
                val input = socket.getInputStream().buffered(64 * 1024)
                val out = socket.getOutputStream()

                val request = BluetoothControlServer.readLine(input)?.split(" ") ?: return
                if (request.size < 2) return respond(out, 400, "bad request")
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = BluetoothControlServer.readLine(input) ?: return
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }

                if (headers["x-androidcast-token"] != app.prefs.lanToken) return respond(out, 403, "ERR bad token")

                val method = request[0]
                val path = request[1].substringBefore('?')
                val query = request[1].substringAfter('?', "").split('&')
                    .mapNotNull { it.split('=', limit = 2).takeIf { kv -> kv.size == 2 } }
                    .associate { (k, v) -> k to v }
                fun nameAfter(prefix: String) = URLDecoder.decode(path.removePrefix(prefix), "UTF-8")

                when {
                    method == "GET" && path == "/ping" -> respond(out, 200, "AndroidCast")

                    method == "PUT" && path.startsWith("/files/") -> {
                        val size = headers["content-length"]?.toLongOrNull()?.takeIf { it > 0 }
                            ?: return respond(out, 411, "ERR Content-Length required")
                        val result = try {
                            processor.receiveFile(nameAfter("/files/"), size, input)
                        } catch (e: IOException) {
                            throw e
                        } catch (e: Exception) {
                            "ERR ${e.message}"
                        }
                        respond(out, if (result.startsWith("OK")) 200 else 400, result)
                    }

                    method == "GET" && path.startsWith("/thumb/") -> {
                        val width = query["w"]?.toIntOrNull() ?: 320
                        val jpeg = processor.thumbnailFor(nameAfter("/thumb/"), width)
                            ?: return respond(out, 404, "ERR no preview")
                        respond(out, 200, jpeg, "image/jpeg")
                    }

                    else -> respond(out, 404, "ERR not found")
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "LAN client error: ${e.message}")
        }
    }

    private fun respond(out: OutputStream, code: Int, text: String) =
        respond(out, code, text.toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8")

    private fun respond(out: OutputStream, code: Int, body: ByteArray, type: String) {
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n" +
            "Content-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    companion object {
        private const val TAG = "AndroidCastLAN"
        const val DEFAULT_PORT = 8642
    }
}
