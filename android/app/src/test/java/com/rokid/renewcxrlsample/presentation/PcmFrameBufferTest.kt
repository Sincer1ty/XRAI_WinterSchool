package com.rokid.renewcxrlsample.presentation

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmFrameBufferTest {
    @Test
    fun arbitrarySdkChunksPreserveCompleteFramesAndOddByteBoundaries() {
        val buffer = PcmFrameBuffer()
        val source = ByteArray(1_280) { (it % 127).toByte() }
        val frames = mutableListOf<ByteArray>()
        buffer.accept(source.copyOfRange(0, 17)) { frames += it.copyOf() }
        assertEquals(0, frames.size)
        buffer.accept(source.copyOfRange(17, 1_001)) { frames += it.copyOf() }
        buffer.accept(source.copyOfRange(1_001, 1_280)) { frames += it.copyOf() }
        assertEquals(2, frames.size)
        assertArrayEquals(source.copyOfRange(0, 640), frames[0])
        assertArrayEquals(source.copyOfRange(640, 1_280), frames[1])
    }

    @Test
    fun resetDiscardsIncompleteFrameFromPreviousSession() {
        val buffer = PcmFrameBuffer()
        val frames = mutableListOf<ByteArray>()
        buffer.accept(ByteArray(639) { 1 }) { frames += it.copyOf() }
        buffer.reset()
        buffer.accept(ByteArray(640) { 2 }) { frames += it.copyOf() }
        assertEquals(1, frames.size)
        assertArrayEquals(ByteArray(640) { 2 }, frames.single())
    }
}
