package com.androidcast

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
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

    /** Packages that count as "the Amazon home screen": known ones plus whatever handles HOME. */
    private lateinit var homePackages: Set<String>
    private var lastLogged = ""

    override fun onServiceConnected() {
        val pm = packageManager
        val handlers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }
        homePackages = (AMAZON_HOME + handlers - packageName - "android").toSet()
        Log.i(TAG, "home button service connected; watching for $homePackages")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Log what comes to the front, so `adb logcat -s AndroidCastHome` shows what Home opened.
        val what = "$pkg/${event.className}"
        if (what != lastLogged) {
            lastLogged = what
            Log.d(TAG, "window: $what")
        }
        val home = (application as AndroidCastApp).home
        if (pkg in homePackages) home.onHome("Amazon home appeared") else home.onWindow(pkg)
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "AndroidCastHome"
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
