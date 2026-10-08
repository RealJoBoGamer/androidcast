package com.androidcast

import android.content.Context

/** Persistent settings, changed over Bluetooth with INTERVAL / FIT / AUDIO / AUTOSTART. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("androidcast", Context.MODE_PRIVATE)

    /** Seconds before auto-advancing to the next background; 0 = stay on the current one. */
    var intervalSeconds: Int
        get() = sp.getInt("interval", 0)
        set(v) = sp.edit().putInt("interval", v).apply()

    /** true = fill the screen (crop edges), false = fit whole picture (letterbox). */
    var cover: Boolean
        get() = sp.getBoolean("cover", true)
        set(v) = sp.edit().putBoolean("cover", v).apply()

    /** Play video soundtracks. Off by default so nothing leaks into the podcast audio. */
    var audio: Boolean
        get() = sp.getBoolean("audio", false)
        set(v) = sp.edit().putBoolean("audio", v).apply()

    /** Loop videos; when off they play once and hold the last frame. */
    var loop: Boolean
        get() = sp.getBoolean("loop", false)
        set(v) = sp.edit().putBoolean("loop", v).apply()

    /** Open the player automatically when the stick boots. */
    var autostart: Boolean
        get() = sp.getBoolean("autostart", true)
        set(v) = sp.edit().putBoolean("autostart", v).apply()

    /** Hide other apps' notifications (Fire OS pop-ups) while the player runs. */
    var quiet: Boolean
        get() = sp.getBoolean("quiet", true)
        set(v) = sp.edit().putBoolean("quiet", v).apply()

    /**
     * Secret that Wi-Fi uploads must present. Created once, and only shared over the
     * paired Bluetooth link, so other devices on the network can't change the display.
     */
    val lanToken: String
        get() = sp.getString("lan_token", null) ?: java.util.UUID.randomUUID().toString().replace("-", "")
            .also { sp.edit().putString("lan_token", it).apply() }

    /** Name of the background on screen, restored after a restart. */
    var currentName: String?
        get() = sp.getString("current", null)
        set(v) = sp.edit().putString("current", v).apply()
}
