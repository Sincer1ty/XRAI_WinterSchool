package com.rokid.renewcxrlsample.script

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 폰에서 고른 txt·md 파일을 읽는다. */
object ScriptFileReader {

    const val MAX_BYTES = 512 * 1024
    private val ALLOWED_EXTENSIONS = setOf("txt", "md", "markdown")

    /** 파일 선택기에 넘길 MIME. md는 기기마다 octet-stream으로 보고되기도 해서 확장자로 다시 거른다. */
    val PICKER_MIME_TYPES = arrayOf("text/plain", "text/markdown", "text/x-markdown", "application/octet-stream")

    enum class Error { UnsupportedType, TooLarge, Empty, Unreadable }

    sealed interface Result {
        data class Success(val text: String, val name: String) : Result
        data class Failure(val error: Error) : Result
    }

    fun read(resolver: ContentResolver, uri: Uri): Result {
        val name = displayName(resolver, uri) ?: uri.lastPathSegment ?: "script"
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in ALLOWED_EXTENSIONS) return Result.Failure(Error.UnsupportedType)

        val bytes = try {
            resolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(MAX_BYTES + 1)
                var total = 0
                while (total < buf.size) {
                    val n = input.read(buf, total, buf.size - total)
                    if (n < 0) break
                    total += n
                }
                if (total > MAX_BYTES) return Result.Failure(Error.TooLarge)
                buf.copyOf(total)
            } ?: return Result.Failure(Error.Unreadable)
        } catch (e: IOException) {
            return Result.Failure(Error.Unreadable)
        } catch (e: SecurityException) {
            return Result.Failure(Error.Unreadable)
        }

        val text = decode(bytes) ?: return Result.Failure(Error.Unreadable)
        if (text.isBlank()) return Result.Failure(Error.Empty)
        return Result.Success(text.replace("\r\n", "\n"), name)
    }

    /** UTF-8(BOM 포함)을 먼저 시도하고, 실패하면 Windows 메모장의 한국어 인코딩(CP949)을 시도한다. */
    internal fun decode(bytes: ByteArray): String? {
        val hasBom = bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        val body = if (hasBom) bytes.copyOfRange(3, bytes.size) else bytes
        strictDecode(body, Charsets.UTF_8)?.let { return it }
        for (name in listOf("x-windows-949", "MS949", "EUC-KR")) {
            val cs = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            strictDecode(body, cs)?.let { return it }
        }
        return null
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}
