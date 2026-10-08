package com.androidcast

/**
 * Decides what a "Home was pressed" signal means. Two detectors report the same press
 * (Home pressed in AndroidCast, and Amazon's home screen appearing), so echoes must be
 * ignored, and a quick second press must let Amazon's home screen through.
 */
class HomePressLogic(private val clock: () -> Long) {

    enum class Decision { REDIRECT, ECHO, PASS_THROUGH }

    /** Package being switched to, while waiting for it to appear. */
    var pending: String? = null
        private set
    private var pendingSince = 0L
    private var targetShownAt = Long.MIN_VALUE / 2
    private var passThroughUntil = Long.MIN_VALUE / 2

    fun onHome(targetPackage: String): Decision {
        val now = clock()
        if (pending != null && now - pendingSince < PENDING_MAX_MS) return Decision.ECHO
        if (now < passThroughUntil) return Decision.PASS_THROUGH
        if (now - targetShownAt < DOUBLE_PRESS_MS) {
            targetShownAt = Long.MIN_VALUE / 2
            passThroughUntil = now + DOUBLE_PRESS_MS
            return Decision.PASS_THROUGH
        }
        pending = targetPackage
        pendingSince = now
        return Decision.REDIRECT
    }

    /** A window from [pkg] came to the front. Returns true if it's the target we were waiting for. */
    fun onWindow(pkg: String): Boolean {
        if (pkg != pending) return false
        targetShown()
        return true
    }

    fun targetShown() {
        if (pending != null) {
            pending = null
            targetShownAt = clock()
        }
    }

    companion object {
        const val DOUBLE_PRESS_MS = 3000L
        const val PENDING_MAX_MS = 7000L
    }
}
