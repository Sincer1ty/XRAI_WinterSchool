package com.rokid.renewcxrlsample.presentation

import com.rokid.renewcxrlsample.script.PrepFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PresentationPhase { Idle, Running, Halted, Stopped }

/** 자동 판단을 멈춘 이유. */
enum class HaltReason { ConnectionLost, AudioStopped, SttStopped, ScriptNotPrepared, ServerUnavailable, GlassViewClosed }

/** 서버가 고른 멈춤 힌트. */
data class PauseHint(val text: String, val keywords: List<String>, val partIndex: Int?)

/** 안경에 실제로 띄우는 키워드. 빈 키워드는 빼고 최대 [PresentationController.MAX_KEYWORDS]개. */
fun displayKeywords(keywords: List<String>): List<String> =
    keywords.map { it.trim() }.filter { it.isNotEmpty() }.take(PresentationController.MAX_KEYWORDS)

/** 안경에 띄울 키워드 한 줄. */
fun keywordLine(keywords: List<String>): String = displayKeywords(keywords).joinToString(" · ")

/** 서버와 같은 비교 규칙: 띄어쓰기를 무시하고 부분 문자열로 찾는다. */
internal fun spokenNorm(text: String): String = text.filterNot(Char::isWhitespace).lowercase()

sealed interface PauseOutcome {
    data class Hint(val hint: PauseHint) : PauseOutcome
    /** 파트 미확인·마지막 파트 등으로 띄울 힌트가 없음. status는 서버 판정 (matched/unknown/ambiguous). */
    data class NoHint(val status: String) : PauseOutcome
    data class Failure(val failure: PrepFailure) : PauseOutcome
}

sealed interface DeliveryOutcome {
    data object Delivered : DeliveryOutcome
    data object NotDelivered : DeliveryOutcome
    data class Failure(val failure: PrepFailure) : DeliveryOutcome
}

/** 화면에 보여줄 마지막 처리 결과. */
enum class NoteKind {
    Started, PauseRequested, NoHint, PauseFailed, HintSent, HintConfirmed, HintNoAck, HintShowFailed,
    NotDeliveredYet, DeliveryCheckFailed, Delivered, HintExpired, HideFailed, Stopped,
}

data class PresentationNote(val kind: NoteKind, val detail: String? = null)

/**
 * 안경에 표시 요청한 힌트. [spokenSince]는 표시 후 확정된 문장 (전달 판단 입력).
 * [shownAtMs]는 표시 요청이 성공한 시각 (elapsedRealtime). 5초 숨김의 기준.
 * [partialAtShow]는 표시 순간의 STT 중간 결과. 그 뒤에 덧붙은 말만 전달 판단에 쓴다.
 */
data class ShownHint(
    val id: Long,
    val hint: PauseHint,
    val spokenSince: List<String> = emptyList(),
    val shownAtMs: Long? = null,
    val partialAtShow: String = "",
)

data class PresentationState(
    val phase: PresentationPhase = PresentationPhase.Idle,
    val scriptSha256: String? = null,
    val recentFinals: List<String> = emptyList(),
    /** 멈춤 판단에 보내는 확정 문장 (최대 [PresentationController.PAUSE_HISTORY]). */
    val history: List<String> = emptyList(),
    val hint: ShownHint? = null,
    val pauseInFlight: Boolean = false,
    val haltReason: HaltReason? = null,
    val note: PresentationNote? = null,
)

data class PauseRequest(val generation: Long, val id: Long, val scriptSha256: String, val text: String)

data class DeliveryRequest(
    val generation: Long,
    val hintId: Long,
    val scriptSha256: String,
    val hint: PauseHint,
    val text: String,
    val sequence: Long = 0,
)

/**
 * 발표 중 상태 규칙 (파트 4 + 파트 5 최소 연결).
 *
 * - 발표 시작·종료·중단마다 generation을 올려, 이전 발표에서 늦게 온 응답을 버린다.
 * - 멈춤 요청은 한 번에 하나, 힌트가 떠 있는 동안에는 새 멈춤 힌트를 요청하지 않는다.
 * - 같은 확정 문장이 연달아 오면 한 번만 처리한다.
 * - 키워드는 표시 후 [HINT_VISIBLE_MS]가 지나면 숨긴다. 그 전에 전달로 판단되면 바로 숨긴다.
 * - 시간 초과로 숨긴 것은 전달 완료로 기록하지 않는다 (HintExpired).
 * - 띄울 키워드가 없으면 힌트 문장으로 대신하지 않고 띄우지 않는다.
 *
 * 모든 함수는 메인 스레드에서 부른다. Android 의존성이 없어 JVM 단위 테스트로 확인한다.
 */
