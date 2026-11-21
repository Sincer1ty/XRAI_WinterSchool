package com.rokid.renewcxrlsample.presentation

import com.rokid.renewcxrlsample.script.PrepFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationControllerTest {

    @Test
    fun closedGlassesDisplayStopsAndRejectsPendingHint() {
        val controller = running()
        controller.onFinal("발표 불안")
        val pending = controller.onPause()!!
        controller.halt(HaltReason.GlassViewClosed)
        assertEquals(PresentationPhase.Halted, controller.state.value.phase)
        assertNull(controller.completePause(pending, PauseOutcome.Hint(hint)))
        assertNull(controller.onPause())
    }

    private val hint = PauseHint("대본 · 불안", listOf("대본", "불안"), 1)

    private fun running(): PresentationController = PresentationController().apply { start("sha") }

    /** 문장 → 멈춤 → 힌트 표시까지 진행한 상태. */
    private fun withHint(): Pair<PresentationController, ShownHint> {
        val c = running()
        c.onFinal("발표를 시작합니다")
        val req = c.onPause()!!
        val shown = c.completePause(req, PauseOutcome.Hint(hint))!!
        c.onHintRendered(shown.id, sent = true, nowMs = SHOWN_AT)
        return c to shown
    }

    @Test
    fun keywordsHideAfterFiveSecondsWithoutCountingAsDelivered() {
        val (c, _) = withHint()
        assertEquals(5_000L, c.remainingMs(SHOWN_AT))
        assertFalse("5초 전에는 유지", c.expireIfDue(SHOWN_AT + 4_999))
        assertNotNull(c.state.value.hint)

        assertTrue(c.expireIfDue(SHOWN_AT + 5_000))
        assertNull(c.state.value.hint)
        assertEquals("시간 초과는 전달 완료가 아님", NoteKind.HintExpired, c.state.value.note?.kind)
        assertFalse("이미 숨김", c.expireIfDue(SHOWN_AT + 6_000))
        assertNotNull("숨긴 뒤 다음 멈춤에 다시 요청 가능", c.onPause())
    }

    @Test
    fun deliveryBeforeFiveSecondsHidesImmediately() {
        val (c, _) = withHint()
        val d = c.onFinal("대본을 잊습니다")!!
        assertTrue(c.completeDelivery(d, DeliveryOutcome.Delivered))
        assertNull(c.remainingMs(SHOWN_AT + 1_000))
        assertFalse("전달로 숨긴 뒤 타이머가 다시 숨기지 않음", c.expireIfDue(SHOWN_AT + 5_000))
        assertEquals(NoteKind.Delivered, c.state.value.note?.kind)
    }

    @Test
    fun stopCancelsPendingExpiry() {
        val (c, _) = withHint()
        assertTrue(c.stop())
        assertFalse("종료 후 남은 타이머로 숨김 없음", c.expireIfDue(SHOWN_AT + 5_000))
        assertEquals(NoteKind.Stopped, c.state.value.note?.kind)
    }

    @Test
    fun keywordLineShowsAtMostThreeNonBlankKeywords() {
        assertEquals("a · b · c", keywordLine(listOf("a", " ", "b", "c", "d")))
        assertEquals("", keywordLine(listOf(" ", "")))
    }

    @Test
    fun hintWithoutKeywordsIsNotShown() {
        val c = running()
        c.onFinal("문장")
        val req = c.onPause()!!
        assertNull(c.completePause(req, PauseOutcome.Hint(PauseHint("문장 힌트", emptyList(), 1))))
        assertNull(c.state.value.hint)
        assertEquals(NoteKind.NoHint, c.state.value.note?.kind)
        assertEquals(PresentationController.NO_KEYWORDS, c.state.value.note?.detail)
    }

    @Test
    fun pauseNeedsRecentSpeechAndUsesLastThreeSentences() {
        val c = running()
        assertNull("인식 문장이 없으면 요청하지 않음", c.onPause())
        listOf("하나", "둘", "셋", "넷").forEach { c.onFinal(it) }
        val req = c.onPause()!!
        assertEquals("둘 셋 넷", req.text)
        assertEquals("sha", req.scriptSha256)
        assertNull("응답 전 중복 멈춤 요청 금지", c.onPause())
    }

    @Test
    fun duplicateFinalIsIgnored() {
        val c = running()
        c.onFinal("같은 문장")
        c.onFinal("같은 문장")
        assertEquals(listOf("같은 문장"), c.state.value.recentFinals)
    }

    @Test
    fun hintShownThenDeliveredOnlyByMatchingResponse() {
        val (c, shown) = withHint()
        assertNull("힌트가 떠 있는 동안 새 멈춤 힌트 없음", c.onPause())

        val d1 = c.onFinal("음 잠시만요")!!
        assertEquals("음 잠시만요", d1.text)
        assertFalse(c.completeDelivery(d1, DeliveryOutcome.NotDelivered))
        assertNotNull("말을 다시 했다는 것만으로 숨기지 않음", c.state.value.hint)

        val d2 = c.onFinal("대본을 잊습니다")!!
        assertEquals("힌트 이후 문장만 보냄", "음 잠시만요 대본을 잊습니다", d2.text)
        assertTrue(c.completeDelivery(d2, DeliveryOutcome.Delivered))
        assertNull(c.state.value.hint)
        assertEquals(NoteKind.Delivered, c.state.value.note?.kind)

        // 숨긴 뒤 다음 멈춤에 다시 힌트를 요청할 수 있다
        assertNotNull(c.onPause())
        assertTrue(shown.id > 0)
    }

    @Test
    fun deliveryFailureKeepsHint() {
        val (c, _) = withHint()
        val d = c.onFinal("대본")!!
        assertFalse(c.completeDelivery(d, DeliveryOutcome.Failure(PrepFailure.Timeout)))
        assertNotNull(c.state.value.hint)
        assertEquals(NoteKind.DeliveryCheckFailed, c.state.value.note?.kind)
        assertEquals(PresentationPhase.Halted, c.state.value.phase)
        assertEquals(HaltReason.ServerUnavailable, c.state.value.haltReason)
    }

    @Test
    fun showFailureClearsHintSoNextPauseCanRetry() {
        val c = running()
        c.onFinal("문장")
        val req = c.onPause()!!
        val shown = c.completePause(req, PauseOutcome.Hint(hint))!!
        c.onHintRendered(shown.id, sent = false, nowMs = SHOWN_AT)
        assertNull(c.state.value.hint)
        assertEquals(NoteKind.HintShowFailed, c.state.value.note?.kind)
    }

    @Test
    fun lateResponsesAfterStopOrRestartAreIgnored() {
        val c = running()
        c.onFinal("문장")
        val oldPause = c.onPause()!!
        c.stop()
        assertNull(c.completePause(oldPause, PauseOutcome.Hint(hint)))

        c.start("sha")
        c.onFinal("새 문장")
        assertNull("이전 발표의 응답은 새 발표에 반영하지 않음", c.completePause(oldPause, PauseOutcome.Hint(hint)))
        assertNull(c.state.value.hint)
    }

    @Test
    fun lateDeliveryForOldHintIsIgnored() {
        val (c, _) = withHint()
        val d = c.onFinal("대본")!!
        assertTrue(c.completeDelivery(d, DeliveryOutcome.Delivered))
        assertFalse("이미 숨긴 힌트의 중복 응답 무시", c.completeDelivery(d, DeliveryOutcome.Delivered))
    }

    @Test
    fun scriptMissingOnServerHaltsAutoJudgement() {
        val c = running()
        c.onFinal("문장")
        val req = c.onPause()!!
        c.completePause(req, PauseOutcome.Failure(PrepFailure.Server("script_not_prepared", null)))
        assertEquals(PresentationPhase.Halted, c.state.value.phase)
        assertEquals(HaltReason.ScriptNotPrepared, c.state.value.haltReason)
        assertNull(c.onFinal("다음 문장"))
        assertNull(c.onPause())
    }

    @Test
    fun noHintKeepsRunning() {
        val c = running()
        c.onFinal("상관없는 말")
        val req = c.onPause()!!
        assertNull(c.completePause(req, PauseOutcome.NoHint("unknown")))
        assertEquals(PresentationPhase.Running, c.state.value.phase)
        assertEquals(NoteKind.NoHint, c.state.value.note?.kind)
    }

    @Test
    fun stopReportsWhetherAHintMustBeHidden() {
        val (c, _) = withHint()
        assertTrue(c.stop())
        assertFalse("중복 종료", c.stop())
        assertFalse(running().stop())
    }

    @Test
    fun newFinalInvalidatesPendingPause() {
        val c = running()
        c.onFinal("이전 파트")
        val request = c.onPause()!!
        c.onFinal("새 파트 설명")
        assertNull(c.completePause(request, PauseOutcome.Hint(hint)))
        assertFalse(c.state.value.pauseInFlight)
        assertNotNull(c.onPause())
    }

    @Test
    fun resumedSpeechInvalidatesPauseBeforeFinalArrives() {
        val c = running()
        c.onFinal("설명")
        val request = c.onPause()!!
        c.invalidatePause()
        assertNull(c.completePause(request, PauseOutcome.Hint(hint)))
        assertNull(c.state.value.hint)
    }

    @Test
    fun olderDeliveryCannotHideHintOrStopNewerJudgement() {
        val (c, _) = withHint()
        val older = c.onFinal("대본")!!
        val newer = c.onFinal("추가 설명")!!
        assertFalse(c.completeDelivery(older, DeliveryOutcome.Delivered))
        assertFalse(c.completeDelivery(older, DeliveryOutcome.Failure(PrepFailure.Network)))
        assertEquals(PresentationPhase.Running, c.state.value.phase)
        assertTrue(c.completeDelivery(newer, DeliveryOutcome.Delivered))
    }

    @Test
    fun networkFailureHaltsAndDiscardsLaterResponse() {
        val c = running()
        c.onFinal("설명")
        val request = c.onPause()!!
        c.completePause(request, PauseOutcome.Failure(PrepFailure.Network))
        assertEquals(PresentationPhase.Halted, c.state.value.phase)
        assertEquals(HaltReason.ServerUnavailable, c.state.value.haltReason)
        assertNull(c.completePause(request, PauseOutcome.Hint(hint)))
        assertNull(c.onPause())
    }

    private companion object {
        const val SHOWN_AT = 10_000L
    }
}
