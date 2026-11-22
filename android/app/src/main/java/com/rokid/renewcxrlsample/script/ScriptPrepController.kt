package com.rokid.renewcxrlsample.script

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PrepPhase {
    /** 분석 전 또는 대본 수정 후. */
    Editing,
    Analyzing,
    /** 분석 결과를 사용자가 확인하는 중. */
    Reviewing,
    /** 사용자가 결과를 확인함 → 발표 시작 가능. */
    Ready,
    Failed,
}

data class ScriptPrepState(
    val script: String = "",
    /** 대본이 바뀔 때마다 1씩 증가. 늦게 도착한 이전 대본의 결과를 걸러내는 기준. */
    val revision: Long = 0,
    val sourceName: String? = null,
    val phase: PrepPhase = PrepPhase.Editing,
    val analysis: ScriptAnalysis? = null,
    val failure: PrepFailure? = null,
    /** 분석을 요청한 서버 주소. 발표 중 요청도 대본이 준비된 이 서버로 보낸다. */
    val analyzedServerUrl: String? = null,
) {
    val isScriptBlank: Boolean get() = script.isBlank()
    val canSubmit: Boolean get() = !isScriptBlank && phase != PrepPhase.Analyzing
    val isReady: Boolean get() = phase == PrepPhase.Ready && analysis != null
}

/** 진행 중인 분석 요청 하나. 결과를 반영할 때 이 값으로 현재 대본과 일치하는지 확인한다. */
data class AnalysisTicket(
    val requestId: Long,
    val revision: Long,
    val script: String,
)

/**
 * 파트 1(대본 입력과 준비)의 상태 규칙.
 *
 * - 빈 대본은 분석 요청을 만들지 않는다.
 * - 대본이 바뀌면 분석 결과·준비 상태를 해제하고 진행 중인 요청을 무효화한다.
 * - 현재 대본(수정 번호·요청 번호·해시)과 맞지 않는 늦은 결과는 버린다.
 * - 사용자가 분석 결과를 확인해야 Ready가 된다.
 *
 * Android 의존성이 없어 JVM 단위 테스트로 확인한다.
 */
class ScriptPrepController {

    private val _state = MutableStateFlow(ScriptPrepState())
    val state: StateFlow<ScriptPrepState> = _state.asStateFlow()

    private var nextRequestId = 0L
    private var activeRequestId: Long? = null

    /** 사용자가 직접 입력·수정한 경우. 불러온 파일 이름은 유지한다. */
    @Synchronized
    fun editScript(text: String) = replaceScript(text, _state.value.sourceName)

    /** 파일에서 불러온 경우. */
    @Synchronized
    fun loadScript(text: String, sourceName: String, expectedRevision: Long? = null): Boolean {
        if (expectedRevision != null && expectedRevision != _state.value.revision) return false
        replaceScript(text, sourceName)
        return true
    }

    private fun replaceScript(text: String, sourceName: String?) {
        val cur = _state.value
        if (text == cur.script) {
            if (sourceName != cur.sourceName) _state.value = cur.copy(sourceName = sourceName)
            return
        }
        activeRequestId = null
        _state.value = cur.copy(
            script = text,
            sourceName = sourceName,
            revision = cur.revision + 1,
            phase = PrepPhase.Editing,
            analysis = null,
            failure = null,
        )
    }

    /** 분석 시작. 빈 대본이거나 이미 분석 중이면 null. */
    @Synchronized
    fun beginAnalysis(serverUrl: String = ""): AnalysisTicket? {
        val cur = _state.value
        if (!cur.canSubmit) return null
        val ticket = AnalysisTicket(++nextRequestId, cur.revision, cur.script)
        activeRequestId = ticket.requestId
        _state.value = cur.copy(
            phase = PrepPhase.Analyzing,
            analysis = null,
            failure = null,
            analyzedServerUrl = serverUrl.trim(),
        )
        return ticket
    }

    /**
     * 서버 주소가 바뀌면 이전 서버에서 만든 분석·준비 상태를 해제한다 (서버는 대본을 메모리에만 보관).
     * 해제했으면 true.
     */
    @Synchronized
    fun onServerUrlChanged(url: String): Boolean {
        val cur = _state.value
        val bound = cur.phase == PrepPhase.Analyzing ||
            cur.phase == PrepPhase.Reviewing ||
            cur.phase == PrepPhase.Ready
        if (!bound || sameServer(cur.analyzedServerUrl, url)) return false
        activeRequestId = null
        _state.value = cur.copy(phase = PrepPhase.Editing, analysis = null, failure = null, analyzedServerUrl = null)
        return true
    }

    /** 결과 반영. 현재 대본의 최신 요청이 아니면 버리고 false를 돌려준다. */
    @Synchronized
    fun completeAnalysis(ticket: AnalysisTicket, outcome: AnalysisOutcome): Boolean {
        val cur = _state.value
        val isCurrent = ticket.requestId == activeRequestId &&
            ticket.revision == cur.revision &&
            ticket.script == cur.script &&
            cur.phase == PrepPhase.Analyzing
        if (!isCurrent) return false
        activeRequestId = null

        _state.value = when (outcome) {
            is AnalysisOutcome.Failure -> cur.copy(phase = PrepPhase.Failed, failure = outcome.failure)
            is AnalysisOutcome.Success -> {
                val a = outcome.analysis
                when {
                    !a.scriptSha256.equals(sha256Hex(ticket.script), ignoreCase = true) ->
                        cur.copy(
                            phase = PrepPhase.Failed,
                            failure = PrepFailure.InvalidFormat("script hash mismatch")
                        )
                    a.clientRevision != ticket.revision ->
                        cur.copy(
                            phase = PrepPhase.Failed,
                            failure = PrepFailure.InvalidFormat("revision mismatch")
                        )
                    else -> cur.copy(phase = PrepPhase.Reviewing, analysis = a)
                }
            }
        }
        return true
    }

    /** "pc:8000", "http://pc:8000/" 처럼 표기만 다른 주소는 같은 서버로 본다. */
    private fun sameServer(a: String?, b: String): Boolean {
        fun norm(u: String) = runCatching { ScriptAnalysisClient.normalizeBaseUrl(u) }.getOrDefault(u.trim())
        return a != null && norm(a) == norm(b)
    }

    /** 진행 중인 분석을 취소하고 편집 상태로 돌아간다. 이후 도착하는 결과는 버려진다. */
    @Synchronized
    fun cancelAnalysis(ticket: AnalysisTicket? = null) {
        if (ticket != null && ticket.requestId != activeRequestId) return
        val cur = _state.value
        if (cur.phase != PrepPhase.Analyzing) return
        activeRequestId = null
        _state.value = cur.copy(phase = PrepPhase.Editing)
    }

    /** 사용자가 분석 결과를 확인함. 확인 대기 상태에서만 준비 완료로 이동한다. */
    @Synchronized
    fun confirmReady(): Boolean {
        val cur = _state.value
        if (cur.phase != PrepPhase.Reviewing || cur.analysis == null) return false
        _state.value = cur.copy(phase = PrepPhase.Ready)
        return true
    }
}

/** 앱 전체에서 같은 준비 상태를 보도록 하는 보관소. 발표 시작 화면도 여기서 Ready 여부를 읽는다. */
object ScriptPrepRepository {
    val controller = ScriptPrepController()
}
