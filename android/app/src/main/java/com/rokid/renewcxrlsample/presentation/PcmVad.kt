package com.rokid.renewcxrlsample.presentation

import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate

/** SDK 청크 경계와 관계없이 16 kHz·16비트·모노 PCM을 20 ms 프레임으로 전달한다. */
internal class PcmFrameBuffer(private val frameBytes: Int = 640) {
    private val frame = ByteArray(frameBytes)
    private var size = 0

    fun reset() { size = 0 }

    fun accept(pcm: ByteArray, onFrame: (ByteArray) -> Unit) {
        var offset = 0
        while (offset < pcm.size) {
            val count = minOf(frameBytes - size, pcm.size - offset)
            pcm.copyInto(frame, size, offset, offset + count)
            size += count
            offset += count
            if (size == frameBytes) {
                onFrame(frame)
                size = 0
            }
        }
    }
}

/** 발표 세션마다 VAD 자원을 열고 닫는다. 이전 세션의 늦은 PCM은 버린다. */
internal class PcmVad(private val onVoice: (Boolean, Long) -> Unit) {
    private var vad: VadWebRTC? = null
    private var session: Long? = null
    private val frames = PcmFrameBuffer()
    private var lastChunkAt: Long? = null

    @Synchronized
    fun start(sessionId: Long) {
        close()
        vad = VadWebRTC(
            sampleRate = SampleRate.SAMPLE_RATE_16K,
            frameSize = FrameSize.FRAME_SIZE_320,
            mode = Mode.NORMAL,
        )
        session = sessionId
    }

    @Synchronized
    fun accept(pcm: ByteArray, nowMs: Long, sessionId: Long) {
        val engine = vad ?: return
        if (session != sessionId) return
        if (lastChunkAt?.let { nowMs - it > SilenceDetector.DEFAULT_INPUT_LOST_MS } == true) frames.reset()
        lastChunkAt = nowMs
        frames.accept(pcm) { onVoice(engine.isSpeech(it), nowMs) }
    }

    @Synchronized
    fun close() {
        val old = vad
        vad = null
        session = null
        frames.reset()
        lastChunkAt = null
        old?.close()
    }
}
