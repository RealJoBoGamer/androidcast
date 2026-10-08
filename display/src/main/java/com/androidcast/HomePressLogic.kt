package com.androidcast

/**
 * Decides what a "Home was pressed" signal means. Two detectors report presses:
 * Home pressed while AndroidCast is in front, and Amazon's home screen appearing.
 * One press can be seen by both, so echoes are ignored. Pressing Home a second time
 * (before or just after the target has appeared) lets Amazon's home screen through.
 */
class HomePressLogic(private val clock: () -> Long) {

    enum class Decision { REDIRECT, ECHO, PASS_THROUGH }

    /** Package being switched to, while waiting for it to appear. */
    var pending: String? = null
        private set
    private var pendingSince = 0L
    /** When Amazon's home screen was first seen for the pending redirect. */
    private var homeSeenAt: Long? = null
    private var targetShownAt = NEVER
    private var passThroughUntil = NEVER
    private var cancelledAt = NEVER

    /** [fromHomeScreen]: the signal is Amazon's home screen appearing (vs. Home pressed in AndroidCast). */
    fun onHome(targetPackage: String, fromHomeScreen: Boolean): Decision {
        val now = clock()
        if (pending != null && now - pendingSince < PENDING_MAX_MS) {
            if (!fromHomeScreen) return Decision.ECHO
            val seen = homeSeenAt
            if (seen == null) {
                homeSeenAt = now  // the home screen showing up for the first press
                return Decision.ECHO
            }
            if (now - seen < SECOND_PRESS_GAP_MS) return Decision.ECHO
            // Home pressed again while the switch was still being held back: cancel it.
            pending = null
            cancelledAt = now
            passThroughUntil = now + DOUBLE_PRESS_MS
            return Decision.PASS_THROUGH
        }
        if (now < passThroughUntil) return Decision.PASS_THROUGH
        if (now - targetShownAt < DOUBLE_PRESS_MS) {
            targetShownAt = NEVER
            passThroughUntil = now + DOUBLE_PRESS_MS
            return Decision.PASS_THROUGH
        }
        pending = targetPackage
        pendingSince = now
        homeSeenAt = if (fromHomeScreen) now else null
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

    /**
     * True (once) if a redirect was cancelled recently. Android may still deliver the held-back
     * launch afterwards, so the target should step aside when this returns true.
     */
    fun consumeCancelled(): Boolean {
        val now = clock()
        val recent = now - cancelledAt < PENDING_MAX_MS
        cancelledAt = NEVER
        // Stepping aside brings Amazon's home screen back; that mustn't start a new switch.
        if (recent) passThroughUntil = maxOf(passThroughUntil, now + DOUBLE_PRESS_MS)
        return recent
    }

    companion object {
        const val DOUBLE_PRESS_MS = 3000L
        const val PENDING_MAX_MS = 7000L
        /** Home-screen events closer together than this are one press. */
        const val SECOND_PRESS_GAP_MS = 400L
        private const val NEVER = Long.MIN_VALUE / 2
    }
}
