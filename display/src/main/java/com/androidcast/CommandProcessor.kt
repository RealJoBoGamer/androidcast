package com.androidcast

import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Bluetooth text protocol. One command per line; arguments containing
 * spaces go in double quotes. Every command ends with exactly one line that
 * starts with "OK" or "ERR"; any lines before it are informational.
 */
class CommandProcessor(private val app: AndroidCastApp) {

    private val main = Handler(Looper.getMainLooper())

    fun handle(line: String, input: InputStream, progress: (String) -> Unit): String {
        val args = tokenize(line)
        if (args.isEmpty()) return "ERR empty command"
        val cmd = args[0].uppercase()
        val rest = args.drop(1)

        return when (cmd) {
            "HELP", "?" -> HELP
            "PING" -> "OK pong"
            "STATUS" -> status()
            "LIST", "LS" -> list()

            "NEXT", "N", ">" -> onPlayer { it.next(); "OK ${it.nowShowing()}" }
            "PREV", "P", "<" -> onPlayer { it.previous(); "OK ${it.nowShowing()}" }
            "GOTO", "G" -> {
                val n = rest.firstOrNull()?.toIntOrNull() ?: return "ERR usage: GOTO <number>"
                onPlayer { if (it.showIndex(n - 1)) "OK ${it.nowShowing()}" else "ERR no item $n" }
            }
            "SHOW" -> {
                val name = rest.firstOrNull() ?: return "ERR usage: SHOW <filename>"
                onPlayer { if (it.showName(name)) "OK ${it.nowShowing()}" else "ERR no file '$name'" }
            }
            "BLANK" -> {
                val on = parseOnOff(rest.firstOrNull() ?: "on") ?: return "ERR usage: BLANK on|off"
                onPlayer { it.setBlank(on); "OK blank ${if (on) "on" else "off"}" }
            }

            "INTERVAL" -> {
                val s = rest.firstOrNull()?.toIntOrNull()?.takeIf { it >= 0 }
                    ?: return "ERR usage: INTERVAL <seconds> (0 = never auto-advance)"
                app.prefs.intervalSeconds = s
                applySettings()
                "OK interval ${if (s == 0) "off" else "${s}s"}"
            }
            "FIT" -> {
                app.prefs.cover = when (rest.firstOrNull()?.lowercase()) {
                    "cover", "fill", "crop" -> true
                    "contain", "fit", "letterbox" -> false
                    else -> return "ERR usage: FIT cover|contain"
                }
                applySettings()
                "OK fit ${if (app.prefs.cover) "cover" else "contain"}"
            }
            "AUDIO" -> {
                app.prefs.audio = parseOnOff(rest.firstOrNull()) ?: return "ERR usage: AUDIO on|off"
                applySettings()
                "OK audio ${if (app.prefs.audio) "on" else "off"}"
            }
            "AUTOSTART" -> {
                app.prefs.autostart = parseOnOff(rest.firstOrNull()) ?: return "ERR usage: AUTOSTART on|off"
                "OK autostart ${if (app.prefs.autostart) "on" else "off"}"
            }

            "WIFI" -> {
                val ssid = rest.getOrNull(0) ?: return "ERR usage: WIFI \"<network name>\" \"<password>\""
                app.wifi.connect(ssid, rest.getOrNull(1))
            }
            "FORGETWIFI" -> app.wifi.forget(rest.firstOrNull() ?: return "ERR usage: FORGETWIFI \"<network name>\"")

            "UPLOAD", "PUT" -> upload(rest, input, progress)
            "FETCH", "GET" -> fetch(rest, progress)
            "DELETE", "RM" -> {
                val file = app.library.find(rest.firstOrNull() ?: return "ERR usage: DELETE <filename>")
                    ?: return "ERR no such file"
                if (!file.delete()) return "ERR could not delete"
                reload()
                "OK deleted ${file.name}"
            }
            "RENAME", "MV" -> {
                if (rest.size < 2) return "ERR usage: RENAME <old> <new>"
                val from = app.library.find(rest[0]) ?: return "ERR no such file"
                val to = app.library.safeFile(rest[1])
                if (to.exists()) return "ERR '${to.name}' already exists"
                if (!from.renameTo(to)) return "ERR rename failed"
                reload()
                "OK renamed to ${to.name}"
            }
            else -> "ERR unknown command '${args[0]}' - send HELP"
        }
    }

    private fun status(): String {
        val showing = onMain { it?.nowShowing() } ?: "player not on screen"
        val p = app.prefs
        return listOf(
            "  showing:   $showing",
            "  items:     ${app.library.items().size}",
            "  wifi:      ${app.wifi.status()}",
            "  bluetooth: ${app.bluetooth.deviceName}",
            "  interval:  ${if (p.intervalSeconds == 0) "off" else "${p.intervalSeconds}s"}",
            "  fit:       ${if (p.cover) "cover" else "contain"}",
            "  audio:     ${if (p.audio) "on" else "off"}",
            "  autostart: ${if (p.autostart) "on" else "off"}",
            "  free:      ${app.library.dir.usableSpace / MB} MB",
            "OK",
        ).joinToString("\n")
    }

