package com.rokid.renewcxrlsample.script

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL

/** PC 서버의 대본 분석 API 호출. */
class ScriptAnalysisClient(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 60_000,
    /** 응답을 받은 요청의 시간 분리 기록. IO 스레드에서 호출된다. */
    private val onTiming: (RequestTiming) -> Unit = {},
) {

    init {
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be positive" }
        require(readTimeoutMs > 0) { "readTimeoutMs must be positive; zero disables the timeout" }
    }

    suspend fun analyze(serverUrl: String, script: String, revision: Long): AnalysisOutcome =
        withContext(Dispatchers.IO) {
            val url = try {
                URL(normalizeBaseUrl(serverUrl) + ANALYZE_PATH)
            } catch (e: MalformedURLException) {
                return@withContext AnalysisOutcome.Failure(PrepFailure.InvalidServerUrl)
            } catch (e: IllegalArgumentException) {
                return@withContext AnalysisOutcome.Failure(PrepFailure.InvalidServerUrl)
            }

            val body = JSONObject()
                .put("script", script)
                .put("client_revision", revision)
                .toString()
                .toByteArray(Charsets.UTF_8)

            val conn = try {
                url.openConnection() as HttpURLConnection
            } catch (e: IOException) {
                return@withContext AnalysisOutcome.Failure(PrepFailure.Network)
            }
            val startedNs = System.nanoTime()
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Accept", "application/json")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }

                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                onTiming(RequestTiming.of(ANALYZE_PATH, status, elapsedMs(startedNs), conn.getHeaderField(RequestTiming.HEADER)))

                if (status != 200) {
                    AnalysisOutcome.Failure(ScriptAnalysisParser.parseError(status, text))
                } else {
                    try {
                        AnalysisOutcome.Success(ScriptAnalysisParser.parseSuccess(text.orEmpty()))
                    } catch (e: ScriptFormatException) {
                        AnalysisOutcome.Failure(PrepFailure.InvalidFormat(e.message ?: "invalid"))
                    }
                }
            } catch (e: SocketTimeoutException) {
                AnalysisOutcome.Failure(PrepFailure.Timeout)
            } catch (e: IOException) {
                AnalysisOutcome.Failure(PrepFailure.Network)
            } finally {
                conn.disconnect()
            }
        }

    companion object {
        const val ANALYZE_PATH = "/api/v1/scripts/analyze"

        internal fun elapsedMs(startedNs: Long): Double = (System.nanoTime() - startedNs) / 1_000_000.0

        /** "192.168.0.10:8000" → "http://192.168.0.10:8000", 끝의 "/" 제거. */
        fun normalizeBaseUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            require(trimmed.isNotEmpty()) { "empty url" }
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "http://$trimmed"
            }
        }
    }
}
