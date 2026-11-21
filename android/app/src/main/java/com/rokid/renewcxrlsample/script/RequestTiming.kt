package com.rokid.renewcxrlsample.script

/**
 * PC 서버 요청 1건의 시간 분리 기록.
 *
 * - [roundTripMs]: 폰에서 잰 요청 전송~응답 수신 전체 시간
 * - [serverMs]: 서버 자체 처리 시간 (`Server-Timing`의 app)
 * - [llmMs]: 서버가 LLM API 응답을 기다린 시간 (`Server-Timing`의 llm, 대본 분석만)
 * - [networkMs]: 왕복 시간 − 서버 전체 시간. 폰↔PC 네트워크와 연결 대기
 *
 * 서버가 헤더를 주지 않으면(이전 버전 서버 등) 서버·네트워크를 나누지 않고 왕복 시간만 남긴다.
 */
data class RequestTiming(
    val path: String,
    val status: Int,
    val roundTripMs: Double,
    val serverMs: Double?,
    val llmMs: Double?,
    val serverTotalMs: Double?,
) {
    val networkMs: Double? get() = serverTotalMs?.let { (roundTripMs - it).coerceAtLeast(0.0) }

    fun logLine(): String = buildString {
        append("$path $status total=${formatMs(roundTripMs)}")
        append(" server=${formatMs(serverMs)}")
        append(" llm=${formatMs(llmMs)}")
        append(" network=${formatMs(networkMs)}")
    }

    companion object {
        const val HEADER = "Server-Timing"

        fun of(path: String, status: Int, roundTripMs: Double, header: String?): RequestTiming {
            val parts = parseServerTiming(header)
            val server = parts["app"]
            val llm = parts["llm"]
            val total = parts["total"] ?: server?.let { it + (llm ?: 0.0) }
            return RequestTiming(path, status, roundTripMs, server, llm, total)
        }

        /** `app;dur=1.2, llm;dur=850.0, total;dur=851.2` → 이름별 ms. 형식이 틀린 항목은 버린다. */
        internal fun parseServerTiming(header: String?): Map<String, Double> {
            if (header.isNullOrBlank()) return emptyMap()
            return header.split(',').mapNotNull { item ->
                val fields = item.trim().split(';').map(String::trim)
                val name = fields.firstOrNull()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                val dur = fields.drop(1).firstOrNull { it.startsWith("dur=") }
                    ?.removePrefix("dur=")?.toDoubleOrNull() ?: return@mapNotNull null
                name to dur
            }.toMap()
        }

        /** 화면·로그용. 값이 없으면 "-". */
        fun formatMs(value: Double?): String = value?.let { "%.0fms".format(java.util.Locale.ROOT, it) } ?: "-"
    }
}
