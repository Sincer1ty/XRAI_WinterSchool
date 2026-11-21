package com.rokid.renewcxrlsample.script

import java.security.MessageDigest

/** 서버가 나눈 대본의 한 파트. */
data class ScriptPart(
    val index: Int,
    val title: String,
    val keywords: List<String>,
    val hint: String,
)

/** `POST /api/v1/scripts/analyze` 성공 응답. */
data class ScriptAnalysis(
    val scriptSha256: String,
    val clientRevision: Long?,
    val analyzer: String,
    val parts: List<ScriptPart>,
)

/** 준비 실패 원인. 화면에서 안내 문구로 바뀐다. */
sealed interface PrepFailure {
    data object InvalidServerUrl : PrepFailure
    data object Network : PrepFailure
    data object Timeout : PrepFailure
    /** 응답 형식 오류 (JSON 해석 실패, 필수 값 누락, 다른 대본의 결과 등). */
    data class InvalidFormat(val detail: String) : PrepFailure
    /** 서버가 오류 응답을 돌려줌. */
    data class Server(val code: String, val message: String?) : PrepFailure
}

sealed interface AnalysisOutcome {
    data class Success(val analysis: ScriptAnalysis) : AnalysisOutcome
    data class Failure(val failure: PrepFailure) : AnalysisOutcome
}

internal fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
