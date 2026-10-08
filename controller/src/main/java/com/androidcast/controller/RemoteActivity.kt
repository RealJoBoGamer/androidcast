package com.androidcast.controller

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.util.concurrent.Executors

/** Remote control for one AndroidCast display, connected over Bluetooth. */
class RemoteActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    /** All Bluetooth I/O happens on this single thread, so commands never overlap. */
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var connection: CastConnection? = null

    private lateinit var address: String
    private var deviceName = ""
    private var blank = false
    private var intervalSeconds = 0
    /** Set while we update switches from STATUS, so that doesn't send commands back. */
    private var updatingSwitches = false

    private lateinit var connectionText: TextView
    private lateinit var showing: TextView
    private lateinit var reconnect: Button
    private lateinit var itemsBox: LinearLayout
    private lateinit var uploadProgress: ProgressBar
    private lateinit var uploadText: TextView
    private lateinit var fit: Switch
    private lateinit var audio: Switch
    private lateinit var intervalButton: Button
    private lateinit var details: TextView
    private lateinit var controls: List<View>

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote)
        address = intent.getStringExtra(EXTRA_ADDRESS) ?: return finish()
        deviceName = try {
            BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)?.name ?: address
        } catch (e: SecurityException) { address }
        title = deviceName

        connectionText = findViewById(R.id.connection)
        showing = findViewById(R.id.showing)
        reconnect = findViewById(R.id.reconnect)
        itemsBox = findViewById(R.id.items)
        uploadProgress = findViewById(R.id.uploadProgress)
        uploadText = findViewById(R.id.uploadText)
        fit = findViewById(R.id.fit)
        audio = findViewById(R.id.audio)
        intervalButton = findViewById(R.id.interval)
        details = findViewById(R.id.details)

        val prev = findViewById<Button>(R.id.prev)
        val next = findViewById<Button>(R.id.next)
        val blankButton = findViewById<Button>(R.id.blank)
        val refresh = findViewById<Button>(R.id.refresh)
        val upload = findViewById<Button>(R.id.upload)
        val wifi = findViewById<Button>(R.id.wifi)
        val fetch = findViewById<Button>(R.id.fetch)
        controls = listOf(prev, next, blankButton, refresh, upload, fit, audio, intervalButton, wifi, fetch)

        prev.setOnClickListener { send("PREV") }
        next.setOnClickListener { send("NEXT") }
        blankButton.setOnClickListener { send("BLANK ${if (blank) "off" else "on"}") { blank = !blank } }
        refresh.setOnClickListener { refreshAll() }
        reconnect.setOnClickListener { connect() }
        upload.setOnClickListener { pickFiles() }
        fit.setOnCheckedChangeListener { _, on -> if (!updatingSwitches) send("FIT ${if (on) "cover" else "contain"}") }
        audio.setOnCheckedChangeListener { _, on -> if (!updatingSwitches) send("AUDIO ${if (on) "on" else "off"}") }
        intervalButton.setOnClickListener { askInterval() }
        wifi.setOnClickListener { askWifi() }
        fetch.setOnClickListener { askFetch() }

        connect()
    }

    override fun onDestroy() {
        connection?.close()
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---- Connection --------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun connect() {
        setConnected(false, "Connecting to $deviceName…")
        reconnect.visibility = View.GONE
        worker.execute {
            try {
                connection?.close()
                val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
                connection = CastConnection.open(device)
                ui.post { setConnected(true, "Connected to $deviceName") }
                refreshAllNow()
            } catch (e: Exception) {
                ui.post {
                    setConnected(false, "Couldn't connect: ${e.message}\n\nIs AndroidCast open on $deviceName?")
                    reconnect.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setConnected(connected: Boolean, text: String) {
        connectionText.text = text
        controls.forEach { it.isEnabled = connected }
    }

    private fun lostConnection(e: IOException) {
        connection?.close()
        connection = null
        ui.post {
            setConnected(false, "Disconnected: ${e.message}")
            reconnect.visibility = View.VISIBLE
        }
    }

    /** Sends a command in the background, then refreshes "now showing". */
    private fun send(command: String, onOk: () -> Unit = {}) {
        worker.execute {
            val conn = connection ?: return@execute
            try {
                val reply = conn.command(command)
                val last = reply.last()
                ui.post {
                    if (last.startsWith("OK")) onOk() else toast(last.removePrefix("ERR").trim())
                }
                refreshStatusNow(conn)
            } catch (e: IOException) {
                lostConnection(e)
            }
        }
    }

    private fun refreshAll() = worker.execute { refreshAllNow() }

    /** Worker thread only. */
    private fun refreshAllNow() {
        val conn = connection ?: return
        try {
            refreshStatusNow(conn)
            val list = conn.command("LIST")
            ui.post { showItems(list.dropLast(1).mapNotNull(::parseItem)) }
        } catch (e: IOException) {
            lostConnection(e)
        }
    }

    private fun refreshStatusNow(conn: CastConnection) {
        val lines = conn.command("STATUS")
        val status = lines.mapNotNull { line ->
            val i = line.indexOf(':')
            if (i < 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.toMap()
        ui.post { showStatus(status, lines.dropLast(1)) }
    }

    // ---- UI updates --------------------------------------------------------------------

    private fun showStatus(status: Map<String, String>, raw: List<String>) {
        showing.text = status["showing"]?.let { "Showing: $it" } ?: ""
        updatingSwitches = true
        fit.isChecked = status["fit"] == "cover"
        audio.isChecked = status["audio"] == "on"
        updatingSwitches = false
        intervalSeconds = status["interval"]?.removeSuffix("s")?.toIntOrNull() ?: 0
        intervalButton.text = "Auto-advance: ${if (intervalSeconds == 0) "off" else "every ${intervalSeconds}s"}"
        details.text = raw.joinToString("\n") { it.trim() }
    }

    private data class Item(val number: Int, val name: String, val size: String)

    private fun parseItem(line: String): Item? =
        ITEM_LINE.matchEntire(line)?.destructured?.let { (n, name, size) -> Item(n.toInt(), name, size) }

    private fun showItems(items: List<Item>) {
        itemsBox.removeAllViews()
        if (items.isEmpty()) {
            itemsBox.addView(TextView(this).apply {
                text = "Nothing on the display yet - upload some images or videos."
                setPadding(0, dp(8), 0, dp(8))
            })
        }
        for (item in items) {
            itemsBox.addView(TextView(this).apply {
                text = "${item.number}.  ${item.name}   (${item.size})"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(dp(4), dp(12), dp(4), dp(12))
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { send("GOTO ${item.number}") }
                setOnLongClickListener { confirmDelete(item.name); true }
            })
        }
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete $name?")
            .setMessage("It will be removed from the display.")
            .setPositiveButton("Delete") { _, _ ->
                worker.execute {
                    val conn = connection ?: return@execute
                    try {
                        val r = conn.command("DELETE ${CastConnection.quote(name)}").last()
                        ui.post { toast(r) }
                        refreshAllNow()
                    } catch (e: IOException) { lostConnection(e) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Dialogs -----------------------------------------------------------------------

    private fun askInterval() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "seconds (0 = off)"
            setText(intervalSeconds.toString())
        }
        AlertDialog.Builder(this)
            .setTitle("Auto-advance every…")
            .setView(padded(input))
            .setPositiveButton("Set") { _, _ -> send("INTERVAL ${input.text.toString().toIntOrNull() ?: 0}") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askWifi() {
        val ssid = EditText(this).apply { hint = "Network name"; isSingleLine = true }
        val password = EditText(this).apply {
            hint = "Password (blank for open network)"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ssid); addView(password)
        }
        AlertDialog.Builder(this)
            .setTitle("Connect the display to Wi-Fi")
            .setView(padded(box))
            .setPositiveButton("Connect") { _, _ ->
                val name = ssid.text.toString()
                if (name.isBlank()) return@setPositiveButton
                val cmd = "WIFI ${CastConnection.quote(name)} ${CastConnection.quote(password.text.toString())}"
                worker.execute {
                    val conn = connection ?: return@execute
                    try {
                        val reply = conn.command(cmd).last()
                        ui.post { toast(reply) }
                        // Give it a moment to join, then show the new Wi-Fi status.
                        Thread.sleep(6000)
                        refreshStatusNow(conn)
                    } catch (e: IOException) { lostConnection(e) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askFetch() {
        val url = EditText(this).apply {
            hint = "https://…/background.mp4"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("Download onto the display")
            .setMessage("The display downloads the file itself over its Wi-Fi - much faster than Bluetooth for videos. Use a direct download link.")
            .setView(padded(url))
            .setPositiveButton("Download") { _, _ ->
                val link = url.text.toString().trim()
                if (link.isEmpty()) return@setPositiveButton
                showUploadState("Display is downloading…", indeterminate = true)
                worker.execute {
                    val conn = connection ?: return@execute
                    try {
                        val reply = conn.command("FETCH ${CastConnection.quote(link)}").last()
                        ui.post { hideUploadState(); toast(reply) }
                        refreshAllNow()
                    } catch (e: IOException) {
                        ui.post { hideUploadState() }
                        lostConnection(e)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Uploads -----------------------------------------------------------------------

    private fun pickFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        @Suppress("DEPRECATION")
        startActivityForResult(intent, PICK_FILES)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_FILES || resultCode != RESULT_OK || data == null) return
        val uris = data.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } }
            ?: listOfNotNull(data.data)
        if (uris.isNotEmpty()) uploadAll(uris)
    }

    private fun uploadAll(uris: List<Uri>) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showUploadState("Preparing…", indeterminate = false)
        worker.execute {
            val conn = connection
            val results = mutableListOf<String>()
            try {
                if (conn == null) return@execute
                uris.forEachIndexed { i, uri ->
                    val (name, size) = describe(uri)
                    if (size <= 0) {
                        results += "$name: couldn't read file size"
                        return@forEachIndexed
                    }
                    val started = System.currentTimeMillis()
                    val reply = contentResolver.openInputStream(uri)!!.use { stream ->
                        conn.upload(name, size, stream) { sent ->
                            val secs = (System.currentTimeMillis() - started) / 1000.0
                            val rate = if (secs > 0) sent / 1024 / secs else 0.0
                            ui.post {
                                uploadProgress.progress = (sent * 1000 / size).toInt()
                                uploadText.text = "Uploading ${i + 1}/${uris.size}: $name\n" +
                                    "${sent / 1024} of ${size / 1024} KB  (%.0f KB/s)".format(rate)
                            }
                        }
                    }
                    results += reply
                }
                ui.post { toast(results.joinToString("\n")) }
                refreshAllNow()
            } catch (e: IOException) {
                lostConnection(e)
            } finally {
                ui.post {
                    hideUploadState()
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    private fun describe(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "upload"
        var size = -1L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size <= 0) {
            size = try {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
            } catch (e: Exception) { -1 }
        }
        return name to size
    }

    private fun showUploadState(text: String, indeterminate: Boolean) {
        uploadProgress.visibility = View.VISIBLE
        uploadProgress.isIndeterminate = indeterminate
        uploadProgress.progress = 0
        uploadText.visibility = View.VISIBLE
        uploadText.text = text
    }

    private fun hideUploadState() {
        uploadProgress.visibility = View.GONE
        uploadText.visibility = View.GONE
    }

    // ---- Helpers -----------------------------------------------------------------------

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun padded(view: View) = LinearLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    companion object {
        const val EXTRA_ADDRESS = "address"
        private const val PICK_FILES = 1
        /** Matches the display's LIST lines: "   3  intro.mp4  (4.2 MB)". */
        internal val ITEM_LINE = Regex("""^\s*(\d+)\s{2}(.+)\s{2}\((.+)\)$""")
    }
}
