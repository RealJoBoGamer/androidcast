package com.androidcast

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Fire OS always sends the Home button (and start-up) to Amazon's home screen and ignores
 * the "default launcher" setting. This service notices Amazon's home screen appearing and
 * immediately opens the chosen launcher instead (AndroidCast itself, or another app).
 *
 * Press Home twice quickly to get to Amazon's home screen anyway.
 *
 * Fire TV has no settings screen to switch accessibility services on; use adb (see README):
 *   adb shell settings put secure enabled_accessibility_services com.androidcast/com.androidcast.HomeRedirectService
 *   adb shell settings put secure accessibility_enabled 1
 */
class HomeRedirectService : AccessibilityService() {

    private var lastRedirect = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName?.toString() !in AMAZON_HOME) return
        val app = application as AndroidCastApp
        val target = app.prefs.homeTarget
        if (target == HOME_OFF) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastRedirect < DOUBLE_PRESS_MS) {
            // Second Home press soon after a redirect: let Amazon's home screen through.
            Log.i(TAG, "double Home - showing Amazon home")
            return
        }
        lastRedirect = now
        val intent = launchIntent(this, target)
        if (intent == null) {
            Log.w(TAG, "home target '$target' isn't installed")
            return
        }
        Log.i(TAG, "Amazon home appeared - opening $target")
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "AndroidCastHome"
        private const val DOUBLE_PRESS_MS = 3000L
        const val HOME_SELF = "androidcast"
        const val HOME_OFF = "off"

        /** Amazon home screen packages across Fire OS versions. */
        private val AMAZON_HOME = setOf("com.amazon.tv.launcher", "com.amazon.firehomestarter")

        fun launchIntent(context: Context, target: String): Intent? =
            if (target == HOME_SELF) {
                Intent(context, MainActivity::class.java)
            } else {
                context.packageManager.getLeanbackLaunchIntentForPackage(target)
                    ?: context.packageManager.getLaunchIntentForPackage(target)
            }

        fun isEnabled(context: Context): Boolean =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.contains("${context.packageName}/") == true
    }
}
