package com.rokid.renewcxrlsample.diagnostics

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.SystemClock
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.IAudioStreamCbk
import com.rokid.renewcxrlsample.app.CXRLApplication
import com.rokid.renewcxrlsample.link.CxrLinkConnectionHub
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import com.rokid.sprite.aiapp.externalapp.auth.GlassPermission
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class GlassDiagnosticsState(
    val connected: Boolean = false,
    val receiving: Boolean = false,
    val captureStatus: String = "음성 수신 시작 전",
    val pcm: PcmProbeSnapshot = PcmProbeSnapshot(),
    val inputAgeMs: Long? = null,
    val pipeBytes: Long = 0,
    val speech: SpeechProbeState = SpeechProbeState(),
    val hintStatus: String = "안경에서 표시·숨김을 직접 확인하세요.",
    val deviceRecognizerAvailable: Boolean = false,
)

class GlassDiagnosticsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CXRLApplication
    private val _state = MutableStateFlow(GlassDiagnosticsState())
    val state = _state.asStateFlow()
    private val meter = Pcm16Probe()
    private val accepting = AtomicBoolean(false)
    private var cxrLink: CXRLink? = null
    private var closed = false
    private val speech = PcmSpeechProbe(
        application,
        viewModelScope,
        onState = { _state.value = _state.value.copy(speech = it) },
    )

    init {
        _state.value = _state.value.copy(
            deviceRecognizerAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(application)
        )
        viewModelScope.launch {
            combine(CxrLinkConnectionHub.cxrlConnected, CxrLinkConnectionHub.btConnected) { service, bt ->
                service && bt
            }.collect { connected ->
                _state.value = _state.value.copy(connected = connected)
                if (!connected && accepting.get()) {
                    stopCapture()
                    _state.value = _state.value.copy(captureStatus = "연결 끊김 · 재연결 후 수신을 다시 시작하세요.")
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(250)
                val snapshot = meter.snapshot()
                _state.value = _state.value.copy(
                    pcm = snapshot,
                    inputAgeMs = snapshot.lastChunkAtMs?.let { SystemClock.elapsedRealtime() - it },
                    pipeBytes = speech.writtenBytes.get(),
                )
            }
        }
    }

    private val audioCallback = object : IAudioStreamCbk {
        override fun onAudioReceived(data: ByteArray?, offset: Int, length: Int) {
            if (!accepting.get()) return
            val pcm = meter.accept(data, offset, length, SystemClock.elapsedRealtime()) ?: return
            speech.feed(pcm)
        }

        override fun onAudioError(errorCode: Int, errorInfo: String?) {
            viewModelScope.launch {
                if (closed) return@launch
                stopCapture()
                _state.value = _state.value.copy(
                    captureStatus = "안경 음성 오류 " + errorCode + ": " + errorInfo.orEmpty()
                )
            }
        }

        override fun onAudioStreamStateChanged(started: Boolean) {
            viewModelScope.launch {
                if (closed) return@launch
                if (started && accepting.get()) {
                    _state.value = _state.value.copy(captureStatus = "SDK 수신 시작 응답 · 아래 수신량도 확인하세요.")
                } else if (!started && accepting.get()) {
                    stopCapture()
                    _state.value = _state.value.copy(captureStatus = "SDK가 음성 수신 종료를 알렸습니다.")
                }
            }
        }
    }

    fun startCapture() {
        if (closed || accepting.get()) return
        val link = app.sharedLink
        if (!_state.value.connected || !app.isSessionReady || link == null) {
            captureMessage("안경 서비스·블루투스 연결을 먼저 확인하세요.")
            return
        }
        if (!AuthorizationHelper.hasGlassPermission(GlassPermission.MICROPHONE)) {
            captureMessage("안경 마이크 권한이 없습니다. 연결 승인 화면에서 확인하세요.")
            return
        }
        try {
            if (!link.customViewIsOpen()) {
                captureMessage("안경 화면 모드에서 안경 화면을 연 뒤 진단 화면에 들어오세요.")
                return
            }
            speech.cancel()
            speech.writtenBytes.set(0)
            meter.reset()
            cxrLink = link
            link.setCXRAudioCbk(audioCallback)
            accepting.set(true)
            _state.value = _state.value.copy(receiving = true, pcm = meter.snapshot(), inputAgeMs = null)
            val result: Any? = link.startAudioStream(1)
            if (result == false) {
                stopCapture()
                captureMessage("SDK가 음성 시작 요청을 거부했습니다.")
            } else {
                captureMessage("수신 요청 전송됨 · 청크·바이트 증가를 확인하세요.")
            }
        } catch (error: Exception) {
            stopCapture()
            captureMessage("음성 시작 실패: " + error.message.orEmpty())
        }
    }

    fun stopCapture() {
        accepting.set(false)
        runCatching { cxrLink?.stopAudioStream() }
        speech.finishInput()
        _state.value = _state.value.copy(receiving = false, captureStatus = "음성 수신 중지")
    }

    fun startSpeech(onDevice: Boolean) {
        if (!accepting.get() || meter.snapshot().bytes == 0L) {
            captureMessage("먼저 안경 음성 수신을 시작하고 바이트가 증가하는지 확인하세요.")
            return
        }
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            captureMessage("STT를 사용하려면 휴대폰 마이크 권한을 허용하세요.")
            return
        }
        speech.start(onDevice)
    }

    fun finishSpeech() = speech.finishInput()
    fun cancelSpeech() = speech.cancel()

    fun setHint(hidden: Boolean) {
        if (!_state.value.connected || !app.isSessionReady) {
            _state.value = _state.value.copy(hintStatus = "안경 연결을 확인하세요.")
            return
        }
        val link = app.sharedLink ?: return
        try {
            if (!link.customViewIsOpen()) {
                _state.value = _state.value.copy(hintStatus = "안경 화면이 닫혀 있습니다.")
                return
            }
            val changes = JSONArray()
            changes.put(JSONObject().put("action", "update").put("id", "textView")
                .put("props", JSONObject().put("text", if (hidden) "" else "안경 음성 진단 중입니다")))
            val result: Any? = link.customViewUpdate(changes.toString())
            if (result == false) {
                _state.value = _state.value.copy(hintStatus = "SDK가 표시 변경 요청을 거부했습니다.")
                return
            }
            if (hidden) meter.markHidden()
            _state.value = _state.value.copy(
                hintStatus = if (hidden) "숨김 요청 전송됨 · 안경 화면과 숨김 후 수신량을 확인하세요."
                    else "문구 표시 요청 전송됨 · 안경에서 확인하세요."
            )
        } catch (error: Exception) {
            _state.value = _state.value.copy(hintStatus = "화면 요청 오류: " + error.message.orEmpty())
        }
    }

    fun close() {
        if (closed) return
        closed = true
        stopCapture()
        speech.cancel()
        // Do not replace callbacks belonging to a newer connection.
        if (app.sharedLink === cxrLink) {
            runCatching {
                cxrLink?.setCXRAudioCbk(object : IAudioStreamCbk {
                    override fun onAudioReceived(data: ByteArray?, offset: Int, length: Int) {}
                    override fun onAudioError(errorCode: Int, errorInfo: String?) {}
                    override fun onAudioStreamStateChanged(started: Boolean) {}
                })
            }
        }
        cxrLink = null
    }

    private fun captureMessage(message: String) {
        _state.value = _state.value.copy(captureStatus = message)
    }

    override fun onCleared() {
        close()
        super.onCleared()
    }
}