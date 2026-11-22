package com.rokid.renewcxrlsample.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceDetectorTest {

    private val loud = 0.1
    private val quiet = 0.0

    /** 20 ms마다 청크를 넣는다. */
    private fun SilenceDetector.feed(fromMs: Long, toMs: Long, rms: Double) {
        var t = fromMs
        while (t <= toMs) {
            onChunk(rms, t)
            t += 20
        }
    }

    @Test
    fun pauseFiresOnceAfterThreeSecondsOfSilenceFollowingSpeech() {
        val d = SilenceDetector()
        d.feed(0, 1_000, loud)
        d.feed(1_020, 3_900, quiet)
        assertFalse("2.9초 침묵은 멈춤 아님", d.tick(3_900))
        d.feed(3_920, 4_100, quiet)
        assertTrue(d.tick(4_100))
        d.feed(4_120, 9_000, quiet)
        assertFalse("다시 말하기 전에는 중복 멈춤 없음", d.tick(9_000))

        d.feed(9_020, 9_500, loud)
        d.feed(9_520, 12_600, quiet)
        assertTrue("말한 뒤 다시 3초 멈추면 다시 알림", d.tick(12_600))
    }

    @Test
    fun silenceWithoutSpeechIsNotAPause() {
        val d = SilenceDetector()
        d.feed(0, 10_000, quiet)
        assertFalse(d.tick(10_000))
        assertEquals(VoiceStatus.Silent, d.status(10_000))
    }

    @Test
    fun inputGapIsNotCountedAsSilence() {
        val d = SilenceDetector()
        d.feed(0, 500, loud)
        // 0.5초 이후 청크가 끊김 → 입력 끊김, 멈춤 아님
        assertEquals(VoiceStatus.InputLost, d.status(5_000))
        assertFalse(d.tick(5_000))
        // 입력이 돌아온 시점부터 침묵을 다시 센다
        d.feed(5_000, 7_000, quiet)
        assertFalse(d.tick(7_000))
        d.feed(7_020, 8_020, quiet)
        assertTrue(d.tick(8_020))
    }

    @Test
    fun noAudioYetIsWaiting() {
        val d = SilenceDetector()
        assertEquals(VoiceStatus.Waiting, d.status(1_000))
        assertFalse(d.tick(10_000))
    }

    @Test
    fun briefSpeechInvalidatesOldPauseEvenAfterAnotherSilence() {
        val d = SilenceDetector()
        d.feed(0, 500, loud)
        d.feed(520, 3_600, quiet)
        assertTrue(d.tick(3_600))
        val old = d.revision()
        assertTrue(d.isCurrentPause(old, 3_600))
        d.onChunk(loud, 3_620)
        d.feed(3_640, 7_000, quiet)
        assertFalse(d.isCurrentPause(old, 7_000))
        assertTrue(d.isCurrentPause(d.revision(), 7_000))
        d.reset()
        assertFalse(d.isCurrentPause(old, 7_000))
    }

    @Test
    fun vadSpeechDecisionDoesNotUseDiagnosticRmsThreshold() {
        val d = SilenceDetector(speechRms = 1.0)
        val before = d.speechRevision()
        d.onVoice(true, 0)
        assertEquals(VoiceStatus.Speaking, d.status(0))
        assertTrue(d.speechRevision() > before)
        val speaking = d.speechRevision()
        d.onVoice(true, 20)
        d.onVoice(false, 40)
        assertEquals(speaking, d.speechRevision())
        for (time in 60L..3_020L step 20) d.onVoice(false, time)
        assertTrue(d.tick(3_020))
        assertFalse(d.tick(3_020))
        d.onVoice(true, 3_040)
        assertTrue(d.speechRevision() > speaking)
    }

    @Test
    fun thresholdIsAdjustable() {
        val d = SilenceDetector(speechRms = 0.2)
        d.feed(0, 1_000, loud) // 0.1 < 0.2 → 발화 아님
        d.feed(1_020, 5_000, quiet)
        assertFalse(d.tick(5_000))
        d.speechRms = 0.05
        d.feed(5_020, 5_500, loud)
        assertEquals(VoiceStatus.Speaking, d.status(5_500))
    }
}
