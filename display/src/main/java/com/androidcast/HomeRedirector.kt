package com.androidcast

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Sends the Home button to the chosen launcher instead of Amazon's home screen.
 *
 * Two things trigger it: [HomeRedirectService] seeing Amazon's home screen appear, and
 * [MainActivity] being left by the user (Home pressed while AndroidCast is showing).
 *
 * After Home is pressed, Android holds back other apps from opening for up to 5 seconds,
 * so a full-screen black cover goes up straight away and stays until the target opens.
 * Pressing Home again within a few seconds cancels it and leaves Amazon's home screen.
 */
class HomeRedirector(private val app: AndroidCastApp) {

    private val handler = Handler(Looper.getMainLooper())
    private val windows = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val logic = HomePressLogic { SystemClock.elapsedRealtime() }
    private var cover: View? = null
    private val removeCover = Runnable { hideCover() }

    /** Main thread. Returns true if it's redirecting. */
    fun onHome(source: String, fromHomeScreen: Boolean): Boolean {
        val target = app.prefs.homeTarget
        if (target == HomeRedirectService.HOME_OFF) return false

        val intent = HomeRedirectService.launchIntent(app, target)
        if (intent == null) {
            Log.w(TAG, "$source: home target '$target' isn't installed")
            return false
        }
        val targetPackage = if (target == HomeRedirectService.HOME_SELF) app.packageName else target
        when (logic.onHome(targetPackage, fromHomeScreen)) {
            HomePressLogic.Decision.ECHO -> return true
            HomePressLogic.Decision.PASS_THROUGH -> {
                Log.i(TAG, "$source: Home pressed twice - leaving Amazon home screen")
                hideCover()
                return false
            }
            HomePressLogic.Decision.REDIRECT -> {}
        }
        Log.i(TAG, "$source: opening $target")
        showCover()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        // Start now (Android may hold it back up to 5 s) and once more in case the first was dropped.
        app.startActivity(intent)
        handler.postDelayed({ if (logic.pending != null) app.startActivity(intent) }, 5500)
        return true
    }

    /** A window from [pkg] came to the front. */
    fun onWindow(pkg: String) {
        // Our own cover window also reports as our package; AndroidCast reports itself via targetShown().
        if (pkg == app.packageName) return
        if (logic.onWindow(pkg)) hideCover()
    }

    /** True if a switch to AndroidCast was cancelled by a second Home press and it should step aside. */
    fun consumeCancelled(): Boolean = logic.consumeCancelled()

    /** The redirect target is on screen: drop the cover and start the double-press window. */
    fun targetShown() {
        logic.targetShown()
        hideCover()
    }

    private fun hideCover() {
        handler.removeCallbacks(removeCover)
        cover?.let { try { windows.removeView(it) } catch (_: Exception) {} }
        cover = null
    }

    private fun showCover() {
        if (cover != null) return
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(app)) return
        val view = FrameLayout(app).apply {
            setBackgroundColor(Color.BLACK)
            addView(TextView(app).apply {
                text = "AndroidCast"
                setTextColor(0x55FFFFFF)
                textSize = 18f
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.OPAQUE,
        )
        try {
            windows.addView(view, params)
            cover = view
            handler.postDelayed(removeCover, COVER_MAX_MS)
        } catch (e: Exception) {
            Log.w(TAG, "can't show cover: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AndroidCastHome"
        private const val COVER_MAX_MS = 8000L

    }
}
