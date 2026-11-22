package com.rokid.renewcxrlsample.presentation

enum class VoiceStatus {
    /** 아직 오디오가 들어오지 않음. */
    Waiting,
    Speaking,
    Silent,
    /** 오디오 청크가 끊김. 침묵으로 세지 않는다. */
    InputLost,
}

/**
 * 오디오 청크의 소리 크기(RMS)로 발화·침묵·입력 끊김을 구분하고, 말한 뒤 [pauseMs] 동안 조용하면 멈춤을 한 번 알린다.
 *
 * - 말이 다시 들릴 때까지 멈춤은 다시 알리지 않는다 (중복 멈춤 방지).
 * - 입력이 끊겼던 시간은 침묵에 넣지 않는다. 입력이 돌아온 시점부터 다시 센다.
 * - Android 의존성이 없어 JVM 단위 테스트로 확인한다. SDK 콜백 스레드와 UI 스레드에서 함께 부른다.
 */
class SilenceDetector(
    /** 이 값 이상이면 발화로 본다. 실기기에서 조정하는 값 (화면에서 바꿀 수 있음). */
    @Volatile var speechRms: Double = DEFAULT_SPEECH_RMS,
    /** 말한 뒤 이 시간 동안 조용하면 멈춤. 실기기 테스트 중 화면에서 바꿀 수 있음. */
    @Volatile var pauseMs: Long = DEFAULT_PAUSE_MS,
    private val inputLostMs: Long = DEFAULT_INPUT_LOST_MS,
) {
    private var lastChunkAt: Long? = null
    private var silenceSince = 0L
    private var spokeSincePause = false
    private var speaking = false
    private var inputRevision = 0L
    private var speechRevision = 0L

    @Synchronized
    fun speechRevision(): Long = speechRevision

    /** Brief speech/input gaps also invalidate a pending hint between UI ticks. */
    @Synchronized
    fun revision(): Long = inputRevision

    @Synchronized
    fun isCurrentPause(revision: Long, nowMs: Long): Boolean =
        revision == inputRevision && status(nowMs) == VoiceStatus.Silent &&
            nowMs - silenceSince >= pauseMs

    @Synchronized
    fun reset() {
        inputRevision++
        speechRevision++
        lastChunkAt = null
        silenceSince = 0L
        spokeSincePause = false
        speaking = false
    }

    @Synchronized
    fun onChunk(rms: Double, nowMs: Long) = onVoice(rms >= speechRms, nowMs)

    /** 발표 화면은 VAD 결과를 사용하고, 기존 RMS 진단 호출은 유지한다. */
    @Synchronized
    fun onVoice(isSpeech: Boolean, nowMs: Long) {
        val last = lastChunkAt
        if (last == null || nowMs - last > inputLostMs) {
            silenceSince = nowMs
            inputRevision++
        }
        if (isSpeech && !speaking) {
            inputRevision++
            speechRevision++
        }
        lastChunkAt = nowMs
        speaking = isSpeech
        if (speaking) {
            silenceSince = nowMs
            spokeSincePause = true
        }
    }

    /** 주기적으로 부른다. 이번 호출에서 멈춤이 확정되면 true. */
    @Synchronized
    fun tick(nowMs: Long): Boolean {
        if (status(nowMs) != VoiceStatus.Silent) return false
        if (!spokeSincePause || nowMs - silenceSince < pauseMs) return false
        spokeSincePause = false
        return true
    }

    @Synchronized
    fun status(nowMs: Long): VoiceStatus {
        val last = lastChunkAt ?: return VoiceStatus.Waiting
        return when {
            nowMs - last > inputLostMs -> VoiceStatus.InputLost
            speaking -> VoiceStatus.Speaking
            else -> VoiceStatus.Silent
        }
    }

    /** 현재 침묵이 이어진 시간. 발화 중·입력 끊김이면 0. */
    @Synchronized
    fun silentForMs(nowMs: Long): Long =
        if (status(nowMs) == VoiceStatus.Silent) nowMs - silenceSince else 0L

    companion object {
        /** 팀 결정: 3초 멈춤. */
        const val DEFAULT_PAUSE_MS = 3_000L
        const val DEFAULT_INPUT_LOST_MS = 1_000L
        /** 진단 화면의 무음 판정값과 같은 출발점. 실기기 RMS를 보고 조정한다. */
        const val DEFAULT_SPEECH_RMS = 0.01
    }
}
