package com.rokid.renewcxrlsample.presentation

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationJsonTest {

    @Test
    fun glassUpdateEscapesQuotesBackslashAndNewline() {
        val text = "그는 \"안녕\"이라고\\ 말했다\n다음"
        val arr = JSONArray(GlassHintRenderer.buildUpdate(text))
        val item = arr.getJSONObject(0)
        assertEquals("update", item.getString("action"))
        assertEquals("textView", item.getString("id"))
        assertEquals(text, item.getJSONObject("props").getString("text"))
    }

    @Test
    fun pauseResponseWithHint() {
        val body = """{"script_sha256":"s","status":"matched","hint":"그래서 저희는",
            "hint_keywords":["스마트 안경"," "],"hint_part_index":2}"""
        assertEquals(
            PauseOutcome.Hint(PauseHint("그래서 저희는", listOf("스마트 안경"), 2)),
            HintServerClient.parsePause("s", body),
        )
    }

    @Test
    fun pauseResponseWithoutHintOrForOtherScript() {
        assertEquals(
            PauseOutcome.NoHint("ambiguous"),
            HintServerClient.parsePause("s", """{"script_sha256":"s","status":"ambiguous","hint":null}"""),
        )
        assertTrue(HintServerClient.parsePause("s", """{"script_sha256":"x","hint":"a"}""") is PauseOutcome.Failure)
        assertTrue(HintServerClient.parsePause("s", "not json") is PauseOutcome.Failure)
    }

    @Test
    fun deliveryResponse() {
        assertEquals(
            DeliveryOutcome.Delivered,
            HintServerClient.parseDelivery("s", """{"script_sha256":"s","delivered":true}"""),
        )
        assertEquals(
            DeliveryOutcome.NotDelivered,
            HintServerClient.parseDelivery("s", """{"script_sha256":"s","delivered":false}"""),
        )
        assertTrue(
            "판단 값이 없으면 전달로 보지 않음",
            HintServerClient.parseDelivery("s", """{"script_sha256":"s"}""") is DeliveryOutcome.Failure,
        )
    }
}
