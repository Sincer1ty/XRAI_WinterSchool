package com.rokid.renewcxrlsample.diagnostics

import kotlin.math.sqrt

data class PcmProbeSnapshot(
    val bytes: Long = 0,
    val chunks: Long = 0,
    val invalidChunks: Long = 0,
    val lastChunkAtMs: Long? = null,
    val rms: Double = 0.0,
    val bytesAfterHide: Long? = null,
    /** Loudest chunk since reset; a single-chunk RMS cannot show whether speech ever arrived. */
    val peakRms: Double = 0.0,
)

/** Meters the sample's assumed signed 16-bit little-endian PCM format. */
class Pcm16Probe {
    private var bytes = 0L
    private var chunks = 0L
    private var invalid = 0L
    private var lastAt: Long? = null
    private var rms = 0.0
    private var peakRms = 0.0
    private var lowByte: Int? = null
    private var hiddenBaseline: Long? = null

    @Synchronized
    fun accept(data: ByteArray?, offset: Int, length: Int, nowMs: Long): ByteArray? {
        if (data == null || offset < 0 || length <= 0 ||
            offset > data.size || length > data.size - offset) {
            invalid++
            return null
        }
        val pcm = data.copyOfRange(offset, offset + length)
        bytes += pcm.size
        chunks++
        lastAt = nowMs
        var squareSum = 0.0
        var samples = 0
        for (byte in pcm) {
            val low = lowByte
            if (low == null) {
                lowByte = byte.toInt() and 0xff
            } else {
                val sample = (low or (byte.toInt() shl 8)).toShort().toDouble() / 32768.0
                squareSum += sample * sample
                samples++
                lowByte = null
            }
        }
        if (samples > 0) {
            rms = sqrt(squareSum / samples)
            if (rms > peakRms) peakRms = rms
        }
        return pcm
    }

    @Synchronized
    fun markHidden() { hiddenBaseline = bytes }

    @Synchronized
    fun reset() {
        bytes = 0
        chunks = 0
        invalid = 0
        lastAt = null
        rms = 0.0
        peakRms = 0.0
        lowByte = null
        hiddenBaseline = null
    }

    @Synchronized
    fun snapshot() = PcmProbeSnapshot(
        bytes, chunks, invalid, lastAt, rms, hiddenBaseline?.let { bytes - it }, peakRms
    )
}