class PresentationController {

    private val _state = MutableStateFlow(PresentationState())
    val state: StateFlow<PresentationState> = _state.asStateFlow()

    private var generation = 0L
    private var nextId = 0L
    private var pendingPauseId: Long? = null
    private var latestDeliverySequence = 0L

    fun start(scriptSha256: String): Boolean {
        if (_state.value.phase == PresentationPhase.Running) return false
        generation++
        pendingPauseId = null
        _state.value = PresentationState(
            phase = PresentationPhase.Running,
            scriptSha256 = scriptSha256,
            note = PresentationNote(NoteKind.Started),
        )
        return true
    }

    /** 발표 종료. 표시 중이던 힌트가 있었으면 true (호출자가 안경에서 지운다). */
    fun stop(): Boolean {
        val cur = _state.value
        if (cur.phase == PresentationPhase.Idle || cur.phase == PresentationPhase.Stopped) return false
        generation++
        pendingPauseId = null
        _state.value = cur.copy(
            phase = PresentationPhase.Stopped,
            hint = null,
            pauseInFlight = false,
            note = PresentationNote(NoteKind.Stopped),
        )
        return cur.hint != null
    }

    /** 오류로 자동 판단 중지. 이후 응답은 버린다. 다시 하려면 종료 후 새로 시작한다. */
    fun halt(reason: HaltReason) {
        val cur = _state.value
        if (cur.phase != PresentationPhase.Running) return
        generation++
        pendingPauseId = null
        _state.value = cur.copy(phase = PresentationPhase.Halted, pauseInFlight = false, haltReason = reason)
    }

    /** 발화 재개·입력 중단 후 이전 쉼의 응답은 표시하지 않는다. */
    fun invalidatePause() {
        pendingPauseId = null
        if (_state.value.pauseInFlight) _state.value = _state.value.copy(pauseInFlight = false)
    }

    /** STT 확정 문장. 힌트가 떠 있으면 전달 판단 요청을 돌려준다. */
    fun onFinal(text: String): DeliveryRequest? {
        val cur = _state.value
        val sentence = text.trim()
        if (cur.phase != PresentationPhase.Running || sentence.isEmpty()) return null
        if (sentence == cur.recentFinals.lastOrNull()) return null
        invalidatePause()
        val recent = (cur.recentFinals + sentence).takeLast(RECENT_FINALS)
        val history = (cur.history + sentence).takeLast(PAUSE_HISTORY)
        // 확정되면 중간 결과가 새로 시작되므로 기준을 비운다.
        val hint = cur.hint?.let {
            it.copy(spokenSince = (it.spokenSince + sentence).takeLast(RECENT_FINALS), partialAtShow = "")
        }
        _state.value = cur.copy(recentFinals = recent, history = history, hint = hint, pauseInFlight = false)
        if (hint == null) return null
        return DeliveryRequest(generation, hint.id, cur.scriptSha256!!, hint.hint, hint.spokenSince.joinToString(" "), ++latestDeliverySequence)
    }

    /**
     * 멈춤 감지. 요청할 수 있으면 멈춤 힌트 요청을 돌려준다.
     *
     * [partial]은 아직 확정되지 않은 STT 중간 결과. 확정 문장을 기다리면 힌트가 늦어지므로 마지막 문장으로 함께 보낸다.
     * 파트 앞부분에서 이미 말한 키워드를 다시 띄우지 않도록 최근 3문장이 아니라 [PresentationState.history]를 보낸다.
     * 서버는 최신 문장부터 거꾸로 보며 다른 파트 문장을 만나면 멈춘다.
     */
    fun onPause(partial: String = ""): PauseRequest? {
        val cur = _state.value
        if (cur.phase != PresentationPhase.Running || cur.hint != null || cur.pauseInFlight) return null
        val pending = partial.trim().takeIf { it.isNotEmpty() && it != cur.history.lastOrNull() }
        val sentences = cur.history + listOfNotNull(pending)
        if (sentences.isEmpty()) return null
        // 서버가 최신 문장부터 파트를 판단하도록 문장을 줄바꿈으로 구분한다 (오래된 것 → 최신).
        val text = sentences.joinToString("\n") { it.replace('\n', ' ') }
        val request = PauseRequest(generation, ++nextId, cur.scriptSha256!!, text)
        pendingPauseId = request.id
        _state.value = cur.copy(pauseInFlight = true, note = PresentationNote(NoteKind.PauseRequested))
        return request
    }

