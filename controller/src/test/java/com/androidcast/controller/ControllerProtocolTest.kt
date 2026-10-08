package com.androidcast.controller

import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerProtocolTest {

    @Test
    fun parsesDisplayListLines() {
        // Same format string the display uses in CommandProcessor.list().
        val line = "  %2d  %s  (%s)".format(3, "my intro clip.mp4", "4.2 MB")
        val (n, name, size) = RemoteActivity.ITEM_LINE.matchEntire(line)!!.destructured
        assertEquals("3", n)
        assertEquals("my intro clip.mp4", name)
        assertEquals("4.2 MB", size)
    }

    @Test
    fun quotesArgumentsForDisplayParser() {
        assertEquals("next", CastConnection.quote("next"))
        assertEquals("\"My Wifi\"", CastConnection.quote("My Wifi"))
        assertEquals("\"a\\\"b\\\\c\"", CastConnection.quote("a\"b\\c"))
        assertEquals("\"\"", CastConnection.quote(""))
    }
}
