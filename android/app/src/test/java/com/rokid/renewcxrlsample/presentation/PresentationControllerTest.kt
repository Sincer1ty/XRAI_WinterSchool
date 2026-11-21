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
    fun pauseSendsSentenceHistoryNotOnlyLastThree() {
        val c = running()
        assertNull("인식 문장이 없으면 요청하지 않음", c.onPause())
        listOf("하나", "둘", "셋", "넷").forEach { c.onFinal(it) }
        val req = c.onPause()!!
        assertEquals("파트 앞부분에서 말한 키워드도 세도록 모두 보냄", "하나\n둘\n셋\n넷", req.text)
        assertEquals("sha", req.scriptSha256)
        assertEquals("화면용 최근 문장은 3개 유지", listOf("둘", "셋", "넷"), c.state.value.recentFinals)
        assertNull("응답 전 중복 멈춤 요청 금지", c.onPause())
    }

    @Test
    fun pauseHistoryIsCapped() {
        val c = running()
        (1..25).forEach { c.onFinal("문장$it") }
        val lines = c.onPause()!!.text.split("\n")
        assertEquals(PresentationController.PAUSE_HISTORY, lines.size)
        assertEquals("문장25", lines.last())
    }

    @Test
    fun pauseUsesPendingPartialWithoutWaitingForFinal() {
        val c = running()
        assertEquals("확정 문장이 없어도 중간 결과로 요청", "발표 불안이", c.onPause(partial = " 발표 불안이 ")!!.text)

        val c2 = running()
        c2.onFinal("첫 문장")
        assertEquals("첫 문장\n둘째 문장", c2.onPause(partial = "둘째 문장")!!.text)

        val c3 = running()
        c3.onFinal("같은 문장")
        assertEquals("이미 확정된 중간 결과는 중복으로 넣지 않음", "같은 문장", c3.onPause(partial = "같은 문장")!!.text)
    }

    @Test
    fun onlyDisplayedKeywordsAreJudged() {
        val c = running()
        c.onFinal("문장")
        val req = c.onPause()!!
        val five = PauseHint("a · b · c · d · e", listOf("일", "이", "삼", "사", "오"), 1)
        val shown = c.completePause(req, PauseOutcome.Hint(five))!!
        assertEquals("안경에 보이는 3개만 판단 대상", listOf("일", "이", "삼"), shown.hint.keywords)
        c.onHintRendered(shown.id, sent = true, nowMs = SHOWN_AT)
        assertEquals(listOf("일", "이", "삼"), c.onFinal("사 오")!!.hint.keywords)
        assertFalse("안 보이는 키워드는 중간 결과로도 숨기지 않음", c.onPartial("사 오"))
    }

    @Test
    fun partialWithShownKeywordHidesImmediately() {
        val (c, _) = withHint()
        assertFalse(c.onPartial("음 그러니까"))
        assertNotNull(c.state.value.hint)
        assertTrue("띄어쓰기가 달라도 확정 문장 전에 숨김", c.onPartial("그러니까 대 본을"))
        assertNull(c.state.value.hint)
        assertEquals(NoteKind.Delivered, c.state.value.note?.kind)
        assertNull("숨긴 뒤 확정 문장은 전달 판단 요청 안 함", c.onFinal("그러니까 대본을"))
    }

    @Test
    fun partialStartedBeforeShowCountsOnlyNewWords() {
        val c = running()
        c.onFinal("발표를 시작합니다")
        val shown = c.completePause(c.onPause()!!, PauseOutcome.Hint(hint))!!
        c.onHintRendered(shown.id, sent = true, nowMs = SHOWN_AT, partialAtShow = "대본")
        assertFalse("표시 전부터 있던 말은 전달로 보지 않음", c.onPartial("대본 그래서"))
        assertTrue(c.onPartial("대본 그래서 불안"))
    }

    @Test
    fun partialBeforeShowRequestSucceedsIsIgnored() {
        val c = running()
        c.onFinal("문장")
        c.completePause(c.onPause()!!, PauseOutcome.Hint(hint))
        assertFalse("표시 요청 결과 전에는 숨기지 않음", c.onPartial("대본"))
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
    fun glassAckIsRecordedSeparatelyFromSending() {
        val (c, shown) = withHint()
        assertEquals("전송만으로는 표시 확인이 아님", NoteKind.HintSent, c.state.value.note?.kind)
        c.onHintAck(shown.id, confirmed = true)
        assertEquals(NoteKind.HintConfirmed, c.state.value.note?.kind)
        assertNotNull(c.state.value.hint)
    }

    @Test
    fun missingGlassAckKeepsHintButRecordsNoResponse() {
        val (c, shown) = withHint()
        c.onHintAck(shown.id, confirmed = null)
        assertEquals(NoteKind.HintNoAck, c.state.value.note?.kind)
        assertNotNull("보였는지 모르므로 표시 시간 정책 유지", c.state.value.hint)
        assertEquals(5_000L, c.remainingMs(SHOWN_AT))
    }

    @Test
    fun glassErrorClearsHintAndStaleAckIsIgnored() {
        val (c, shown) = withHint()
        c.onHintAck(shown.id + 1, confirmed = true)
        assertEquals("다른 힌트의 응답은 무시", NoteKind.HintSent, c.state.value.note?.kind)
        c.onHintAck(shown.id, confirmed = false)
        assertNull(c.state.value.hint)
        assertEquals(NoteKind.HintShowFailed, c.state.value.note?.kind)
        assertNotNull("다음 멈춤에 다시 요청 가능", c.onPause())
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