    /** 멈춤 응답 반영. 안경에 띄울 힌트가 있으면 돌려준다. 늦은·이전 발표의 응답이면 null. */
    fun completePause(request: PauseRequest, outcome: PauseOutcome): ShownHint? {
        val cur = _state.value
        if (request.generation != generation || request.id != pendingPauseId || cur.phase != PresentationPhase.Running) {
            return null
        }
        pendingPauseId = null
        return when (outcome) {
            is PauseOutcome.Hint -> {
                val shownKeywords = displayKeywords(outcome.hint.keywords)
                if (shownKeywords.isEmpty()) {
                    _state.value = cur.copy(pauseInFlight = false, note = PresentationNote(NoteKind.NoHint, NO_KEYWORDS))
                    return null
                }
                // 안경에 보이는 키워드로만 전달을 판단한다. 안 보이는 4·5번째 키워드를 말해 숨는 일을 막는다.
                val shown = ShownHint(request.id, outcome.hint.copy(keywords = shownKeywords))
                _state.value = cur.copy(pauseInFlight = false, hint = shown)
                shown
            }
            is PauseOutcome.NoHint -> {
                _state.value = cur.copy(pauseInFlight = false, note = PresentationNote(NoteKind.NoHint, outcome.status))
                null
            }
            is PauseOutcome.Failure -> {
                _state.value = cur.copy(pauseInFlight = false, note = PresentationNote(NoteKind.PauseFailed))
                haltForFailure(outcome.failure)
                null
            }
        }
    }

    /** 안경 표시 요청 결과. 요청이 거부·실패했으면 힌트를 떠 있지 않은 것으로 되돌린다. */
    fun onHintRendered(hintId: Long, sent: Boolean, nowMs: Long, partialAtShow: String = "") {
        val cur = _state.value
        val hint = cur.hint
        if (hint == null || hint.id != hintId) return
        val line = keywordLine(hint.hint.keywords)
        _state.value = if (sent) {
            cur.copy(
                hint = hint.copy(shownAtMs = nowMs, partialAtShow = partialAtShow.trim()),
                note = PresentationNote(NoteKind.HintSent, line),
            )
        } else {
            cur.copy(hint = null, note = PresentationNote(NoteKind.HintShowFailed, line))
        }
    }

    /**
     * 표시 요청 뒤 안경 응답. [confirmed]: true 갱신 응답, false 오류 응답, null 제한 시간 내 응답 없음.
     * 오류면 힌트가 안 보인 것으로 되돌린다. 응답이 없으면 보였는지 모르므로 힌트·표시 시간은 유지한다.
     */
    fun onHintAck(hintId: Long, confirmed: Boolean?) {
        val cur = _state.value
        val hint = cur.hint
        if (cur.phase != PresentationPhase.Running || hint == null || hint.id != hintId) return
        val line = keywordLine(hint.hint.keywords)
        _state.value = when (confirmed) {
            true -> cur.copy(note = PresentationNote(NoteKind.HintConfirmed, line))
            false -> cur.copy(hint = null, note = PresentationNote(NoteKind.HintShowFailed, line))
            null -> cur.copy(note = PresentationNote(NoteKind.HintNoAck, line))
        }
    }

