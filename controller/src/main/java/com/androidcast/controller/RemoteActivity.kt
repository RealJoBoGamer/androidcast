package com.androidcast.controller

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/** Remote control for one AndroidCast display, connected over Bluetooth. */
class RemoteActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    /** All Bluetooth I/O happens on this single thread, so commands never overlap. */
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var connection: CastConnection? = null
    /** Set when the display is reachable on the same Wi-Fi: uploads and previews go this way. */
    @Volatile private var lan: LanClient? = null
    /** Wi-Fi transfers run here, so they don't hold up Bluetooth button presses. */
    private val lanPool = Executors.newFixedThreadPool(2)
    private lateinit var phoneWifi: PhoneWifi
    /** The display's Wi-Fi status line from STATUS, e.g. "Home (192.168.1.50)" or "not connected". */
    private var displayWifi: String? = null
    /** Only offer to send the phone's Wi-Fi once per visit. */
    private var wifiOffered = false

    private lateinit var address: String
    private var deviceName = ""
    private var blank = false
    private var intervalSeconds = 0
    /** Set while we update switches from STATUS, so that doesn't send commands back. */
    private var updatingSwitches = false

    /** Number (1-based) of the background on the TV, for highlighting it in the grid. */
    private var currentNumber = -1
    private val cells = mutableMapOf<Int, View>()
    private val thumbCache = mutableMapOf<String, Bitmap>()
    /** Bumped whenever the grid is rebuilt, so slow thumbnail loads for an old grid are dropped. */
    @Volatile private var gridGeneration = 0

    private lateinit var connectionText: TextView
    private lateinit var showing: TextView
    private lateinit var reconnect: Button
    private lateinit var itemsBox: LinearLayout
    private lateinit var uploadProgress: ProgressBar
    private lateinit var uploadText: TextView
    private lateinit var fit: Switch
    private lateinit var audio: Switch
    private lateinit var loop: Switch
    private lateinit var quiet: Switch
    private lateinit var autostart: Switch
    private lateinit var transport: TextView
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
        loop = findViewById(R.id.loop)
        quiet = findViewById(R.id.quiet)
        autostart = findViewById(R.id.autostart)
        transport = findViewById(R.id.transport)
        phoneWifi = PhoneWifi(this)
        intervalButton = findViewById(R.id.interval)
        details = findViewById(R.id.details)

        val prev = findViewById<Button>(R.id.prev)
        val next = findViewById<Button>(R.id.next)
        val blankButton = findViewById<Button>(R.id.blank)
        val refresh = findViewById<Button>(R.id.refresh)
        val upload = findViewById<Button>(R.id.upload)
        val wifi = findViewById<Button>(R.id.wifi)
        val fetch = findViewById<Button>(R.id.fetch)
        controls = listOf(prev, next, blankButton, refresh, upload, fit, audio, loop, quiet, autostart, intervalButton, wifi, fetch)

        prev.setOnClickListener { send("PREV") }
        next.setOnClickListener { send("NEXT") }
        blankButton.setOnClickListener { send("BLANK ${if (blank) "off" else "on"}") { blank = !blank } }
        refresh.setOnClickListener { refreshAll() }
        reconnect.setOnClickListener { connect() }
        upload.setOnClickListener { pickFiles() }
        fit.setOnCheckedChangeListener { _, on -> if (!updatingSwitches) send("FIT ${if (on) "cover" else "contain"}") }
        audio.setOnCheckedChangeListener { _, on -> if (!updatingSwitches) send("AUDIO ${if (on) "on" else "off"}") }
        loop.setOnCheckedChangeListener { _, on -> if (!updatingSwitches) send("LOOP ${if (on) "on" else "off"}") }
        quiet.setOnCheckedChangeListener { _, on -> if (!updatingSwitches) send("QUIET ${if (on) "on" else "off"}") }
        autostart.setOnCheckedChangeListener { _, on -> if (!updatingSwitches) send("AUTOSTART ${if (on) "on" else "off"}") }
        intervalButton.setOnClickListener { askInterval() }
        wifi.setOnClickListener { askWifi(prefillSsid = phoneWifi.currentSsid()) }
        fetch.setOnClickListener { askFetch() }

        connect()
    }

    override fun onDestroy() {
        connection?.close()
        worker.shutdownNow()
        lanPool.shutdownNow()
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
            checkLanNow(conn)
            val list = conn.command("LIST")
            ui.post { showItems(list.dropLast(1).mapNotNull(::parseItem)) }
        } catch (e: IOException) {
            lostConnection(e)
        }
    }

    /**
     * Worker thread. Asks the display for its Wi-Fi address and checks this phone can reach it,
     * i.e. that both are on the same network. If so, files and previews go over Wi-Fi.
     */
    private fun checkLanNow(conn: CastConnection) {
        val reply = conn.command("LAN").last()  // "OK <ip> <port> <token>", or ERR (no Wi-Fi / older display)
        val parts = reply.split(" ")
        val client = if (reply.startsWith("OK") && parts.size >= 4 && phoneWifi.isOnWifi()) {
            parts[2].toIntOrNull()?.let { port -> LanClient(this, parts[1], port, parts[3]) }
        } else null
        lan = client?.takeIf { it.ping() }
        ui.post { showTransport() }
    }

    private fun showTransport() {
        val wifiDown = displayWifi == null || displayWifi == "off" || displayWifi == "not connected"
        transport.text = when {
            lan != null -> "⚡ Same Wi-Fi as the display (${lan?.ip}): files go over Wi-Fi"
            wifiDown -> "Display isn't on Wi-Fi: files go over Bluetooth (slow)"
            !phoneWifi.isOnWifi() -> "This phone isn't on Wi-Fi: files go over Bluetooth (slow)"
            else -> "Display is on a different network: files go over Bluetooth (slow)"
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
        currentNumber = status["showing"]?.substringBefore('/')?.toIntOrNull() ?: -1
        applyHighlight()
        updatingSwitches = true
        fit.isChecked = status["fit"] == "cover"
        audio.isChecked = status["audio"] == "on"
        loop.isChecked = status["loop"] == "on"
        quiet.isChecked = status["quiet"]?.startsWith("on") == true
        autostart.isChecked = status["autostart"] == "on"
        updatingSwitches = false
        displayWifi = status["wifi"]
        showTransport()
        if ((displayWifi == "off" || displayWifi == "not connected") && !wifiOffered) {
            wifiOffered = true
            offerWifi()
        }
        intervalSeconds = status["interval"]?.removeSuffix("s")?.toIntOrNull() ?: 0
        intervalButton.text = "Auto-advance: ${if (intervalSeconds == 0) "off" else "every ${intervalSeconds}s"}"
        details.text = raw.joinToString("\n") { it.trim() }
    }

    private data class Item(val number: Int, val name: String, val size: String)

    private fun parseItem(line: String): Item? =
        ITEM_LINE.matchEntire(line)?.destructured?.let { (n, name, size) -> Item(n.toInt(), name, size) }

    private fun showItems(items: List<Item>) {
        itemsBox.removeAllViews()
        cells.clear()
        val gen = ++gridGeneration
        if (items.isEmpty()) {
            itemsBox.addView(TextView(this).apply {
                text = "Nothing on the display yet - upload some images or videos."
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        for (row in items.chunked(COLUMNS)) {
            val rowLayout = LinearLayout(this)
            for (item in row) rowLayout.addView(makeCell(item, gen), cellParams())
            repeat(COLUMNS - row.size) { rowLayout.addView(View(this), cellParams()) }
            itemsBox.addView(rowLayout)
        }
        applyHighlight()
    }

    private fun cellParams() =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }

    /** One background: 16:9 preview with its number and name, tap to show, long-press to delete. */
    private fun makeCell(item: Item, gen: Int): View {
        val frame = AspectFrameLayout(this).apply {
            setPadding(dp(3), dp(3), dp(3), dp(3))
            setOnClickListener { send("GOTO ${item.number}") }
            setOnLongClickListener { confirmDelete(item.name); true }
        }
        val match = FrameLayout.LayoutParams.MATCH_PARENT
        val wrap = FrameLayout.LayoutParams.WRAP_CONTENT

        // Placeholder until the preview arrives.
        frame.addView(TextView(this).apply {
            text = item.name
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0xFF2A2638.toInt())
            setTextColor(0x99FFFFFF.toInt())
        }, FrameLayout.LayoutParams(match, match))

        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(image, FrameLayout.LayoutParams(match, match))

        frame.addView(TextView(this).apply {
            text = "${item.number}  ${item.name}"
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA000000.toInt())
            setPadding(dp(6), dp(3), dp(6), dp(3))
        }, FrameLayout.LayoutParams(match, wrap, Gravity.BOTTOM))

        if (isVideo(item.name)) {
            frame.addView(TextView(this).apply {
                text = "▶"
                setTextColor(Color.WHITE)
                setBackgroundColor(0xAA000000.toInt())
                setPadding(dp(6), dp(2), dp(6), dp(2))
            }, FrameLayout.LayoutParams(wrap, wrap, Gravity.TOP or Gravity.END))
        }

        cells[item.number] = frame
        loadThumbnail(item, image, gen)
        return frame
    }

    /**
     * Memory cache, then disk cache, then ask the display: over Wi-Fi when we can (in parallel),
     * otherwise over Bluetooth (queued behind any button presses).
     */
    private fun loadThumbnail(item: Item, image: ImageView, gen: Int) {
        val key = "${item.name}|${item.size}"
        thumbCache[key]?.let { return image.setImageBitmap(it) }
        val diskFile = File(cacheDir, "thumbs/${key.hashCode().toUInt()}.jpg")
        val lanNow = lan
        (if (lanNow != null) lanPool else worker).execute {
            if (gen != gridGeneration) return@execute
            val bytes = when {
                diskFile.exists() -> diskFile.readBytes()
                lanNow != null -> lanNow.thumbnail(item.name, THUMB_WIDTH)
                    // Wi-Fi didn't work out; fall back to Bluetooth.
                    ?: return@execute worker.execute { fetchThumbOverBluetooth(item, key, diskFile, image, gen) }
                else -> return@execute fetchThumbOverBluetooth(item, key, diskFile, image, gen)
            }
            if (!diskFile.exists()) {
                diskFile.parentFile?.mkdirs()
                diskFile.writeBytes(bytes)
            }
            showThumb(bytes, key, image, gen)
        }
    }

    /** Worker thread only. */
    private fun fetchThumbOverBluetooth(item: Item, key: String, diskFile: File, image: ImageView, gen: Int) {
        if (gen != gridGeneration) return
        val conn = connection ?: return
        val bytes = try {
            conn.thumbnail(item.name, THUMB_WIDTH)
        } catch (e: IOException) {
            lostConnection(e)
            return
        } ?: return
        diskFile.parentFile?.mkdirs()
        diskFile.writeBytes(bytes)
        showThumb(bytes, key, image, gen)
    }

    private fun showThumb(bytes: ByteArray, key: String, image: ImageView, gen: Int) {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        ui.post {
            thumbCache[key] = bitmap
            if (gen == gridGeneration) image.setImageBitmap(bitmap)
        }
    }

    /** Outlines the background that's on the TV right now. */
    private fun applyHighlight() {
        for ((number, cell) in cells) {
            cell.setBackgroundColor(if (number == currentNumber) ACCENT else Color.TRANSPARENT)
        }
    }

    private fun isVideo(name: String) =
        name.substringAfterLast('.').lowercase() in setOf("mp4", "m4v", "mkv", "webm", "3gp", "mov")

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

    /**
     * The display has no Wi-Fi: offer this phone's network. If you've sent this network
     * before, its password is remembered and it's sent straight away.
     */
    private fun offerWifi() {
        if (!phoneWifi.isOnWifi()) return
        if (!phoneWifi.hasLocationPermission() && !phoneWifi.askedForLocation) {
            // Android hides the Wi-Fi name without location permission. Ask once; either way
            // onRequestPermissionsResult comes back here.
            phoneWifi.askedForLocation = true
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION,
            )
            return
        }
        val ssid = phoneWifi.currentSsid()
        val saved = ssid?.let { phoneWifi.savedPassword(it) }
        if (ssid != null && saved != null) {
            sendWifi(ssid, saved, announce = "Sent your Wi-Fi \"$ssid\" to the display")
        } else {
            askWifi(
                title = "The display isn't on Wi-Fi",
                message = "Send this phone's Wi-Fi to it? Then pictures and videos upload much faster. " +
                    "Android doesn't let apps read Wi-Fi passwords, so type it once - it's remembered on this phone.",
                prefillSsid = ssid,
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_LOCATION) offerWifi()
    }

    private fun askWifi(
        title: String = "Connect the display to Wi-Fi",
        message: String? = null,
        prefillSsid: String? = null,
    ) {
        val ssid = EditText(this).apply {
            hint = "Network name"
            isSingleLine = true
            setText(prefillSsid ?: "")
        }
        val password = EditText(this).apply {
            hint = "Password (blank for open network)"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(prefillSsid?.let { phoneWifi.savedPassword(it) } ?: "")
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ssid); addView(password)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .apply { if (message != null) setMessage(message) }
            .setView(padded(box))
            .setPositiveButton("Send to display") { _, _ ->
                val name = ssid.text.toString().trim()
                if (name.isNotEmpty()) sendWifi(name, password.text.toString())
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun sendWifi(ssid: String, password: String, announce: String? = null) {
        phoneWifi.savePassword(ssid, password)
        val cmd = "WIFI ${CastConnection.quote(ssid)} ${CastConnection.quote(password)}"
        worker.execute {
            val conn = connection ?: return@execute
            try {
                val reply = conn.command(cmd).last()
                ui.post { toast(if (reply.startsWith("OK")) announce ?: reply else reply) }
            } catch (e: IOException) { lostConnection(e) }
        }
        // Give the display time to join, then pick up its new address for Wi-Fi transfers.
        for (delay in listOf(8_000L, 20_000L)) {
            ui.postDelayed({
                worker.execute {
                    val conn = connection ?: return@execute
                    try { refreshStatusNow(conn); checkLanNow(conn) } catch (e: IOException) { lostConnection(e) }
                }
            }, delay)
        }
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
                    fun progressVia(via: String): (Long) -> Unit {
                        val started = System.currentTimeMillis()
                        return { sent ->
                            val secs = (System.currentTimeMillis() - started) / 1000.0
                            val rate = if (secs > 0) sent / 1024 / secs else 0.0
                            ui.post {
                                uploadProgress.progress = (sent * 1000 / size).toInt()
                                uploadText.text = "Uploading ${i + 1}/${uris.size} over $via: $name\n" +
                                    "${sent / 1024} of ${size / 1024} KB  (%.0f KB/s)".format(rate)
                            }
                        }
                    }
                    // Wi-Fi first when we're on the same network; Bluetooth if that fails.
                    val viaWifi = lan?.let { lanNow ->
                        try {
                            contentResolver.openInputStream(uri)!!.use { lanNow.upload(name, size, it, progressVia("Wi-Fi")) }
                        } catch (e: IOException) {
                            lan = null
                            ui.post { showTransport() }
                            null
                        }
                    }
                    results += viaWifi ?: contentResolver.openInputStream(uri)!!.use { stream ->
                        conn.upload(name, size, stream, progressVia("Bluetooth"))
                    }
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
        private const val REQ_LOCATION = 2
        private const val COLUMNS = 2
        private const val THUMB_WIDTH = 360
        private const val ACCENT = 0xFFFF5C8A.toInt()
        /** Matches the display's LIST lines: "   3  intro.mp4  (4.2 MB)". */
        internal val ITEM_LINE = Regex("""^\s*(\d+)\s{2}(.+)\s{2}\((.+)\)$""")
    }
}
