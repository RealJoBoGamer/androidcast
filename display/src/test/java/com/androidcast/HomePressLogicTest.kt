package com.androidcast

import com.androidcast.HomePressLogic.Decision.ECHO
import com.androidcast.HomePressLogic.Decision.PASS_THROUGH
import com.androidcast.HomePressLogic.Decision.REDIRECT
import org.junit.Assert.assertEquals
import org.junit.Test

class HomePressLogicTest {
    private var now = 100_000L
    private val logic = HomePressLogic { now }
    private val target = "com.androidcast"

    @Test
    fun singlePressSeenByBothDetectorsRedirectsOnce() {
        assertEquals(REDIRECT, logic.onHome(target))  // Home pressed in AndroidCast
        now += 200
        assertEquals(ECHO, logic.onHome(target))      // Amazon home appeared (same press)
        now += 5000
        logic.targetShown()                           // AndroidCast back after Android's 5 s hold
        now += 10_000
        assertEquals(REDIRECT, logic.onHome(target))  // a later, separate press redirects again
    }

    @Test
    fun quickSecondPressLetsAmazonHomeThroughForBothDetectors() {
        assertEquals(REDIRECT, logic.onHome(target))
        now += 5000
        logic.targetShown()
        now += 1000
        assertEquals(PASS_THROUGH, logic.onHome(target))  // second press, first detector
        now += 200
        assertEquals(PASS_THROUGH, logic.onHome(target))  // same press, other detector
        now += 4000
        assertEquals(REDIRECT, logic.onHome(target))      // much later: back to normal
    }

    @Test
    fun otherLauncherAppearingEndsThePendingRedirect() {
        assertEquals(REDIRECT, logic.onHome("org.example.launcher"))
        assertEquals(false, logic.onWindow("com.amazon.tv.launcher"))
        now += 4000
        assertEquals(true, logic.onWindow("org.example.launcher"))
        now += 1000
        assertEquals(PASS_THROUGH, logic.onHome("org.example.launcher"))
    }

    @Test
    fun staleRedirectExpiresIfTargetNeverAppears() {
        assertEquals(REDIRECT, logic.onHome(target))
        now += HomePressLogic.PENDING_MAX_MS + 1
        assertEquals(REDIRECT, logic.onHome(target))
    }
}
