package com.rokid.renewcxrlsample.presentation

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.IAudioStreamCbk
import com.rokid.renewcxrlsample.app.CXRLApplication
import com.rokid.renewcxrlsample.diagnostics.Pcm16Probe
import com.rokid.renewcxrlsample.diagnostics.PcmSpeechProbe
import com.rokid.renewcxrlsample.diagnostics.SpeechProbeState
import com.rokid.renewcxrlsample.link.CxrLinkConnectionHub
import com.rokid.renewcxrlsample.script.ScriptPrepRepository
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import com.rokid.sprite.aiapp.externalapp.auth.GlassPermission
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** 발표를 시작하지 못한 이유. */
enum class StartError {
    ScriptNotReady, NoServer, NotConnected, NoGlassMic, GlassViewClosed, NoPhoneMic, AudioRejected, AudioFailed,
}

data class PresentationUiState(
    val connected: Boolean = false,
    val receiving: Boolean = false,
    val voice: VoiceStatus = VoiceStatus.Waiting,
    val rms: Double = 0.0,
    val peakRms: Double = 0.0,
    val silentMs: Long = 0,
    val speechRms: Double = SilenceDetector.DEFAULT_SPEECH_RMS,
    val speech: SpeechProbeState = SpeechProbeState(),
    val startError: StartError? = null,
    val startErrorDetail: String? = null,
    /** 안경 키워드가 사라지기까지 남은 시간. 표시 중이 아니면 null. */
    val hintRemainingMs: Long? = null,
)

/**
 * 발표 진행: 안경 음성 수신 → 폰 STT·멈춤 감지 → 서버 키워드 → 안경 표시 → 전달 판단 또는 5초 경과 → 숨김.
 *
 * Activity가 아니라 ViewModel이 음성 수신을 가지고 있어 화면 회전·다른 앱 전환에도 수신이 이어진다.
 * 발표 화면을 닫으면(onCleared) 모두 멈춘다.
 */
class PresentationViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CXRLApplication
    val controller = PresentationController()
    val presentation: StateFlow<PresentationState> = controller.state

    private val _ui = MutableStateFlow(PresentationUiState())
    val ui: StateFlow<PresentationUiState> = _ui.asStateFlow()

    private val meter = Pcm16Probe()
    private val detector = SilenceDetector()
    private val client = HintServerClient()
    private val renderer = GlassHintRenderer { app.sharedLink }
    private val accepting = AtomicBoolean(false)
    private var audioLink: CXRLink? = null
    private var serverUrl = ""
    /** 멈춤은 감지됐지만 보낼 인식 문장이 아직 없음. 다시 말하기 시작하면 해제. 메인 스레드 전용. */
    private var pauseWaitingForText = false

    private val speech = PcmSpeechProbe(
        application,
        viewModelScope,
        onState = { s -> _ui.value = _ui.value.copy(speech = s) },
        onFinal = { text ->
            controller.onFinal(text)?.let(::requestDelivery)
            // 멈춤이 먼저 감지되고 확정 문장이 늦게 온 경우, 그 문장으로 힌트를 요청한다.
            if (pauseWaitingForText) {
                pauseWaitingForText = false
                controller.onPause()?.let(::requestPause)
            }
        },
        autoRestart = true,
        onStopped = { controller.halt(HaltReason.SttStopped) },
    )

    private val audioCallback = object : IAudioStreamCbk {
        override fun onAudioReceived(data: ByteArray?, offset: Int, length: Int) {
            if (!accepting.get()) return
            val pcm = meter.accept(data, offset, length, SystemClock.elapsedRealtime()) ?: return
            detector.onChunk(meter.snapshot().rms, SystemClock.elapsedRealtime())
            speech.feed(pcm)
        }

        override fun onAudioError(errorCode: Int, errorInfo: String?) {
            viewModelScope.launch { if (accepting.get()) controller.halt(HaltReason.AudioStopped) }
        }

        override fun onAudioStreamStateChanged(started: Boolean) {
            viewModelScope.launch { if (!started && accepting.get()) controller.halt(HaltReason.AudioStopped) }
        }
    }

    init {
        viewModelScope.launch {
            combine(CxrLinkConnectionHub.cxrlConnected, CxrLinkConnectionHub.btConnected) { a, b -> a && b }
                .collect { connected ->
                    _ui.value = _ui.value.copy(connected = connected)
                    if (!connected) controller.halt(HaltReason.ConnectionLost)
                }
        }
        // 오류로 자동 판단이 멈추면 음성 처리도 멈춘다.
        viewModelScope.launch {
            controller.state.collect { if (it.phase == PresentationPhase.Halted) stopMedia() }
        }
        viewModelScope.launch {
            while (true) {
                delay(TICK_MS)
                val now = SystemClock.elapsedRealtime()
                if (accepting.get() && detector.tick(now)) {
                    val request = controller.onPause()
                    if (request != null) requestPause(request)
                    else pauseWaitingForText = presentation.value.let { it.hint == null && !it.pauseInFlight }
                }
                if (detector.status(now) == VoiceStatus.Speaking) pauseWaitingForText = false
                // 5초가 지난 키워드는 전달 판단과 관계없이 내린다.
                if (controller.expireIfDue(now) && !renderer.hide()) controller.onHideFailed()
                val m = meter.snapshot()
                _ui.value = _ui.value.copy(
                    receiving = accepting.get(),
                    voice = if (accepting.get()) detector.status(now) else VoiceStatus.Waiting,
                    rms = m.rms,
                    peakRms = m.peakRms,
                    silentMs = detector.silentForMs(now),
                    hintRemainingMs = controller.remainingMs(now),
                )
            }
        }
    }

    fun setSpeechRms(value: Double) {
        detector.speechRms = value
        _ui.value = _ui.value.copy(speechRms = value)
    }

    /** 발표 시작. 휴대폰 마이크 권한(STT 인식기 요구)은 Activity가 먼저 받는다. */
    fun start(onDevice: Boolean) {
        if (presentation.value.phase == PresentationPhase.Running) return
        val prep = ScriptPrepRepository.controller.state.value
        val analysis = prep.analysis
        val link = app.sharedLink
        val error = when {
            !prep.isReady || analysis == null -> StartError.ScriptNotReady
            prep.analyzedServerUrl.isNullOrBlank() -> StartError.NoServer
            !_ui.value.connected || !app.isSessionReady || link == null -> StartError.NotConnected
            !AuthorizationHelper.hasGlassPermission(GlassPermission.MICROPHONE) -> StartError.NoGlassMic
            runCatching { link?.customViewIsOpen() != true }.getOrDefault(true) -> StartError.GlassViewClosed
            ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED -> StartError.NoPhoneMic
            else -> null
        }
        if (error != null || analysis == null || link == null) {
            _ui.value = _ui.value.copy(startError = error, startErrorDetail = null)
            return
        }
        serverUrl = prep.analyzedServerUrl.orEmpty()
        _ui.value = _ui.value.copy(startError = null, startErrorDetail = null)

        meter.reset()
        detector.reset()
        controller.start(analysis.scriptSha256)
        renderer.hide() // 연결 확인용 문구를 지우고 빈 화면으로 시작한다.
        try {
            audioLink = link
            link.setCXRAudioCbk(audioCallback)
            accepting.set(true)
            val result: Any? = link.startAudioStream(AUDIO_CODEC_PCM)
            if (result == false) {
                failStart(StartError.AudioRejected, null)
                return
            }
        } catch (e: Exception) {
            failStart(StartError.AudioFailed, e.message)
            return
        }
        speech.start(onDevice)
    }

    fun stop() {
        val hadHint = controller.stop()
        stopMedia()
        if (hadHint) renderer.hide()
    }

    private fun failStart(error: StartError, detail: String?) {
        controller.stop()
        stopMedia()
        _ui.value = _ui.value.copy(startError = error, startErrorDetail = detail)
    }

    private fun stopMedia() {
        if (accepting.getAndSet(false)) runCatching { audioLink?.stopAudioStream() }
        // 이미 멈춘 인식기를 다시 취소하면 오류 안내가 "STT 중지"로 덮인다.
        if (_ui.value.speech.running) speech.cancel()
    }

    private fun requestPause(request: PauseRequest) {
        viewModelScope.launch {
            val outcome = client.pause(serverUrl, request)
            val shown = controller.completePause(request, outcome) ?: return@launch
            val sent = renderer.show(keywordLine(shown.hint.keywords))
            controller.onHintRendered(shown.id, sent, SystemClock.elapsedRealtime())
        }
    }

    private fun requestDelivery(request: DeliveryRequest) {
        viewModelScope.launch {
            val outcome = client.delivery(serverUrl, request)
            if (controller.completeDelivery(request, outcome) && !renderer.hide()) controller.onHideFailed()
        }
    }

    override fun onCleared() {
        stop()
        // 더 새로운 연결의 콜백은 덮어쓰지 않는다.
        val link = audioLink
        if (link != null && app.sharedLink === link) {
            runCatching {
                link.setCXRAudioCbk(object : IAudioStreamCbk {
                    override fun onAudioReceived(data: ByteArray?, offset: Int, length: Int) {}
                    override fun onAudioError(errorCode: Int, errorInfo: String?) {}
                    override fun onAudioStreamStateChanged(started: Boolean) {}
                })
            }
        }
        audioLink = null
        super.onCleared()
    }

    private companion object {
        const val TICK_MS = 200L
        /** 기존 샘플·진단과 같은 값. 16 kHz·모노·16비트 PCM으로 가정 (실기기 확인 필요). */
        const val AUDIO_CODEC_PCM = 1
    }
}