    /**
     * STT 중간 결과. 띄운 키워드가 들리면 확정 문장·서버 판단을 기다리지 않고 바로 전달로 본다 (true면 호출자가 숨긴다).
     * 서버 전달 판단과 같은 규칙(띄어쓰기 무시 부분 문자열)이다. 표시 전부터 이어진 중간 결과는 표시 뒤 덧붙은 부분만 본다.
     */
    fun onPartial(partial: String): Boolean {
        val cur = _state.value
        val hint = cur.hint
        if (cur.phase != PresentationPhase.Running || hint?.shownAtMs == null) return false
        val text = partial.trim()
        val fresh = if (hint.partialAtShow.isNotEmpty() && text.startsWith(hint.partialAtShow)) {
            text.removePrefix(hint.partialAtShow)
        } else text
        val spoken = spokenNorm(fresh)
        val heard = spoken.isNotEmpty() && hint.hint.keywords.any { kw ->
            spokenNorm(kw).let { it.isNotEmpty() && it in spoken }
        }
        if (!heard) return false
        latestDeliverySequence++ // 이 힌트에 대해 늦게 오는 서버 판단 응답은 버린다.
        _state.value = cur.copy(hint = null, note = PresentationNote(NoteKind.Delivered, keywordLine(hint.hint.keywords)))
        return true
    }

    /** 표시 시간이 끝났으면 힌트를 내리고 true (호출자가 안경에서 지운다). 전달 완료로 기록하지 않는다. */
    fun expireIfDue(nowMs: Long): Boolean {
        val cur = _state.value
        val hint = cur.hint ?: return false
        val left = remainingMs(nowMs) ?: return false
        if (left > 0) return false
        _state.value = cur.copy(hint = null, note = PresentationNote(NoteKind.HintExpired, keywordLine(hint.hint.keywords)))
        return true
    }

    /** 표시 중인 키워드가 사라지기까지 남은 시간. 표시 중이 아니면 null. */
    fun remainingMs(nowMs: Long): Long? {
        val shownAt = _state.value.hint?.shownAtMs ?: return null
        return (HINT_VISIBLE_MS - (nowMs - shownAt)).coerceAtLeast(0)
    }

    /** 전달 판단 응답. 전달이면 true (호출자가 안경에서 지운다). */
    fun completeDelivery(request: DeliveryRequest, outcome: DeliveryOutcome): Boolean {
        val cur = _state.value
        if (request.generation != generation || cur.hint?.id != request.hintId ||
            cur.phase != PresentationPhase.Running || request.sequence != latestDeliverySequence) return false
        return when (outcome) {
            DeliveryOutcome.Delivered -> {
                _state.value = cur.copy(
                    hint = null,
                    note = PresentationNote(NoteKind.Delivered, keywordLine(request.hint.keywords)),
                )
                true
            }
            DeliveryOutcome.NotDelivered -> {
                _state.value = cur.copy(note = PresentationNote(NoteKind.NotDeliveredYet))
                false
            }
            is DeliveryOutcome.Failure -> {
                _state.value = cur.copy(note = PresentationNote(NoteKind.DeliveryCheckFailed))
                haltForFailure(outcome.failure)
                false
            }
        }
    }

    /** 숨김 요청이 실패함. 안경에 이전 힌트가 남아 있을 수 있음을 알린다. */
    fun onHideFailed() {
        val cur = _state.value
        _state.value = cur.copy(note = PresentationNote(NoteKind.HideFailed))
    }

    private fun haltForFailure(failure: PrepFailure) {
        halt(if (failure is PrepFailure.Server && failure.code == "script_not_prepared")
            HaltReason.ScriptNotPrepared else HaltReason.ServerUnavailable)
    }

    companion object {
        /** 화면 표시·전달 판단에 쓰는 최근 확정 문장 수. */
        const val RECENT_FINALS = 3

        /** 멈춤 판단에 보내는 확정 문장 수. 파트 앞부분에서 말한 키워드까지 세기 위해 넉넉히 둔다. */
        const val PAUSE_HISTORY = 20

        /** 안경에 한 번에 띄우는 최대 키워드 수 (팀 결정 2026-11-21). */
        const val MAX_KEYWORDS = 3

        /** 키워드 표시 유지 시간 (팀 결정 2026-11-21). */
        const val HINT_VISIBLE_MS = 5_000L

        /** 서버가 힌트를 줬지만 띄울 키워드가 없을 때의 메모. */
        const val NO_KEYWORDS = "no_keywords"
    }
}
