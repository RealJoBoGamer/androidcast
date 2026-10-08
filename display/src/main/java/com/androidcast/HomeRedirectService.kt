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

    /** Packages that may show the Amazon home screen: known ones plus whatever handles HOME. */
    private lateinit var homePackages: Set<String>
    /** The HOME activities themselves, e.g. com.amazon.tv.launcher.ui.HomeActivity. */
    private lateinit var homeActivities: Set<String>
    private var lastLogged = ""

    override fun onServiceConnected() {
        val handlers = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo }
            .filter { it.packageName != packageName && it.packageName != "android" }
        homePackages = AMAZON_HOME + handlers.map { it.packageName }
        homeActivities = handlers.map { it.name }.toSet()
        Log.i(TAG, "home button service connected; home screen = $homeActivities in $homePackages")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: ""
        // Log what comes to the front, so `adb logcat -s AndroidCastHome` shows what Home opened.
        val what = "$pkg/$cls"
        if (what != lastLogged) {
            lastLogged = what
            Log.d(TAG, "window: $what")
        }
        val home = (application as AndroidCastApp).home
        if (isHomeScreen(pkg, cls)) home.onHome("Amazon home appeared", fromHomeScreen = true) else home.onWindow(pkg)
    }

    /**
     * On Fire OS the same app shows the home screen AND Settings, Your Apps, search..., so
     * only its home screen counts - otherwise opening Settings would bounce back too.
     */
    private fun isHomeScreen(pkg: String, cls: String): Boolean {
        if (pkg !in homePackages) return false
        if (cls.contains("setting", ignoreCase = true)) return false
        return cls in homeActivities || cls.substringAfterLast('.').contains("home", ignoreCase = true)
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
