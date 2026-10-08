package com.androidcast

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log

class AndroidCastApp : Application() {
    lateinit var library: MediaLibrary
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var wifi: WifiSetup
        private set
    lateinit var bluetooth: BluetoothControlServer
        private set
    lateinit var lan: LanServer
        private set

    /** The on-screen player, set while [MainActivity] exists. Only touch it on the main thread. */
    var player: PlayerControl? = null

    override fun onCreate() {
        super.onCreate()
        library = MediaLibrary(this)
        prefs = Prefs(this)
        wifi = WifiSetup(this)
        val processor = CommandProcessor(this)
        bluetooth = BluetoothControlServer(processor)
        bluetooth.start()
        lan = LanServer(this, processor)
        lan.start()
        // Dedicated display: keep Wi-Fi on and let it join any saved network in range.
        wifi.enableAllSaved()

        // The stick usually sleeps (rather than reboots) when the TV turns off, and Fire OS
        // may show its home screen on wake, so come back to the front when the screen turns on.
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = showPlayer(delayMs = 2000, reason = "screen on")
        }, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Brings the player to the front if AUTOSTART is on. On Fire OS 8 (Android 9+ rules)
     * this only works from the background once "display over other apps" is allowed:
     *   adb shell appops set com.androidcast SYSTEM_ALERT_WINDOW allow
     */
    fun showPlayer(delayMs: Long = 0, reason: String = "") {
        handler.postDelayed({
            when {
                !prefs.autostart -> Log.i(TAG, "autostart off, not opening ($reason)")
                playerInFront -> Log.i(TAG, "player already in front ($reason)")
                else -> {
                    Log.i(TAG, "opening player ($reason)")
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                }
            }
        }, delayMs)
    }

    /**
     * After boot, slow sticks (Fire OS 5) can take a minute or more to finish starting their home
     * screen, which then covers the player. Keep re-checking for a while; each attempt does nothing
     * if the player is already on screen.
     */
    fun showPlayerAfterBoot() {
        for (seconds in BOOT_RETRY_SECONDS) showPlayer(seconds * 1000L, "boot +${seconds}s")
    }

    /** True while [MainActivity] is resumed (visible and in front). */
    @Volatile var playerInFront = false

    companion object {
        const val TAG = "AndroidCast"
        private val BOOT_RETRY_SECONDS = listOf(0, 10, 20, 40, 60, 90, 120)
    }
}

/** What Bluetooth commands can ask the screen to do. All calls happen on the main thread. */
interface PlayerControl {
    fun next()
    fun previous()
    /** 0-based index into the library. */
    fun showIndex(index: Int): Boolean
    fun showName(name: String): Boolean
    /** Rescan the folder after files were added or removed. */
    fun reload()
    /** Re-apply fit / audio / interval settings. */
    fun applySettings()
    fun setBlank(blank: Boolean)
    /** e.g. "3/7 intro.mp4", or null when nothing is showing. */
    fun nowShowing(): String?
}
