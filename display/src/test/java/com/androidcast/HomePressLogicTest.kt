package com.androidcast

import com.androidcast.HomePressLogic.Decision.ECHO
import com.androidcast.HomePressLogic.Decision.PASS_THROUGH
import com.androidcast.HomePressLogic.Decision.REDIRECT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePressLogicTest {
    private var now = 100_000L
    private val logic = HomePressLogic { now }
    private val target = "com.androidcast"

    @Test
    fun singlePressSeenByBothDetectorsRedirectsOnce() {
        assertEquals(REDIRECT, logic.onHome(target, fromHomeScreen = false))  // Home pressed in AndroidCast
        now += 200
        assertEquals(ECHO, logic.onHome(target, fromHomeScreen = true))       // Amazon home appeared
        now += 100
        assertEquals(ECHO, logic.onHome(target, fromHomeScreen = true))       // launcher's duplicate event
        now += 4700
        logic.targetShown()                                                    // back after the 5 s hold
        assertFalse(logic.consumeCancelled())
        now += 10_000
        assertEquals(REDIRECT, logic.onHome(target, fromHomeScreen = false))   // later press redirects again
    }

    @Test
    fun secondPressWhileHeldBackCancelsAndTargetStepsAside() {
        assertEquals(REDIRECT, logic.onHome(target, fromHomeScreen = false))
        now += 200
        assertEquals(ECHO, logic.onHome(target, fromHomeScreen = true))
        now += 600                                                             // user presses Home again
        assertEquals(PASS_THROUGH, logic.onHome(target, fromHomeScreen = true))
        now += 4000                                                            // held-back launch arrives
        assertTrue(logic.consumeCancelled())                                   // -> AndroidCast steps aside
        assertFalse(logic.consumeCancelled())                                  // only once
        now += 300
        assertEquals(PASS_THROUGH, logic.onHome(target, fromHomeScreen = true))  // Amazon home back: no loop
        now += 10_000
        assertEquals(REDIRECT, logic.onHome(target, fromHomeScreen = false))      // normal again later
    }

    @Test
    fun secondPressAfterTargetAppearedLetsAmazonHomeThroughForBothDetectors() {
        assertEquals(REDIRECT, logic.onHome(target, fromHomeScreen = false))
        now += 5000
        logic.targetShown()
        now += 1000
        assertEquals(PASS_THROUGH, logic.onHome(target, fromHomeScreen = false))
        now += 200
        assertEquals(PASS_THROUGH, logic.onHome(target, fromHomeScreen = true))
        now += 4000
        assertEquals(REDIRECT, logic.onHome(target, fromHomeScreen = true))
    }

    @Test
    fun otherLauncherAppearingEndsThePendingRedirect() {
        assertEquals(REDIRECT, logic.onHome("org.example.launcher", fromHomeScreen = true))
        assertFalse(logic.onWindow("com.amazon.tv.launcher"))
        now += 4000
        assertTrue(logic.onWindow("org.example.launcher"))
        now += 1000
        assertEquals(PASS_THROUGH, logic.onHome("org.example.launcher", fromHomeScreen = true))
    }

    @Test
    fun staleRedirectExpiresIfTargetNeverAppears() {
        assertEquals(REDIRECT, logic.onHome(target, fromHomeScreen = false))
        now += HomePressLogic.PENDING_MAX_MS + 1
        assertEquals(REDIRECT, logic.onHome(target, fromHomeScreen = false))
    }
}
