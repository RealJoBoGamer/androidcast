package com.androidcast

import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Dismisses other apps' notifications while the player is on screen, so Fire OS
 * pop-ups don't appear over the background. Fire TV has no settings screen to
 * switch this on; grant it once with adb (see README):
 *
 *   adb shell settings put secure enabled_notification_listeners com.androidcast/com.androidcast.NotificationBlocker
 */
class NotificationBlocker : NotificationListenerService() {

    override fun onListenerConnected() {
        try { activeNotifications?.forEach(::maybeHide) } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = maybeHide(sbn)

    private fun maybeHide(sbn: StatusBarNotification) {
        val app = application as AndroidCastApp
        if (!app.prefs.quiet || !app.playerInFront) return
        if (sbn.packageName == packageName || !sbn.isClearable) return
        try { cancelNotification(sbn.key) } catch (_: Exception) {}
    }

    companion object {
        fun isEnabled(context: Context): Boolean =
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.contains(context.packageName) == true
    }
}
