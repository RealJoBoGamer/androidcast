package com.androidcast

import android.app.Application

class AndroidCastApp : Application() {
    lateinit var library: MediaLibrary
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var wifi: WifiSetup
        private set
    lateinit var bluetooth: BluetoothControlServer
        private set

    /** The on-screen player, set while [MainActivity] exists. Only touch it on the main thread. */
    var player: PlayerControl? = null

    override fun onCreate() {
        super.onCreate()
        library = MediaLibrary(this)
        prefs = Prefs(this)
        wifi = WifiSetup(this)
        bluetooth = BluetoothControlServer(CommandProcessor(this))
        bluetooth.start()
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
