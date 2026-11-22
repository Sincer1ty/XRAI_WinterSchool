package com.rokid.renewcxrlsample.script

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class ScriptFormatException(message: String) : Exception(message)

/** 서버 응답 JSON을 검증해 모델로 바꾼다. 필수 값이 없거나 형식이 다르면 ScriptFormatException. */
object ScriptAnalysisParser {

    private val SHA256_RE = Regex("^[0-9a-fA-F]{64}$")

    fun parseSuccess(body: String): ScriptAnalysis {
        val root = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw ScriptFormatException("response is not a JSON object")
        }

        val sha = root.optString("script_sha256", "")
        if (!SHA256_RE.matches(sha)) throw ScriptFormatException("script_sha256 missing")

        val revision = if (root.has("client_revision") && !root.isNull("client_revision")) {
            val raw = root.get("client_revision")
            (raw as? Number)?.toLong() ?: throw ScriptFormatException("client_revision invalid")
        } else {
            null
        }

        val partsJson = root.optJSONArray("parts") ?: throw ScriptFormatException("parts missing")
        if (partsJson.length() == 0) throw ScriptFormatException("parts empty")

        val parts = (0 until partsJson.length()).map { i ->
            val p = partsJson.optJSONObject(i) ?: throw ScriptFormatException("part $i invalid")
            val title = p.optStringStrict("title") ?: throw ScriptFormatException("part $i title missing")
            val hint = p.optStringStrict("hint") ?: throw ScriptFormatException("part $i hint missing")
            val body = if (!p.has("body")) "" else {
                p.opt("body") as? String ?: throw ScriptFormatException("part $i body invalid")
            }
            val keywords = p.optJSONArray("keywords")?.stringsOrNull()
                ?: throw ScriptFormatException("part $i keywords invalid")
            if (keywords.isEmpty()) throw ScriptFormatException("part $i keywords empty")
            ScriptPart(
                index = p.optInt("index", i + 1),
                title = title,
                keywords = keywords,
                hint = hint,
                body = body,
            )
        }

        return ScriptAnalysis(
            scriptSha256 = sha,
            clientRevision = revision,
            analyzer = root.optString("analyzer", ""),
            parts = parts,
        )
    }

    /** `{"error": {"code", "message"}}` 형식의 오류 응답. 형식이 다르면 HTTP 상태 코드로 대신한다. */
    fun parseError(httpStatus: Int, body: String?): PrepFailure {
        val error = try {
            body?.let { JSONObject(it).optJSONObject("error") }
        } catch (e: JSONException) {
            null
        }
        val code = error?.optStringStrict("code") ?: "http_$httpStatus"
        val message = error?.optStringStrict("message")
        return if (code == "analysis_invalid_format") {
            PrepFailure.InvalidFormat(message ?: code)
        } else {
            PrepFailure.Server(code, message)
        }
    }

    private fun JSONObject.optStringStrict(key: String): String? {
        val v = opt(key) as? String ?: return null
        return v.trim().takeIf { it.isNotEmpty() }
    }

    private fun JSONArray.stringsOrNull(): List<String>? {
        val out = mutableListOf<String>()
        for (i in 0 until length()) {
            val s = opt(i) as? String ?: return null
            s.trim().takeIf { it.isNotEmpty() }?.let(out::add)
        }
        return out
    }
}
