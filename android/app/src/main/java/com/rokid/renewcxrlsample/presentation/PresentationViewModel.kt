package com.rokid.renewcxrlsample.presentation

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.IAudioStreamCbk
import com.rokid.renewcxrlsample.R
import com.rokid.renewcxrlsample.app.CXRLApplication
import com.rokid.renewcxrlsample.diagnostics.Pcm16Probe
import com.rokid.renewcxrlsample.diagnostics.PcmSpeechProbe
import com.rokid.renewcxrlsample.diagnostics.SpeechProbeState
import com.rokid.renewcxrlsample.link.CustomViewAcks
import com.rokid.renewcxrlsample.link.CxrLinkConnectionHub
import com.rokid.renewcxrlsample.script.ScriptPrepRepository
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import com.rokid.sprite.aiapp.externalapp.auth.GlassPermission
import kotlinx.coroutines.Job
import com.rokid.renewcxrlsample.script.PrepFailure
import com.rokid.renewcxrlsample.script.RequestTiming
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** 발표를 시작하지 못한 이유. */
enum class StartError {
    ScriptNotReady, NoServer, NotConnected, NoGlassMic, GlassViewClosed, NoPhoneMic, AudioRejected, AudioFailed,
    ServerUnavailable, ServerTimeout, ScriptMissing, ServerInvalid, SttFailed, AudioMissing, SttTimeout,
}

enum class StartStage { Server, AudioAndStt }
enum class GlassOpenError { Failed, TimedOut }

