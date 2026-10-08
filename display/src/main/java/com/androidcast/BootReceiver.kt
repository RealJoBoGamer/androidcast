package com.androidcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Opens the player when the stick boots, so it works as a dedicated display. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(AndroidCastApp.TAG, "received ${intent.action}")
        (context.applicationContext as AndroidCastApp).showPlayerAfterBoot()
    }
}
