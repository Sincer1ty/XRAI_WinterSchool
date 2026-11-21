package com.rokid.renewcxrlsample.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RequestTimingTest {

    @Test
    fun splitsServerLlmAndNetworkTime() {
        val t = RequestTiming.of("/api/v1/scripts/analyze", 200, 1_000.0, "app;dur=12.5, llm;dur=850.0, total;dur=862.5")
        assertEquals(12.5, t.serverMs!!, 0.001)
        assertEquals(850.0, t.llmMs!!, 0.001)
        assertEquals("왕복 − 서버 전체", 137.5, t.networkMs!!, 0.001)
    }

    @Test
    fun requestWithoutLlmHasServerTimeOnly() {
        val t = RequestTiming.of("/api/v1/hints/pause", 200, 40.0, "app;dur=1.0, total;dur=1.0")
        assertNull(t.llmMs)
        assertEquals(39.0, t.networkMs!!, 0.001)
    }

    @Test
    fun missingOrBrokenHeaderKeepsRoundTripOnly() {
        for (header in listOf(null, "", "garbage", "app;desc=x")) {
            val t = RequestTiming.of("/p", 200, 40.0, header)
            assertNull(header, t.serverMs)
            assertNull(header, t.networkMs)
            assertEquals(40.0, t.roundTripMs, 0.001)
        }
    }

    @Test
    fun clockSkewNeverGivesNegativeNetworkTime() {
        assertEquals(0.0, RequestTiming.of("/p", 200, 5.0, "app;dur=9.0, total;dur=9.0").networkMs!!, 0.001)
    }

    @Test
    fun formatsForScreen() {
        assertEquals("851ms", RequestTiming.formatMs(850.6))
        assertEquals("-", RequestTiming.formatMs(null))
    }
}