    private fun list(): String {
        val items = app.library.items()
        val lines = items.mapIndexed { i, f -> "  %2d  %s  (%s)".format(i + 1, f.name, size(f.length())) }
        return (lines + "OK ${items.size} item(s)").joinToString("\n")
    }

    /**
     * UPLOAD <name> <bytes>
     * The device answers "READY", then the client sends exactly <bytes> raw bytes.
     */
    private fun upload(rest: List<String>, input: InputStream, progress: (String) -> Unit): String {
        if (rest.size < 2) return "ERR usage: UPLOAD <filename> <size-in-bytes>"
        val size = rest[1].toLongOrNull()?.takeIf { it > 0 } ?: return "ERR bad size"
        val target = app.library.safeFile(rest[0])
        if (size > app.library.dir.usableSpace - 20 * MB) return "ERR not enough free space"

        val part = app.library.partFile(target)
        progress("READY")
        try {
            part.outputStream().buffered(64 * 1024).use { out ->
                val buf = ByteArray(16 * 1024)
                var remaining = size
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n == -1) throw IOException("connection closed during upload")
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
        } catch (e: IOException) {
            part.delete()
            throw e
        }
        return finish(part, target)
    }

    /** FETCH <url> [name] - download a file over Wi-Fi (much faster than Bluetooth for videos). */
    private fun fetch(rest: List<String>, progress: (String) -> Unit): String {
        val url = rest.firstOrNull() ?: return "ERR usage: FETCH <url> [filename]"
        var conn = URL(url).openConnection() as HttpURLConnection
        // HttpURLConnection won't follow http<->https redirects on its own.
        var redirects = 0
        while (true) {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            val code = conn.responseCode
            if (code in 200..299) break
            val location = conn.getHeaderField("Location")
            val base = conn.url
            conn.disconnect()
            if (code !in 300..399) return "ERR HTTP $code"
            if (++redirects > 5) return "ERR too many redirects"
            if (location == null) return "ERR redirect without location"
            conn = URL(base, location).openConnection() as HttpURLConnection
        }
        val name = rest.getOrNull(1)
            ?: conn.url.path.substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: return "ERR give a filename: FETCH <url> <filename>"
        val target = app.library.safeFile(name)
        val part = app.library.partFile(target)
        val total = conn.contentLength.toLong()
        try {
            conn.inputStream.use { inp ->
                part.outputStream().buffered(64 * 1024).use { out ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport >= 5 * MB) {
                            lastReport = done
                            progress("  downloaded ${size(done)}" + if (total > 0) " of ${size(total)}" else "")
                        }
                    }
                }
            }
        } catch (e: IOException) {
            part.delete()
            return "ERR download failed: ${e.message}"
        } finally {
            conn.disconnect()
        }
        return finish(part, target)
    }

    private fun finish(part: File, target: File): String {
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.delete()
            return "ERR could not save ${target.name}"
        }
        reload()
        return "OK saved ${target.name} (${size(target.length())})"
    }

    private fun reload() = onMain { it?.reload() }
    private fun applySettings() = onMain { it?.applySettings() }

    private fun onPlayer(block: (PlayerControl) -> String): String =
        onMain { player -> player?.let(block) } ?: "ERR player is not on screen - open AndroidCast on the TV"

    /** Runs [block] on the main thread and waits for its result. */
    private fun <T> onMain(block: (PlayerControl?) -> T): T? {
        var result: T? = null
        val done = CountDownLatch(1)
        main.post {
            try { result = block(app.player) } finally { done.countDown() }
        }
        done.await(5, TimeUnit.SECONDS)
        return result
    }

    companion object {
        private const val MB = 1024L * 1024L

        private fun size(bytes: Long) =
            if (bytes >= MB) "%.1f MB".format(bytes / MB.toDouble()) else "${(bytes + 1023) / 1024} KB"

        private fun parseOnOff(s: String?): Boolean? = when (s?.lowercase()) {
            "on", "1", "true", "yes" -> true
            "off", "0", "false", "no" -> false
            else -> null
        }

        /** Splits on spaces, honouring "double quotes" and backslash escapes. */
        fun tokenize(line: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var inQuotes = false
            var hasToken = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '\\' && i + 1 < line.length -> { cur.append(line[++i]); hasToken = true }
                    c == '"' -> { inQuotes = !inQuotes; hasToken = true }
                    c.isWhitespace() && !inQuotes -> {
                        if (hasToken) out += cur.toString()
                        cur.setLength(0); hasToken = false
                    }
                    else -> { cur.append(c); hasToken = true }
                }
                i++
            }
            if (hasToken) out += cur.toString()
            return out
        }

        private val HELP = """
            |  Playback:  NEXT | PREV | GOTO <n> | SHOW <file> | BLANK on|off
            |  Settings:  INTERVAL <sec> (0=off) | FIT cover|contain | AUDIO on|off | AUTOSTART on|off
            |  Files:     LIST | UPLOAD <file> <bytes> | FETCH <url> [file] | DELETE <file> | RENAME <old> <new>
            |  Wi-Fi:     WIFI "<network>" "<password>" | FORGETWIFI "<network>"
            |  Info:      STATUS | PING | HELP
            |  Put names with spaces in "double quotes".
            |OK
        """.trimMargin()
    }
}
