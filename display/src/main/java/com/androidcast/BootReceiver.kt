package com.androidcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Opens the player when the stick boots, so it works as a dedicated display. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as AndroidCastApp
        app.showPlayer()
        // Fire OS can finish loading its home screen after us and cover the player, so try again.
        app.showPlayer(delayMs = 15_000)
    }
}
