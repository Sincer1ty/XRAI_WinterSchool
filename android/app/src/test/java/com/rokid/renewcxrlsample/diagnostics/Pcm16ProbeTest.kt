package com.rokid.renewcxrlsample.diagnostics

import org.junit.Assert.*
import org.junit.Test

class Pcm16ProbeTest {
    @Test fun invalidRangesDoNotCountAsReceivedAudio() {
        val probe = Pcm16Probe()
        assertNull(probe.accept(null, 0, 2, 100))
        assertNull(probe.accept(byteArrayOf(1, 2), -1, 2, 100))
        assertNull(probe.accept(byteArrayOf(1, 2), 1, Int.MAX_VALUE, 100))
        assertEquals(0L, probe.snapshot().bytes)
        assertEquals(3L, probe.snapshot().invalidChunks)
        assertNull(probe.snapshot().lastChunkAtMs)
    }

    @Test fun onlyTheSdkRangeIsForwardedAndMeasured() {
        val probe = Pcm16Probe()
        val copied = probe.accept(byteArrayOf(99, 0, 64, 99), 1, 2, 100)
        assertArrayEquals(byteArrayOf(0, 64), copied)
        assertEquals(2L, probe.snapshot().bytes)
        assertEquals(1L, probe.snapshot().chunks)
        assertEquals(0.5, probe.snapshot().rms, 0.00001)
    }

    @Test fun splitSamplesAcrossCallbacksRemainLittleEndian() {
        val probe = Pcm16Probe()
        probe.accept(byteArrayOf(0), 0, 1, 100)
        probe.accept(byteArrayOf(64), 0, 1, 120)
        assertEquals(0.5, probe.snapshot().rms, 0.00001)
        assertEquals(2L, probe.snapshot().bytes)
    }

    @Test fun silentAudioStillCountsAsInputButMissingAudioDoesNot() {
        val probe = Pcm16Probe()
        probe.accept(byteArrayOf(0, 0, 0, 0), 0, 4, 100)
        assertEquals(0.0, probe.snapshot().rms, 0.0)
        assertEquals(100L, requireNotNull(probe.snapshot().lastChunkAtMs))
        assertEquals(4L, probe.snapshot().bytes)
    }

    @Test fun peakRmsKeepsLoudestChunkUntilReset() {
        val probe = Pcm16Probe()
        probe.accept(byteArrayOf(0, 64), 0, 2, 100)
        probe.accept(byteArrayOf(0, 0), 0, 2, 120)
        assertEquals(0.0, probe.snapshot().rms, 0.0)
        assertEquals(0.5, probe.snapshot().peakRms, 0.00001)
        probe.reset()
        assertEquals(0.0, probe.snapshot().peakRms, 0.0)
    }

    @Test fun hideBaselineCountsOnlySubsequentAudioAndResetClearsIt() {
        val probe = Pcm16Probe()
        probe.accept(byteArrayOf(0, 1), 0, 2, 100)
        probe.markHidden()
        assertEquals(0L, requireNotNull(probe.snapshot().bytesAfterHide))
        probe.accept(byteArrayOf(0, 2, 0, 3), 0, 4, 120)
        assertEquals(4L, requireNotNull(probe.snapshot().bytesAfterHide))
        probe.reset()
        assertEquals(0L, probe.snapshot().bytes)
        assertNull(probe.snapshot().bytesAfterHide)
        assertNull(probe.snapshot().lastChunkAtMs)
    }
}