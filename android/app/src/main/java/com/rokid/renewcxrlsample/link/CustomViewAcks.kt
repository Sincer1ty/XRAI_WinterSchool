package com.rokid.renewcxrlsample.link

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 안경 화면(CustomView) 갱신 응답. SessionHubViewModel의 ICustomViewCbk가 전달한다.
 *
 * 발표 화면은 표시 요청 전 [seq]를 기억했다가 더 큰 [seq]가 오면 안경이 응답한 것으로 본다.
 * SDK 응답에 요청 id가 없어 직전 다른 요청(숨김 등)의 늦은 응답과 구분하지 못한다.
 */
object CustomViewAcks {
    /** [error]가 null이면 onCustomViewUpdated, 아니면 onCustomViewError. */
    data class Ack(val seq: Long, val error: String? = null)

    private val _last = MutableStateFlow(Ack(0))
    val last: StateFlow<Ack> = _last.asStateFlow()

    fun onUpdated() = _last.update { Ack(it.seq + 1) }

    fun onError(code: Int, message: String?) =
        _last.update { Ack(it.seq + 1, listOfNotNull(code.toString(), message).joinToString(" ")) }
}
