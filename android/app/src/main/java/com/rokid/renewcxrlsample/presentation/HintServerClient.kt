package com.rokid.renewcxrlsample.presentation

import com.rokid.renewcxrlsample.script.PrepFailure
import com.rokid.renewcxrlsample.script.RequestTiming
import com.rokid.renewcxrlsample.script.ScriptAnalysisClient
import com.rokid.renewcxrlsample.script.ScriptAnalysisParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL

/** 발표 중 PC 서버 호출: 멈춤 힌트(`/hints/pause`)와 전달 판단(`/hints/delivery`). */
class HintServerClient(
    private val connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 5_000,
    /** 응답을 받은 요청의 시간 분리 기록. IO 스레드에서 호출된다. */
    private val onTiming: (RequestTiming) -> Unit = {},
) {

    init {
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be positive" }
        require(readTimeoutMs > 0) { "readTimeoutMs must be positive; zero disables the timeout" }
    }

    /** Empty-text matching checks both reachability and the prepared script without selecting a hint. */
    suspend fun checkPrepared(serverUrl: String, scriptSha256: String): PrepFailure? {
        val body = JSONObject().put("script_sha256", scriptSha256).put("text", "")
        return when (val result = post(serverUrl, MATCH_PATH, body)) {
            is Http.Ok -> parsePrepared(scriptSha256, result.body)
            is Http.Fail -> result.failure
        }
    }

    suspend fun pause(serverUrl: String, request: PauseRequest): PauseOutcome {
        val body = JSONObject().put("script_sha256", request.scriptSha256).put("text", request.text)
        request.lastPartIndex?.let { body.put("last_part_index", it) }
        return when (val r = post(serverUrl, PAUSE_PATH, body)) {
            is Http.Ok -> parsePause(request.scriptSha256, r.body)
            is Http.Fail -> PauseOutcome.Failure(r.failure)
        }
    }

    suspend fun delivery(serverUrl: String, request: DeliveryRequest): DeliveryOutcome {
        val body = JSONObject()
            .put("script_sha256", request.scriptSha256)
            .put("hint", request.hint.text)
            .put("hint_keywords", JSONArray(request.hint.keywords))
            .put("text", request.text)
        return when (val r = post(serverUrl, DELIVERY_PATH, body)) {
            is Http.Ok -> parseDelivery(request.scriptSha256, r.body)
            is Http.Fail -> DeliveryOutcome.Failure(r.failure)
        }
    }

    private sealed interface Http {
        data class Ok(val body: String) : Http
        data class Fail(val failure: PrepFailure) : Http
    }

    private suspend fun post(serverUrl: String, path: String, json: JSONObject): Http = withContext(Dispatchers.IO) {
        val url = try {
            URL(ScriptAnalysisClient.normalizeBaseUrl(serverUrl) + path)
        } catch (e: MalformedURLException) {
            return@withContext Http.Fail(PrepFailure.InvalidServerUrl)
        } catch (e: IllegalArgumentException) {
            return@withContext Http.Fail(PrepFailure.InvalidServerUrl)
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val conn = try {
            url.openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return@withContext Http.Fail(PrepFailure.Network)
        }
        val startedNs = System.nanoTime()
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            onTiming(RequestTiming.of(path, status, ScriptAnalysisClient.elapsedMs(startedNs), conn.getHeaderField(RequestTiming.HEADER)))
            if (status == 200) Http.Ok(text) else Http.Fail(ScriptAnalysisParser.parseError(status, text))
        } catch (e: SocketTimeoutException) {
            Http.Fail(PrepFailure.Timeout)
        } catch (e: IOException) {
            Http.Fail(PrepFailure.Network)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val MATCH_PATH = "/api/v1/parts/match"

        internal fun parsePrepared(expectedSha: String, body: String): PrepFailure? = try {
            val root = JSONObject(body)
            if (root.opt("script_sha256") == expectedSha &&
                root.opt("status") == "unknown" && root.opt("stt_text") == "") null
            else PrepFailure.InvalidFormat("invalid readiness response")
        } catch (e: JSONException) {
            PrepFailure.InvalidFormat("readiness response is not JSON")
        }

        const val PAUSE_PATH = "/api/v1/hints/pause"
        const val DELIVERY_PATH = "/api/v1/hints/delivery"

        /** 멈춤 응답 해석. 다른 대본의 응답이거나 형식이 틀리면 실패로 본다. */
        internal fun parsePause(expectedSha: String, body: String): PauseOutcome = try {
            val root = JSONObject(body)
            if (root.optString("script_sha256") != expectedSha) {
                PauseOutcome.Failure(PrepFailure.InvalidFormat("script hash mismatch"))
            } else {
                val hint = (root.opt("hint") as? String)?.trim().orEmpty()
                if (hint.isEmpty()) {
                    PauseOutcome.NoHint(root.optString("status", "unknown"))
                } else {
                    val keywords = root.optJSONArray("hint_keywords")?.let { arr ->
                        (0 until arr.length()).mapNotNull { (arr.opt(it) as? String)?.trim()?.takeIf(String::isNotEmpty) }
                    }.orEmpty()
                    val part = if (root.isNull("hint_part_index")) null else root.optInt("hint_part_index")
                    PauseOutcome.Hint(PauseHint(hint, keywords, part))
                }
            }
        } catch (e: JSONException) {
            PauseOutcome.Failure(PrepFailure.InvalidFormat("pause response is not JSON"))
        }

        internal fun parseDelivery(expectedSha: String, body: String): DeliveryOutcome = try {
            val root = JSONObject(body)
            val delivered = root.opt("delivered")
            when {
                root.optString("script_sha256") != expectedSha ->
                    DeliveryOutcome.Failure(PrepFailure.InvalidFormat("script hash mismatch"))
                delivered == true -> DeliveryOutcome.Delivered
                delivered == false -> DeliveryOutcome.NotDelivered
                // 판단 결과가 없으면 전달로 보지 않는다.
                else -> DeliveryOutcome.Failure(PrepFailure.InvalidFormat("delivered missing"))
            }
        } catch (e: JSONException) {
            DeliveryOutcome.Failure(PrepFailure.InvalidFormat("delivery response is not JSON"))
        }
    }
}
