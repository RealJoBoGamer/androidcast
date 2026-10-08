package com.androidcast

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Bundle
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/**
 * Fullscreen background player. Shows the files in [MediaLibrary] one at a
 * time: images stay up, videos play once and hold their last frame (or loop
 * with LOOP on), until switched by a Bluetooth
 * clicker/keyboard/remote key press, a Bluetooth serial command, or the
 * optional auto-advance timer.
 */
class MainActivity : Activity(), PlayerControl, SurfaceHolder.Callback {

    private lateinit var app: AndroidCastApp
    private lateinit var video: SurfaceView
    private lateinit var blackout: View
    private lateinit var images: Array<ImageView>
    private lateinit var hint: TextView
    private lateinit var overlay: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor()

    private var items: List<File> = emptyList()
    private var index = -1
    private var frontImage = 0
    /** Bumped on every switch so stale async image decodes / video callbacks are ignored. */
    private var generation = 0
    private var blank = false
    private var currentModified = 0L

    private var mediaPlayer: MediaPlayer? = null
    private var surface: Surface? = null
    private var pendingVideo: File? = null
    private var videoWidth = 0
    private var videoHeight = 0

    /** Notices files added/removed behind our back (e.g. `adb push`) and rescans. */
    private var folderWatcher: FileObserver? = null
    private val rescan = Runnable { reload() }

