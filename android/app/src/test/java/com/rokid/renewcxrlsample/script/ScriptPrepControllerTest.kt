package com.rokid.renewcxrlsample.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptPrepControllerTest {

    private fun analysisFor(ticket: AnalysisTicket, revision: Long? = ticket.revision) =
        AnalysisOutcome.Success(
            ScriptAnalysis(
                scriptSha256 = sha256Hex(ticket.script),
                clientRevision = revision,
                analyzer = "mock",
                parts = listOf(ScriptPart(1, "소개", listOf("발표"), "안녕하세요")),
            )
        )

    @Test
    fun blankScriptCannotBeSubmitted() {
        val c = ScriptPrepController()
        assertNull(c.beginAnalysis())
        c.editScript("   \n\t")
        assertFalse(c.state.value.canSubmit)
        assertNull(c.beginAnalysis())
        assertEquals(PrepPhase.Editing, c.state.value.phase)
    }

    @Test
    fun happyPathRequiresUserConfirmation() {
        val c = ScriptPrepController()
        c.editScript("안녕하세요. 발표를 시작합니다.")
        val t = c.beginAnalysis()!!
        assertEquals(PrepPhase.Analyzing, c.state.value.phase)
        assertNull("분석 중 중복 요청 금지", c.beginAnalysis())

        assertTrue(c.completeAnalysis(t, analysisFor(t)))
        assertEquals(PrepPhase.Reviewing, c.state.value.phase)
        assertFalse("확인 전에는 준비 상태가 아님", c.state.value.isReady)

        assertTrue(c.confirmReady())
        assertTrue(c.state.value.isReady)
    }

    @Test
    fun confirmOnlyFromReviewing() {
        val c = ScriptPrepController()
        assertFalse(c.confirmReady())
        c.editScript("x")
        val t = c.beginAnalysis()!!
        assertFalse(c.confirmReady())
        c.completeAnalysis(t, AnalysisOutcome.Failure(PrepFailure.Network))
        assertFalse(c.confirmReady())
        assertEquals(PrepPhase.Failed, c.state.value.phase)
    }

    @Test
    fun editingClearsReadyState() {
        val c = ScriptPrepController()
        c.editScript("원본 대본")
        val t = c.beginAnalysis()!!
        c.completeAnalysis(t, analysisFor(t))
        c.confirmReady()

        c.editScript("원본 대본 수정")
        val s = c.state.value
        assertFalse(s.isReady)
        assertEquals(PrepPhase.Editing, s.phase)
        assertNull(s.analysis)
    }

    @Test
    fun sameTextDoesNotClearReadyState() {
        val c = ScriptPrepController()
        c.editScript("대본")
        val t = c.beginAnalysis()!!
        c.completeAnalysis(t, analysisFor(t))
        c.confirmReady()
        c.editScript("대본")
        assertTrue(c.state.value.isReady)
    }

    @Test
    fun lateResultForOldScriptIsIgnored() {
        val c = ScriptPrepController()
        c.editScript("첫 번째 대본")
        val old = c.beginAnalysis()!!
        c.editScript("두 번째 대본")
        assertFalse(c.completeAnalysis(old, analysisFor(old)))
        assertEquals(PrepPhase.Editing, c.state.value.phase)
        assertNull(c.state.value.analysis)

        // 새 요청을 보낸 뒤 이전 요청이 늦게 도착해도 무시한다.
        val current = c.beginAnalysis()!!
        assertFalse(c.completeAnalysis(old, analysisFor(old)))
        assertEquals(PrepPhase.Analyzing, c.state.value.phase)
        assertTrue(c.completeAnalysis(current, analysisFor(current)))
        assertEquals("두 번째 대본", c.state.value.script)
    }

    @Test
    fun editBackToSameTextStillIgnoresOldRequest() {
        val c = ScriptPrepController()
        c.editScript("A")
        val old = c.beginAnalysis()!!
        c.editScript("AB")
        c.editScript("A")
        assertFalse(c.completeAnalysis(old, analysisFor(old)))
    }

    @Test
    fun resultAfterCancelIsIgnored() {
        val c = ScriptPrepController()
        c.editScript("대본")
        val t = c.beginAnalysis()!!
        c.cancelAnalysis()
        assertFalse(c.completeAnalysis(t, analysisFor(t)))
        assertEquals(PrepPhase.Editing, c.state.value.phase)
    }

    @Test
    fun hashMismatchIsFormatFailure() {
        val c = ScriptPrepController()
        c.editScript("대본")
        val t = c.beginAnalysis()!!
        val wrong = AnalysisOutcome.Success(
            (analysisFor(t).analysis).copy(scriptSha256 = sha256Hex("다른 대본"))
        )
        assertTrue(c.completeAnalysis(t, wrong))
        assertEquals(PrepPhase.Failed, c.state.value.phase)
        assertTrue(c.state.value.failure is PrepFailure.InvalidFormat)
    }

    @Test
    fun revisionMismatchIsFormatFailure() {
        val c = ScriptPrepController()
        c.editScript("대본")
        val t = c.beginAnalysis()!!
        c.completeAnalysis(t, analysisFor(t, revision = t.revision + 5))
        assertTrue(c.state.value.failure is PrepFailure.InvalidFormat)
    }

    @Test
    fun failureCanBeRetried() {
        val c = ScriptPrepController()
        c.editScript("대본")
        val t1 = c.beginAnalysis()!!
        c.completeAnalysis(t1, AnalysisOutcome.Failure(PrepFailure.Timeout))
        assertEquals(PrepFailure.Timeout, c.state.value.failure)

        val t2 = c.beginAnalysis()
        assertNotNull(t2)
        assertNull(c.state.value.failure)
        c.completeAnalysis(t2!!, analysisFor(t2))
        assertEquals(PrepPhase.Reviewing, c.state.value.phase)
    }

    @Test
    fun changingServerUrlClearsReadyStateButSameUrlKeepsIt() {
        val c = ScriptPrepController()
        c.editScript("대본")
        val t = c.beginAnalysis(" http://pc:8000 ")!!
        c.completeAnalysis(t, analysisFor(t))
        c.confirmReady()
        assertEquals("http://pc:8000", c.state.value.analyzedServerUrl)

        assertFalse(c.onServerUrlChanged("http://pc:8000"))
        assertFalse("끝의 / 나 http:// 생략은 같은 서버", c.onServerUrlChanged("pc:8000/"))
        assertTrue(c.state.value.isReady)

        assertTrue(c.onServerUrlChanged("http://other:8000"))
        assertFalse(c.state.value.isReady)
        assertNull(c.state.value.analysis)
        assertEquals(PrepPhase.Editing, c.state.value.phase)
    }

    @Test
    fun loadedFileNameIsKeptWhileEditing() {
        val c = ScriptPrepController()
        c.loadScript("파일 대본", "talk.md")
        c.editScript("파일 대본 수정")
        assertEquals("talk.md", c.state.value.sourceName)
        assertEquals(2L, c.state.value.revision)
    }
    @Test
    fun lateFileResultDoesNotOverwriteEditedScriptOrNewAnalysis() {
        val c = ScriptPrepController()
        c.loadScript("처음 대본", "first.md")
        val revisionAtRead = c.state.value.revision
        c.editScript("사용자가 새로 수정한 대본")
        val current = c.beginAnalysis()!!
        assertFalse(c.loadScript("뒤늦게 읽은 파일", "late.md", revisionAtRead))
        assertEquals("사용자가 새로 수정한 대본", c.state.value.script)
        assertEquals("first.md", c.state.value.sourceName)
        assertEquals(PrepPhase.Analyzing, c.state.value.phase)
        assertTrue(c.completeAnalysis(current, analysisFor(current)))
    }

    @Test
    fun editBackToOriginalTextStillRejectsOldFileResult() {
        val c = ScriptPrepController()
        c.editScript("A")
        val revisionAtRead = c.state.value.revision
        c.editScript("AB")
        c.editScript("A")
        assertFalse(c.loadScript("파일 내용", "late.txt", revisionAtRead))
        assertEquals("A", c.state.value.script)
    }

    @Test
    fun fileResultAppliesWhenRevisionHasNotChanged() {
        val c = ScriptPrepController()
        c.editScript("기존 대본")
        assertTrue(c.loadScript("불러온 대본", "new.md", c.state.value.revision))
        assertEquals("불러온 대본", c.state.value.script)
        assertEquals("new.md", c.state.value.sourceName)
    }

    @Test
    fun ownerCancellationLeavesDraftEditableAndRejectsLateResponse() {
        val c = ScriptPrepController()
        c.editScript("보존할 대본")
        val ticket = c.beginAnalysis()!!
        c.cancelAnalysis(ticket)
        assertEquals("보존할 대본", c.state.value.script)
        assertEquals(PrepPhase.Editing, c.state.value.phase)
        assertFalse(c.completeAnalysis(ticket, analysisFor(ticket)))
        assertNotNull("재진입 후 새 분석 가능", c.beginAnalysis())
    }

    @Test
    fun oldRequestCleanupCannotCancelNewerRequest() {
        val c = ScriptPrepController()
        c.editScript("대본")
        val old = c.beginAnalysis()!!
        c.cancelAnalysis(old)
        val current = c.beginAnalysis()!!
        c.cancelAnalysis(old)
        assertEquals(PrepPhase.Analyzing, c.state.value.phase)
        assertTrue(c.completeAnalysis(current, analysisFor(current)))
        c.cancelAnalysis(current)
        assertEquals("성공 뒤 finally 정리도 결과를 지우지 않음", PrepPhase.Reviewing, c.state.value.phase)
    }

}
