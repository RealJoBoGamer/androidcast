package com.androidcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class ProtocolTest {

    @Test
    fun tokenizeHandlesQuotesAndEscapes() {
        assertEquals(listOf("NEXT"), CommandProcessor.tokenize("NEXT"))
        assertEquals(listOf("GOTO", "3"), CommandProcessor.tokenize("  GOTO   3 "))
        assertEquals(listOf("WIFI", "My Home Net", "p@ss word"), CommandProcessor.tokenize("WIFI \"My Home Net\" \"p@ss word\""))
        assertEquals(listOf("WIFI", "a\"b", "c\\d"), CommandProcessor.tokenize("WIFI \"a\\\"b\" c\\\\d"))
        assertEquals(listOf("WIFI", "open", ""), CommandProcessor.tokenize("WIFI open \"\""))
    }

    @Test
    fun readLineLeavesBinaryPayloadUntouched() {
        val payload = byteArrayOf(0, 10, 13, -1, 65)
        val input = ByteArrayInputStream("UPLOAD a.jpg 5\r\n".toByteArray() + payload)
        assertEquals("UPLOAD a.jpg 5", BluetoothControlServer.readLine(input))
        assertEquals(payload.toList(), input.readBytes().toList())
    }

    @Test
    fun readLineReturnsNullAtEndOfStream() {
        val input = ByteArrayInputStream("PING".toByteArray())
        assertEquals("PING", BluetoothControlServer.readLine(input))
        assertNull(BluetoothControlServer.readLine(input))
    }
}