    private val advance = Runnable { next() }
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }
    private val refreshOverlay = object : Runnable {
        override fun run() {
            if (overlay.visibility == View.VISIBLE) {
                overlay.text = statusText()
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as AndroidCastApp
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        video = findViewById(R.id.video)
        images = arrayOf(findViewById(R.id.imageA), findViewById(R.id.imageB))
        hint = findViewById(R.id.hint)
        overlay = findViewById(R.id.overlay)
        video.holder.addCallback(this)
        blackout = findViewById(R.id.blackout)
        findViewById<View>(R.id.root).setOnTouchListener { v, e -> onTap(v, e) }

        app.player = this
        applyScaleType()
        items = app.library.items()
        val start = app.prefs.currentName?.let { name -> items.indexOfFirst { it.name == name } } ?: -1
        if (items.isEmpty()) showEmpty() else show(maxOf(start, 0))

        @Suppress("DEPRECATION")
        folderWatcher = object : FileObserver(
            app.library.dir.absolutePath,
            CLOSE_WRITE or MOVED_TO or MOVED_FROM or DELETE
        ) {
            override fun onEvent(event: Int, path: String?) {
                // Debounce: a multi-file push fires many events.
                handler.removeCallbacks(rescan)
                handler.postDelayed(rescan, 1000)
            }
        }.also { it.startWatching() }

        handleAdbExtras(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAdbExtras(intent)
    }

    /**
     * Pairing helpers for when the remote can't easily reach the stick, triggered from a computer:
     *   adb shell am start -n com.androidcast/.MainActivity --ez discoverable true
     *   adb shell am start -n com.androidcast/.MainActivity --es pair AA:BB:CC:DD:EE:FF
     */
    private fun handleAdbExtras(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_PAIR)?.let { pairWith(it.trim().uppercase()) }
        if (intent.getBooleanExtra(EXTRA_DISCOVERABLE, false)) requestDiscoverable()
        intent.removeExtra(EXTRA_PAIR)
        intent.removeExtra(EXTRA_DISCOVERABLE)
    }

    /** The stick starts pairing with the phone itself, so the stick never needs to be discoverable. */
    private fun pairWith(address: String) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val message = when {
            adapter == null -> "This device has no Bluetooth."
            !BluetoothAdapter.checkBluetoothAddress(address) ->
                "'$address' isn't a Bluetooth address (expected AA:BB:CC:DD:EE:FF)."
            else -> try {
                adapter.cancelDiscovery()
                val device = adapter.getRemoteDevice(address)
                when {
                    device.bondState == BluetoothDevice.BOND_BONDED -> "Already paired with ${device.name ?: address}."
                    device.createBond() -> "Pairing with $address…\nConfirm the code on the phone and on this screen."
                    else -> "Couldn't start pairing with $address. Is the phone's Bluetooth on?"
                }
            } catch (e: SecurityException) {
                "Not allowed to pair: ${e.message}"
            }
        }
        flashMessage(message)
    }

    override fun onResume() {
        super.onResume()
        app.playerInFront = true
        hideSystemUi()
        app.player = this
        // Pick up files that were adb-pushed while we were in the background.
        reload()
    }

    override fun onPause() {
        app.playerInFront = false
        super.onPause()
    }

    override fun onDestroy() {
        if (app.player === this) app.player = null
        folderWatcher?.stopWatching()
        handler.removeCallbacksAndMessages(null)
        releaseVideo()
        decoder.shutdownNow()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    // ---- Remote / Bluetooth clicker / keyboard ---------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount > 0) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_N -> next()

            // While the info panel is open, DOWN opens Fire TV settings (handy when Home
            // is redirected away from the Amazon home screen).
            KeyEvent.KEYCODE_DPAD_DOWN ->
                if (overlay.visibility == View.VISIBLE) openSystemSettings() else next()

            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_P -> previous()

            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> showIndex(keyCode - KeyEvent.KEYCODE_1)
            in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_9 -> showIndex(keyCode - KeyEvent.KEYCODE_NUMPAD_1)

            KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> setBlank(!blank)

            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_I -> toggleOverlay()

            // While the info panel is open, UP makes the stick discoverable so a phone can pair.
            KeyEvent.KEYCODE_DPAD_UP ->
                if (overlay.visibility == View.VISIBLE) requestDiscoverable() else previous()

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> toggleOverlay()

            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    /** Touch controls for phones/tablets: tap left third = previous, right third = next, middle = info. */
    private fun onTap(view: View, e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        when {
            e.x < view.width / 3f -> previous()
            e.x > view.width * 2 / 3f -> next()
            else -> toggleOverlay()
        }
        return true
    }

    private fun toggleOverlay() {
        handler.removeCallbacks(hideOverlay)
        handler.removeCallbacks(refreshOverlay)
        if (overlay.visibility == View.VISIBLE) {
            overlay.visibility = View.GONE
        } else {
            overlay.visibility = View.VISIBLE
            handler.post(refreshOverlay)
            handler.postDelayed(hideOverlay, 15_000)
        }
    }

    private fun openSystemSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            flashMessage("Couldn't open Settings. Press Home twice for the Amazon home screen.")
        }
    }

    private fun requestDiscoverable() {
        try {
            startActivity(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            )
        } catch (e: ActivityNotFoundException) {
            overlay.text = "${statusText()}\n\nThis device can't be made discoverable from the app.\n" +
                "Pair from the stick instead: Settings > Controllers & Bluetooth Devices."
        }
    }

    private fun statusText(): String {
        val p = app.prefs
        return """
            AndroidCast ${BuildConfig.VERSION_NAME}
            Showing:    ${nowShowing() ?: "-"}${if (blank) "  [BLANK]" else ""}
            Bluetooth:  ${app.bluetooth.deviceName} - ${app.bluetooth.status}
            Wi-Fi:      ${app.wifi.status()}
            Interval:   ${if (p.intervalSeconds == 0) "off" else "${p.intervalSeconds}s"}   Fit: ${if (p.cover) "cover" else "contain"}   Audio: ${if (p.audio) "on" else "off"}
            Folder:     ${app.library.dir.absolutePath}

            LEFT/RIGHT (or tap screen edges) switch   1-9 jump   PLAY/PAUSE blank   UP discoverable   DOWN Fire TV settings   MENU close
            Home twice quickly: Amazon home screen
        """.trimIndent()
    }

    // ---- PlayerControl -------------------------------------------------------------------

    override fun next() { if (items.isNotEmpty()) show(Math.floorMod(index + 1, items.size)) }

    override fun previous() { if (items.isNotEmpty()) show(Math.floorMod(index - 1, items.size)) }

    override fun showIndex(index: Int): Boolean {
        if (items.isEmpty()) showEmpty()
        return index in items.indices && show(index)
    }

    private fun show(index: Int): Boolean {
        this.index = index
        val file = items[this.index]
        currentModified = file.lastModified()
        app.prefs.currentName = file.name
        hint.visibility = View.GONE
        if (blank) setBlank(false)
        generation++
        when (MediaLibrary.kindOf(file)) {
            MediaLibrary.Kind.IMAGE -> showImage(file, generation)
            MediaLibrary.Kind.VIDEO -> showVideo(file)
            null -> {}
        }
        scheduleAdvance()
        return true
    }

    override fun showName(name: String): Boolean {
        val i = items.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        return i >= 0 && showIndex(i)
    }

    override fun reload() {
        val current = items.getOrNull(index)
        val currentStamp = current?.let { currentModified }
        val fresh = app.library.items()
        if (fresh.map { it.name to it.lastModified() } == items.map { it.name to it.lastModified() }) return
        items = fresh
        val i = items.indexOfFirst { it.name == current?.name }
        if (i >= 0 && items[i].lastModified() == currentStamp) {
            // Current background is unchanged; just track its new position.
            index = i
        } else if (items.isEmpty()) {
            showEmpty()
        } else {
            // Current file was deleted or replaced by an upload with the same name.
            show(maxOf(i, 0))
        }
    }

    override fun applySettings() {
        applyScaleType()
        applyVideoTransform()
        mediaPlayer?.let {
            setVolume(it)
            it.isLooping = app.prefs.loop
        }
        scheduleAdvance()
    }

    override fun setBlank(blank: Boolean) {
        this.blank = blank
        blackout.animate().alpha(if (blank) 1f else 0f).setDuration(FADE_MS).start()
        if (blank) handler.removeCallbacks(advance) else scheduleAdvance()
    }

    override fun nowShowing(): String? =
        items.getOrNull(index)?.let { "${index + 1}/${items.size} ${it.name}" }

    // ---- Images --------------------------------------------------------------------------

    private fun showImage(file: File, gen: Int) {
        val screen = video.parent as View
        val w = maxOf(screen.width, resources.displayMetrics.widthPixels)
        val h = maxOf(screen.height, resources.displayMetrics.heightPixels)
        decoder.execute {
            val bitmap = decodeSampled(file, w, h)
            handler.post {
                if (gen != generation || isFinishing) return@post
                if (bitmap == null) {
                    Log.w(TAG, "could not decode ${file.name}")
                    flashMessage("Can't open ${file.name}")
                    return@post
                }
                // Once the image fully covers the video layer, stop any video underneath.
                crossfadeTo(bitmap) { if (gen == generation) releaseVideo() }
            }
        }
    }

    private fun crossfadeTo(bitmap: Bitmap, onDone: () -> Unit) {
        val old = images[frontImage]
        frontImage = 1 - frontImage
        val fresh = images[frontImage]
        fresh.setImageBitmap(bitmap)
        fresh.alpha = 0f
        fresh.bringToFront()
        blackout.bringToFront()
        overlay.bringToFront()
        hint.bringToFront()
        fresh.animate().alpha(1f).setDuration(FADE_MS).withEndAction {
            old.alpha = 0f
            old.setImageDrawable(null)
            onDone()
        }.start()
    }

    private fun fadeOutImages() {
        for (iv in images) {
            iv.animate().alpha(0f).setDuration(FADE_MS).withEndAction { iv.setImageDrawable(null) }.start()
        }
    }

    private fun applyScaleType() {
        val type = if (app.prefs.cover) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        images.forEach { it.scaleType = type }
    }

    // ---- Video ---------------------------------------------------------------------------

    private fun showVideo(file: File) {
        pendingVideo = file
        if (surface != null) startVideo(file)
        // otherwise surfaceCreated starts it
    }

    private fun startVideo(file: File) {
        pendingVideo = null
        releaseVideo()
        val gen = generation
        val mp = MediaPlayer()
        mediaPlayer = mp
        try {
            mp.setSurface(surface)
            mp.setDataSource(file.absolutePath)
            // Play once and stay on the last frame, unless LOOP is on.
            mp.isLooping = app.prefs.loop
            setVolume(mp)
            mp.setOnVideoSizeChangedListener { _, w, h ->
                videoWidth = w; videoHeight = h
                applyVideoTransform()
            }
            mp.setOnInfoListener { _, what, _ ->
                // First frame is on screen: fade the previous image away to reveal the video.
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && gen == generation) fadeOutImages()
                false
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "video error $what/$extra for ${file.name} (${videoWidth}x$videoHeight)")
                if (gen == generation) {
                    val format = VideoProbe.describe(file)
                    Log.w(TAG, "can't play ${file.name}: ${format ?: "unknown format"}")
                    flashMessage(
                        "Can't play ${file.name}" + (format?.let { "\n($it)" } ?: "") +
                            "\n\nThis stick plays H.264 video up to 1080p, about 30 fps.\n" +
                            "Upload it from the AndroidCast Remote app to convert it automatically.",
                        durationMs = 10_000,
                    )
                }
                true
            }
            mp.setOnPreparedListener { if (gen == generation) it.start() }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "could not start ${file.name}", e)
            flashMessage("Can't play ${file.name}")
            releaseVideo()
        }
    }

    private fun setVolume(mp: MediaPlayer) {
        val v = if (app.prefs.audio) 1f else 0f
        mp.setVolume(v, v)
    }

    private fun releaseVideo() {
        mediaPlayer?.let {
            it.setOnInfoListener(null)
            it.setOnErrorListener(null)
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        mediaPlayer = null
    }

    /**
     * Sizes the video view to the video's shape: fill (cover) makes it at least as big as the
     * screen, so the edges are cropped; fit (contain) keeps it inside the screen.
     */
    private fun applyVideoTransform() {
        val parent = video.parent as View
        val pw = parent.width
        val ph = parent.height
        if (videoWidth == 0 || videoHeight == 0 || pw == 0 || ph == 0) return
        val videoAspect = videoWidth.toFloat() / videoHeight
        val screenAspect = pw.toFloat() / ph
        val fillWidth = if (app.prefs.cover) videoAspect < screenAspect else videoAspect > screenAspect
        val (w, h) = if (fillWidth) pw to Math.round(pw / videoAspect) else Math.round(ph * videoAspect) to ph
        val lp = video.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            video.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        pendingVideo?.let { startVideo(it) }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Remember what was playing so it restarts when the surface comes back.
        if (mediaPlayer != null) pendingVideo = items.getOrNull(index)
        releaseVideo()
        surface = null
    }

    // ---- Misc ----------------------------------------------------------------------------

    private fun scheduleAdvance() {
        handler.removeCallbacks(advance)
        val s = app.prefs.intervalSeconds
        if (s > 0 && items.size > 1 && !blank) handler.postDelayed(advance, s * 1000L)
    }

    private fun showEmpty() {
        index = -1
        generation++
        releaseVideo()
        fadeOutImages()
        handler.removeCallbacks(advance)
        hint.text = """
            No backgrounds yet.

            Connect over Bluetooth serial to "${app.bluetooth.deviceName}" and send HELP,
            or copy images / MP4 videos into:
            ${app.library.dir.absolutePath}

            Press MENU for status, then UP to make this device discoverable.
        """.trimIndent()
        hint.visibility = View.VISIBLE
        hint.bringToFront()
    }

    private fun flashMessage(text: String, durationMs: Long = 4000) {
        hint.text = text
        hint.visibility = View.VISIBLE
        hint.bringToFront()
        handler.postDelayed({ if (items.isNotEmpty()) hint.visibility = View.GONE }, durationMs)
    }

    companion object {
        private const val TAG = "AndroidCast"
        private const val FADE_MS = 600L
        private const val EXTRA_PAIR = "pair"
        private const val EXTRA_DISCOVERABLE = "discoverable"

        /** Decodes an image no bigger than needed for the screen (old sticks have little RAM). */
        fun decodeSampled(file: File, reqW: Int, reqH: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) sample *= 2
            return try {
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (e: OutOfMemoryError) {
                null
            }
        }
    }
}
