package com.androidcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Opens the player when the stick boots, so it works as a dedicated display. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as AndroidCastApp
        if (!app.prefs.autostart) return
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
