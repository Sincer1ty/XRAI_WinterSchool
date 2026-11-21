package com.rokid.renewcxrlsample.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ScriptAnalysisParserTest {

    private val sha = "a".repeat(64)

    private fun body(parts: String, revision: String = "3") =
        """{"script_sha256":"$sha","client_revision":$revision,"analyzer":"mock","parts":$parts}"""

    @Test
    fun parsesValidResponse() {
        val a = ScriptAnalysisParser.parseSuccess(
            body("""[{"index":1,"title":"소개","keywords":["발표"," ",""],"hint":"안녕하세요"}]""")
        )
        assertEquals(sha, a.scriptSha256)
        assertEquals(3L, a.clientRevision)
        assertEquals(listOf("발표"), a.parts.single().keywords)
        assertEquals("안녕하세요", a.parts.single().hint)
    }

    @Test
    fun nullRevisionAllowedByParser() {
        assertNull(ScriptAnalysisParser.parseSuccess(body("""[{"title":"a","keywords":["k"],"hint":"h"}]""", "null")).clientRevision)
    }

    @Test
    fun rejectsInvalidShapes() {
        val bad = listOf(
            "not json",
            "[]",
            """{"parts":[{"title":"a","keywords":["k"],"hint":"h"}]}""",
            body("[]"),
            body("""[{"title":"","keywords":["k"],"hint":"h"}]"""),
            body("""[{"title":"a","keywords":[],"hint":"h"}]"""),
            body("""[{"title":"a","keywords":["  "],"hint":"h"}]"""),
            body("""[{"title":"a","keywords":"k","hint":"h"}]"""),
            body("""[{"title":"a","keywords":[1],"hint":"h"}]"""),
            body("""[{"title":"a","keywords":["k"]}]"""),
            body("""[{"title":"a","keywords":["k"],"hint":"h"}]""", "\"x\""),
        )
        bad.forEach { input ->
            try {
                ScriptAnalysisParser.parseSuccess(input)
                fail("should reject: $input")
            } catch (expected: ScriptFormatException) {
            }
        }
    }

    @Test
    fun parsesErrorBody() {
        val f = ScriptAnalysisParser.parseError(400, """{"error":{"code":"empty_script","message":"대본이 비어 있습니다."}}""")
        assertEquals(PrepFailure.Server("empty_script", "대본이 비어 있습니다."), f)
    }

    @Test
    fun serverFormatErrorMapsToInvalidFormat() {
        val f = ScriptAnalysisParser.parseError(502, """{"error":{"code":"analysis_invalid_format","message":"x"}}""")
        assertTrue(f is PrepFailure.InvalidFormat)
    }

    @Test
    fun unknownErrorBodyFallsBackToStatus() {
        assertEquals(PrepFailure.Server("http_500", null), ScriptAnalysisParser.parseError(500, "<html>"))
        assertEquals(PrepFailure.Server("http_404", null), ScriptAnalysisParser.parseError(404, null))
    }

    @Test
    fun normalizesBaseUrl() {
        assertEquals("http://192.168.0.10:8000", ScriptAnalysisClient.normalizeBaseUrl(" 192.168.0.10:8000/ "))
        assertEquals("https://x.dev", ScriptAnalysisClient.normalizeBaseUrl("https://x.dev"))
    }

    @Test
    fun decodesUtf8BomAndCp949() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "안녕".toByteArray(Charsets.UTF_8)
        assertEquals("안녕", ScriptFileReader.decode(bom))
        assertEquals("안녕", ScriptFileReader.decode("안녕".toByteArray(charset("MS949"))))
    }
}
