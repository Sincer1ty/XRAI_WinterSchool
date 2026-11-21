package com.rokid.renewcxrlsample.presentation

import com.rokid.cxr.link.CXRLink
import org.json.JSONArray
import org.json.JSONObject

/**
 * 안경 화면의 한 줄 문구(`textView`)로 힌트를 표시·숨긴다. 숨김은 빈 문구로 바꿔 화면·음성 수신을 유지한다.
 *
 * 반환값은 "요청을 보냈는지"뿐이다. 안경에 실제로 보였는지는 사용자가 확인한다.
 */
class GlassHintRenderer(private val linkProvider: () -> CXRLink?) {

    fun show(text: String): Boolean = send(text)

    fun hide(): Boolean = send("")

    private fun send(text: String): Boolean {
        val link = linkProvider() ?: return false
        return runCatching {
            if (!link.customViewIsOpen()) return false
            val result: Any? = link.customViewUpdate(buildUpdate(text))
            result != false
        }.getOrDefault(false)
    }

    companion object {
        /** SessionHubViewModel이 연 템플릿의 문구 노드 id. */
        const val TEXT_VIEW_ID = "textView"

        /** JSONObject로 만들어 따옴표·역슬래시·줄바꿈이 들어간 힌트도 깨지지 않게 한다. */
        fun buildUpdate(text: String): String = JSONArray()
            .put(
                JSONObject()
                    .put("action", "update")
                    .put("id", TEXT_VIEW_ID)
                    .put("props", JSONObject().put("text", text))
            )
            .toString()
    }
}
