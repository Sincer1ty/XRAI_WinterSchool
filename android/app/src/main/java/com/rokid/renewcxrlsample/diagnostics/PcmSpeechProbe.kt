package com.rokid.renewcxrlsample.diagnostics

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SpeechProbeState(
    val running: Boolean = false,
    val ready: Boolean = false,
    val status: String = "STT 시작 전",
    val partial: String = "",
    val transcript: String = "",
)

/** Requests external PCM explicitly; a result alone does not prove the engine used it. */
class PcmSpeechProbe(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onState: (SpeechProbeState) -> Unit,
    /** Each non-blank final (segment) result, on the main thread. */
    private val onFinal: (String) -> Unit = {},
    /** 발표용: 인식 세션이 끝나도 입력이 계속되면 다시 시작한다. 진단 화면은 끔. */
    private val autoRestart: Boolean = false,
    /** autoRestart 중 더 이상 재시작하지 못하고 멈췄을 때 (메인 스레드). 사용자가 cancel한 경우는 제외. */
    private val onStopped: (String) -> Unit = {},
) {
    private var lastOnDevice = true
    private var failuresInRow = 0

    private var state = SpeechProbeState()
    private var recognizer: SpeechRecognizer? = null
    private var readEnd: ParcelFileDescriptor? = null
    private var output: OutputStream? = null
    private var queue: Channel<ByteArray>? = null
    private var writer: Job? = null
    private var resultTimeout: Job? = null
    @Volatile private var generation = 0
    @Volatile private var accepting = false
    @Volatile private var feedingQueue: Channel<ByteArray>? = null
    val writtenBytes = AtomicLong(0)

    fun start(onDevice: Boolean) {
        failuresInRow = 0
        begin(onDevice, keepTranscript = false)
    }

    private fun begin(onDevice: Boolean, keepTranscript: Boolean) {
        lastOnDevice = onDevice
        val previousTranscript = if (keepTranscript) state.transcript else ""
        cleanup()
        if (Build.VERSION.SDK_INT < 33) {
            fail("외부 PCM 입력 시험은 Android 13 이상에서 지원합니다.")
            return
        }
        val available = if (onDevice) SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            else SpeechRecognizer.isRecognitionAvailable(context)
        if (!available) {
            fail(if (onDevice) "기기 내 인식기를 사용할 수 없습니다. 기본 인식기는 별도로 선택하세요."
                else "기본 음성 인식기가 없습니다.")
            return
        }
        val id = generation
        writtenBytes.set(0)
        state = SpeechProbeState(running = true, status = "안경 PCM 외부 입력 요청 중", transcript = previousTranscript)
        onState(state)
        try {
            val pipe = ParcelFileDescriptor.createPipe()
            readEnd = pipe[0]
            val sink = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            output = sink
            val pending = Channel<ByteArray>(32)
            queue = pending
            feedingQueue = pending
            accepting = true
            val engine = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                else SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = engine
            engine.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (id == generation) {
                        state = state.copy(ready = true)
                        publish("인식기 준비됨 · 외부 입력 사용 여부는 직접 확인")
                    }
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {
                    if (id != generation) return
                    state = state.copy(partial = text(partialResults))
                    onState(state)
                }
                override fun onSegmentResults(segmentResults: Bundle) {
                    if (id == generation) appendResult(segmentResults)
                }
                override fun onResults(results: Bundle?) {
                    if (id != generation) return
                    appendResult(results)
                    endSession(id, "인식기 세션 종료 · 다시 시작하려면 STT 시작을 누르세요.", null)
                }
                override fun onEndOfSegmentedSession() {
                    endSession(id, "분할 인식 세션 종료", null)
                }
                override fun onError(error: Int) {
                    endSession(id, "STT 오류 " + error + ": " + errorLabel(error), error)
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            }
            engine.startListening(intent)
            writer = scope.launch(Dispatchers.IO) {
                try {
                    for (pcm in pending) {
                        sink.write(pcm)
                        if (id == generation) writtenBytes.addAndGet(pcm.size.toLong())
                    }
                } catch (error: Exception) {
                    withContext(Dispatchers.Main) {
                        if (id == generation && accepting) {
                            fail("STT 입력 파이프 오류: " + (error.message ?: "입력 종료"))
                        }
                    }
                } finally {
                    runCatching { sink.close() }
                }
            }
        } catch (error: Exception) {
            fail("STT 시작 실패: " + (error.message ?: error.javaClass.simpleName))
        }
    }

    /** Called from the SDK callback. Never blocks the audio callback thread. */
    fun feed(pcm: ByteArray) {
        if (!accepting) return
        val target = feedingQueue ?: return
        if (target.trySend(pcm).isFailure && accepting) {
            accepting = false
            scope.launch {
                if (queue === target) fail("STT 입력이 밀려 중지했습니다. 외부 입력 지원·처리 속도를 확인하세요.")
            }
        }
    }

    fun finishInput() {
        if (!state.running || !accepting) return
        accepting = false
        feedingQueue = null
        queue?.close()
        publish("입력 종료 · 인식 결과를 기다리는 중")
        val id = generation
        resultTimeout = scope.launch {
            delay(10_000)
            if (id == generation) finish("입력 종료 후 10초 동안 세션 종료 응답이 없습니다.")
        }
    }

    fun cancel() { finish("STT 중지") }

    /** 세션 종료 처리. autoRestart면 입력이 계속되는 동안 다시 시작하고, 못 하면 onStopped로 알린다. */
    private fun endSession(id: Int, message: String, error: Int?) {
        if (id != generation) return
        val fatal = error != null && error in FATAL_ERRORS
        // 무음 구간의 결과 없음·시간 초과는 정상 흐름이라 실패 횟수에 넣지 않는다.
        val counted = error != null && error !in BENIGN_ERRORS
        if (autoRestart && accepting && !fatal && (!counted || failuresInRow < MAX_FAILURES_IN_ROW)) {
            if (counted) failuresInRow++
            cleanup()
            state = state.copy(ready = false, status = "인식 다시 시작 중 · $message", partial = "")
            onState(state)
            val restartGeneration = generation
            scope.launch {
                delay(RESTART_DELAY_MS)
                if (restartGeneration == generation) begin(lastOnDevice, keepTranscript = true)
            }
            return
        }
        fail(message)
    }

    private fun appendResult(bundle: Bundle?) {
        val result = text(bundle)
        if (result.isBlank()) return
        failuresInRow = 0
        state = state.copy(
            transcript = (state.transcript.lineSequence().filter { it.isNotBlank() }.toList() + result)
                .takeLast(12).joinToString("\n"),
            partial = "",
        )
        onState(state)
        onFinal(result)
    }

    private fun text(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun publish(message: String) {
        state = state.copy(status = message)
        onState(state)
    }

    private fun finish(message: String) {
        cleanup()
        state = state.copy(running = false, ready = false, status = message, partial = "")
        onState(state)
    }

    /** 오류로 멈춤. 발표 모드에서는 호출자에게 STT 중단을 알린다. */
    private fun fail(message: String) {
        finish(message)
        if (autoRestart) onStopped(message)
    }

    private fun cleanup() {
        generation++
        accepting = false
        feedingQueue = null
        resultTimeout?.cancel()
        resultTimeout = null
        queue?.cancel()
        queue = null
        runCatching { output?.close() }
        output = null
        runCatching { readEnd?.close() }
        readEnd = null
        writer?.cancel()
        writer = null
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun errorLabel(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "오디오 입력 오류"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "휴대폰 마이크 권한 필요"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "한국어 인식 미지원"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "한국어 모델 준비 필요"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "인식기 네트워크 오류"
        SpeechRecognizer.ERROR_NO_MATCH -> "인식 결과 없음"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "인식기 사용 중"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "음성 입력 시간 초과"
        else -> "인식기·외부 오디오 지원 상태 확인 필요"
    }

    private companion object {
        const val RESTART_DELAY_MS = 500L
        const val MAX_FAILURES_IN_ROW = 5
        val FATAL_ERRORS = setOf(
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        )
        val BENIGN_ERRORS = setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
    }
}