data class PresentationUiState(
    val connected: Boolean = false,
    val glassOpened: Boolean = false,
    val glassOpening: Boolean = false,
    val glassOpenError: GlassOpenError? = null,
    val startStage: StartStage? = null,
    val receiving: Boolean = false,
    val voice: VoiceStatus = VoiceStatus.Waiting,
    val rms: Double = 0.0,
    val peakRms: Double = 0.0,
    val silentMs: Long = 0,
    val speechRms: Double = SilenceDetector.DEFAULT_SPEECH_RMS,
    val pauseMs: Long = SilenceDetector.DEFAULT_PAUSE_MS,
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
    val prepState = ScriptPrepRepository.controller.state

    private val _ui = MutableStateFlow(PresentationUiState())
    val ui: StateFlow<PresentationUiState> = _ui.asStateFlow()

    private val meter = Pcm16Probe()
    private val detector = SilenceDetector()
    private val vad = PcmVad(detector::onVoice)
    private var hintSpeechRevisionAtShow = 0L
    private val _lastTiming = MutableStateFlow<RequestTiming?>(null)
    /** 마지막 서버 요청의 서버 처리·네트워크 시간. */
    val lastTiming: StateFlow<RequestTiming?> = _lastTiming.asStateFlow()
    private val client = HintServerClient(onTiming = { timing ->
        Log.i(TIMING_TAG, timing.logLine())
        _lastTiming.value = timing
    })
    private val renderer = GlassHintRenderer { app.sharedLink }
    private val accepting = AtomicBoolean(false)
    private var audioLink: CXRLink? = null
    private var serverUrl = ""
    /** 멈춤은 감지됐지만 보낼 인식 문장이 아직 없음. 다시 말하기 시작하면 해제. 메인 스레드 전용. */
    private var pauseWaitingForText = false
    private var waitingPauseRevision = 0L
    private var audioStartedAtMs = 0L
    private val startGate = PresentationStartGate()
    private var startJob: Job? = null
    private var startTicket = 0L
    private var preparedRevision = 0L
    private var preparedSha = ""
    private var startupDeadlineMs = 0L
    private var glassOpenDeadlineMs = 0L
    private var openingLink: CXRLink? = null
    @Volatile private var mediaGeneration = 0L

    private val speech: PcmSpeechProbe = PcmSpeechProbe(
        application,
        viewModelScope,
        onState = { s ->
            _ui.value = _ui.value.copy(speech = s)
            // 띄운 키워드가 중간 결과에 들리면 확정 문장을 기다리지 않고 바로 숨긴다.
            if (controller.onPartial(s.partial, hasSpeechAfterHint())) hideHint()
        },
        onFinal = { text ->
            val refreshPause = pauseWaitingForText || presentation.value.pauseInFlight
            controller.onFinal(text, hasSpeechAfterHint())?.let(::requestDelivery)
            // 같은 쉼에서 확정 결과가 늦게 오면 이전 요청을 버리고 최신 문장으로 다시 요청한다.
            if (refreshPause) {
                pauseWaitingForText = false
                if (detector.isCurrentPause(waitingPauseRevision, SystemClock.elapsedRealtime())) {
                    controller.onPause()?.let(::requestPause)
                }
            }
        },
        autoRestart = true,
        onStopped = { message ->
            if (startGate.preparing) failStart(StartError.SttFailed, message)
            else controller.halt(HaltReason.SttStopped)
        },
    )

    private fun audioCallback(mediaId: Long) = object : IAudioStreamCbk {
        override fun onAudioReceived(data: ByteArray?, offset: Int, length: Int) {
            if (!accepting.get() || mediaId != mediaGeneration) return
            val pcm = meter.accept(data, offset, length, SystemClock.elapsedRealtime()) ?: return
            val failure = runCatching { vad.accept(pcm, SystemClock.elapsedRealtime(), mediaId) }.exceptionOrNull()
            if (failure != null) {
                viewModelScope.launch {
                    if (accepting.get() && mediaId == mediaGeneration) {
                        if (startGate.preparing) failStart(StartError.AudioFailed, "VAD: " + failure.message)
                        else controller.halt(HaltReason.AudioStopped)
                    }
                }
                return
            }
            if (accepting.get() && mediaId == mediaGeneration) speech.feed(pcm)
        }

        override fun onAudioError(errorCode: Int, errorInfo: String?) {
            viewModelScope.launch {
                if (accepting.get() && mediaId == mediaGeneration) {
                    if (startGate.preparing) failStart(StartError.AudioFailed, errorInfo)
                    else controller.halt(HaltReason.AudioStopped)
                }
            }
        }

        override fun onAudioStreamStateChanged(started: Boolean) {
            viewModelScope.launch {
                if (!started && accepting.get() && mediaId == mediaGeneration) {
                    if (startGate.preparing) failStart(StartError.AudioMissing, null)
                    else controller.halt(HaltReason.AudioStopped)
                }
            }
        }
    }

    init {
        viewModelScope.launch {
            combine(CxrLinkConnectionHub.cxrlConnected, CxrLinkConnectionHub.btConnected) { a, b -> a && b }
                .collect { connected ->
                    _ui.value = _ui.value.copy(connected = connected)
                    if (!connected) {
                        openingLink = null
                        _ui.value = _ui.value.copy(glassOpened = false, glassOpening = false)
                        if (startGate.preparing) failStart(StartError.NotConnected, null)
                        else controller.halt(HaltReason.ConnectionLost)
                    }
                }
        }
        // 오류로 자동 판단이 멈추면 음성 처리도 멈춘다.
        viewModelScope.launch {
            controller.state.collect {
                if (it.phase == PresentationPhase.Halted) {
                    startGate.cancel()
                    stopMedia()
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(TICK_MS)
                val now = SystemClock.elapsedRealtime()
                refreshGlassView(now)
                if (startGate.preparing && _ui.value.startStage == StartStage.AudioAndStt) {
                    finishPreparation(now)
                }
                val voice = detector.status(now)
                if (!startGate.preparing && accepting.get() && (voice == VoiceStatus.InputLost ||
                        (voice == VoiceStatus.Waiting && now - audioStartedAtMs > SilenceDetector.DEFAULT_INPUT_LOST_MS))) {
                    controller.halt(HaltReason.AudioStopped)
                }
                if (presentation.value.phase == PresentationPhase.Running && accepting.get() && detector.tick(now)) {
                    val request = controller.onPause(_ui.value.speech.partial)
                    if (request != null) requestPause(request)
                    else {
                        pauseWaitingForText = presentation.value.let {
                            it.phase == PresentationPhase.Running && it.hint == null && it.pendingHide == null && !it.pauseInFlight
                        }
                        waitingPauseRevision = detector.revision()
                    }
                }
                if (voice != VoiceStatus.Silent) {
                    pauseWaitingForText = false
                    controller.invalidatePause()
                }
                // 5초가 지난 키워드는 전달 판단과 관계없이 내린다.
                if (controller.expireIfDue(now)) hideHint()
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

    fun openGlassView() {
        if (_ui.value.glassOpening || startGate.preparing ||
            presentation.value.phase == PresentationPhase.Running) return
        val link = app.sharedLink
        if (!prepState.value.isReady || !_ui.value.connected || !app.isSessionReady || link == null) return
        _ui.value = _ui.value.copy(glassOpenError = null, startError = null, startErrorDetail = null)
        openingLink = link
        glassOpenDeadlineMs = SystemClock.elapsedRealtime() + 10_000
        _ui.value = _ui.value.copy(glassOpening = true)
        if (!renderer.open(app.getString(R.string.presentation_glass_ready_display))) {
            openingLink = null
            _ui.value = _ui.value.copy(glassOpening = false, glassOpenError = GlassOpenError.Failed)
        }
        refreshGlassView(SystemClock.elapsedRealtime())
    }

    private fun refreshGlassView(now: Long) {
        val wasOpened = _ui.value.glassOpened
        val link = app.sharedLink
        val opened = _ui.value.connected && app.isSessionReady && link != null &&
            runCatching { link.customViewIsOpen() }.getOrDefault(false)
        val waiting = _ui.value.glassOpening
        val invalidLink = openingLink != null && openingLink !== link
        val timedOut = waiting && !opened && now >= glassOpenDeadlineMs
        val finished = opened || invalidLink || timedOut || !_ui.value.connected
        _ui.value = _ui.value.copy(
            glassOpened = opened,
            glassOpening = waiting && !finished,
            glassOpenError = when {
                opened -> null
                invalidLink -> GlassOpenError.Failed
                timedOut -> GlassOpenError.TimedOut
                else -> _ui.value.glassOpenError
            },
        )
        if (finished) openingLink = null
        // 종료 후 다른 화면의 표시를 지우지 않도록 자동 재시도는 현재 발표·중단 상태에만 적용한다.
        val phase = presentation.value.phase
        if (opened && !wasOpened && presentation.value.pendingHide != null &&
            (phase == PresentationPhase.Running || phase == PresentationPhase.Halted)) hideHint()
        if (!opened && presentation.value.phase == PresentationPhase.Running) {
            controller.halt(HaltReason.GlassViewClosed)
        }
    }

    fun setSpeechRms(value: Double) {
        detector.speechRms = value
        _ui.value = _ui.value.copy(speechRms = value)
    }

    /** 테스트 중 멈춤 판단 시간 조정. 기본값은 팀 결정 3초. */
    fun setPauseMs(value: Long) {
        detector.pauseMs = value
        _ui.value = _ui.value.copy(pauseMs = value)
    }

    private fun localStartError(): StartError? {
        val prep = ScriptPrepRepository.controller.state.value
        val link = app.sharedLink
        return when {
            !prep.isReady || prep.analysis == null -> StartError.ScriptNotReady
            prep.analyzedServerUrl.isNullOrBlank() -> StartError.NoServer
            !_ui.value.connected || !app.isSessionReady || link == null -> StartError.NotConnected
            !AuthorizationHelper.hasGlassPermission(GlassPermission.MICROPHONE) -> StartError.NoGlassMic
            runCatching { link.customViewIsOpen() != true }.getOrDefault(true) -> StartError.GlassViewClosed
            ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED -> StartError.NoPhoneMic
            else -> null
        }
    }

    private fun preparationUnchanged(): Boolean {
        val prep = ScriptPrepRepository.controller.state.value
        return prep.isReady && prep.revision == preparedRevision &&
            prep.analysis?.scriptSha256 == preparedSha && prep.analyzedServerUrl == serverUrl
    }

    /** 준비 확인이 모두 끝나기 전에는 controller를 Running으로 전환하지 않는다. */
    fun start(onDevice: Boolean) {
        if (startGate.preparing || presentation.value.phase == PresentationPhase.Running) return
        stop()
        val error = localStartError()
        if (error != null) {
            _ui.value = _ui.value.copy(startError = error, startErrorDetail = null)
            return
        }
        val prep = ScriptPrepRepository.controller.state.value
        preparedSha = prep.analysis!!.scriptSha256
        preparedRevision = prep.revision
        serverUrl = prep.analyzedServerUrl!!
        val ticket = startGate.begin() ?: return
        startTicket = ticket
        _ui.value = _ui.value.copy(startStage = StartStage.Server, startError = null, startErrorDetail = null)
        val requestedUrl = serverUrl
        val requestedSha = preparedSha
        startJob = viewModelScope.launch {
            val failure = client.checkPrepared(requestedUrl, requestedSha)
            if (!startGate.isCurrent(ticket)) return@launch
            if (failure != null) {
                val reason = when (failure) {
                    PrepFailure.Timeout -> StartError.ServerTimeout
                    is PrepFailure.InvalidFormat -> StartError.ServerInvalid
                    is PrepFailure.Server -> if (failure.code == "script_not_prepared")
                        StartError.ScriptMissing else StartError.ServerUnavailable
                    else -> StartError.ServerUnavailable
                }
                failStart(reason, null)
                return@launch
            }
            val localError = localStartError()
            if (!preparationUnchanged() || localError != null) {
                failStart(localError ?: StartError.ScriptNotReady, null)
                return@launch
            }
            if (!startGate.serverConfirmed(ticket)) return@launch
            val link = app.sharedLink ?: return@launch failStart(StartError.NotConnected, null)
            audioLink = link
            pauseWaitingForText = false
            meter.reset()
            detector.reset()
            _ui.value = _ui.value.copy(startStage = StartStage.AudioAndStt)
            startupDeadlineMs = SystemClock.elapsedRealtime() + STARTUP_TIMEOUT_MS
            val mediaId = ++mediaGeneration
            val vadFailure = runCatching { vad.start(mediaId) }.exceptionOrNull()
            if (vadFailure != null) {
                failStart(StartError.AudioFailed, "VAD: " + vadFailure.message)
                return@launch
            }
            try {
                link.setCXRAudioCbk(audioCallback(mediaId))
                audioStartedAtMs = SystemClock.elapsedRealtime()
                accepting.set(true)
                val result: Any? = link.startAudioStream(AUDIO_CODEC_PCM)
                if (result == false) {
                    failStart(StartError.AudioRejected, null)
                    return@launch
                }
            } catch (e: Exception) {
                failStart(StartError.AudioFailed, e.message)
                return@launch
            }
            if (startGate.isCurrent(ticket)) speech.start(onDevice)
        }
    }

    private fun finishPreparation(nowMs: Long) {
        val localError = localStartError()
        if (!preparationUnchanged() || localError != null || app.sharedLink !== audioLink) {
            failStart(localError ?: if (app.sharedLink !== audioLink) StartError.NotConnected
                else StartError.ScriptNotReady, null)
            return
        }
        val voice = detector.status(nowMs)
        val hasAudio = accepting.get() && (voice == VoiceStatus.Speaking || voice == VoiceStatus.Silent)
        val sttReady = _ui.value.speech.running && _ui.value.speech.ready
        if (startGate.complete(startTicket, hasAudio, sttReady)) {
            // 준비 중 VAD의 프레임·발화 이력은 발표로 가져오지 않는다.
            val vadFailure = runCatching { vad.start(mediaGeneration) }.exceptionOrNull()
            if (vadFailure != null) {
                failStart(StartError.AudioFailed, "VAD: " + vadFailure.message)
                return
            }
            // Preparation speech is not presentation content or a pause trigger.
            detector.reset()
            audioStartedAtMs = nowMs
            controller.start(preparedSha)
            _ui.value = _ui.value.copy(startStage = null)
            hideHint()
        } else if (nowMs >= startupDeadlineMs) {
            failStart(if (!hasAudio) StartError.AudioMissing else StartError.SttTimeout, null)
        }
    }

    fun stop() {
        startGate.cancel()
        startJob?.cancel()
        startJob = null
        val hadHint = controller.stop()
        stopMedia()
        _ui.value = _ui.value.copy(startStage = null)
        if (hadHint) hideHint()
    }

    private fun failStart(error: StartError, detail: String?) {
        stop()
        _ui.value = _ui.value.copy(startError = error, startErrorDetail = detail)
    }

    private fun stopMedia() {
        mediaGeneration++
        pauseWaitingForText = false
        if (accepting.getAndSet(false)) runCatching { audioLink?.stopAudioStream() }
        runCatching { vad.close() }
        // A restart keeps running=true. Preserve an already stopped recognizer error.
        if (_ui.value.speech.running) speech.cancel()
    }

    fun retryHide() {
        if (presentation.value.pendingHide != null) hideHint()
    }

    private fun hideHint() {
        controller.requestHide()
        if (renderer.hide()) controller.onHideSent() else controller.onHideFailed()
    }

    private fun hasSpeechAfterHint(): Boolean =
        presentation.value.hint?.shownAtMs != null && detector.speechRevision() > hintSpeechRevisionAtShow

    private fun requestPause(request: PauseRequest) {
        val pauseRevision = detector.revision()
        waitingPauseRevision = pauseRevision
        val requestServerUrl = serverUrl
        viewModelScope.launch {
            val outcome = client.pause(requestServerUrl, request)
            val currentOutcome = if (outcome is PauseOutcome.Hint &&
                !detector.isCurrentPause(pauseRevision, SystemClock.elapsedRealtime())) {
                PauseOutcome.NoHint("pause_ended")
            } else outcome
            val shown = controller.completePause(request, currentOutcome) ?: return@launch
            val ackBefore = CustomViewAcks.last.value.seq
            hintSpeechRevisionAtShow = detector.speechRevision()
            val sent = renderer.show(keywordLine(shown.hint.keywords))
            controller.onHintRendered(shown.id, sent, SystemClock.elapsedRealtime(), _ui.value.speech.partial)
            if (!sent) return@launch
            // 요청 전송만으로는 안경에 보였는지 알 수 없다. 안경 갱신 응답을 기다려 기록한다.
            val ack = withTimeoutOrNull(GLASS_ACK_TIMEOUT_MS) {
                CustomViewAcks.last.first { it.seq > ackBefore }
            }
            controller.onHintAck(shown.id, ack?.let { it.error == null }, SystemClock.elapsedRealtime())
        }
    }

    private fun requestDelivery(request: DeliveryRequest) {
        viewModelScope.launch {
            val outcome = client.delivery(serverUrl, request)
            if (controller.completeDelivery(request, outcome)) hideHint()
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
        /** Resource startup deadline; separate from the presentation silence policy. */
        const val STARTUP_TIMEOUT_MS = 10_000L
        /** 표시 요청 뒤 안경 갱신 응답 대기. 임시값, 실기기 응답 지연 확인 후 조정. */
        const val GLASS_ACK_TIMEOUT_MS = 2_000L
        const val TIMING_TAG = "ServerTiming"
        /** 기존 샘플·진단과 같은 값. 16 kHz·모노·16비트 PCM으로 가정 (실기기 확인 필요). */
        const val AUDIO_CODEC_PCM = 1
    }
}
