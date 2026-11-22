package com.rokid.renewcxrlsample.ui.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokid.renewcxrlsample.R
import com.rokid.renewcxrlsample.presentation.GlassOpenError
import com.rokid.renewcxrlsample.presentation.HaltReason
import com.rokid.renewcxrlsample.presentation.NoteKind
import com.rokid.renewcxrlsample.presentation.PresentationNote
import com.rokid.renewcxrlsample.presentation.PresentationPhase
import com.rokid.renewcxrlsample.presentation.PresentationState
import com.rokid.renewcxrlsample.presentation.PresentationUiState
import com.rokid.renewcxrlsample.presentation.keywordLine
import com.rokid.renewcxrlsample.presentation.PresentationViewModel
import com.rokid.renewcxrlsample.presentation.StartStage
import com.rokid.renewcxrlsample.presentation.StartError
import com.rokid.renewcxrlsample.presentation.VoiceStatus
import com.rokid.renewcxrlsample.ui.design.DevSampleScaffold
import com.rokid.renewcxrlsample.ui.design.DevStatusCard
import com.rokid.renewcxrlsample.ui.design.FlowStepIndicator
import com.rokid.renewcxrlsample.ui.design.PrerequisiteHint
import com.rokid.renewcxrlsample.ui.design.SceneSectionTitle
import com.rokid.renewcxrlsample.script.RequestTiming
import java.util.Locale

/** 발화 기준(RMS) 조정 범위. 실기기 소리 크기를 보며 맞춘다. */
private const val SPEECH_RMS_MIN = 0.001f
private const val SPEECH_RMS_MAX = 0.05f

/** 멈춤 판단 시간 조정 범위 (0.5초 단위). */
private const val PAUSE_MS_MIN = 1_000f
private const val PAUSE_MS_MAX = 5_000f

@Composable
fun PresentationScreen(
    viewModel: PresentationViewModel,
    onBack: () -> Unit,
    onReviewScript: () -> Unit,
    onConnectGlasses: () -> Unit,
    onStart: (onDevice: Boolean) -> Unit,
) {
    val p by viewModel.presentation.collectAsState()
    val ui by viewModel.ui.collectAsState()
    val prep by viewModel.prepState.collectAsState()
    val timing by viewModel.lastTiming.collectAsState()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var onDevice by rememberSaveable { mutableStateOf(true) }
    val running = p.phase == PresentationPhase.Running
    val preparing = ui.startStage != null
    val none = stringResource(R.string.presentation_none)

    DevSampleScaffold(
        title = stringResource(R.string.presentation_title),
        subtitle = stringResource(R.string.presentation_subtitle),
        showBack = true,
        onBack = onBack,
        responsive = true,
        bottomBar = {
            // 숨김 대기 중에도 새 발표를 시작할 수 있도록 재시도 버튼은 아래 버튼과 함께 보인다.
            if (!preparing && !running && p.pendingHide != null && ui.glassOpened) {
                OutlinedButton(
                    onClick = viewModel::retryHide,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.presentation_retry_hide))
                }
            }
            when {
                preparing || running -> OutlinedButton(
                    onClick = viewModel::stop,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(if (preparing) R.string.presentation_cancel_start else R.string.presentation_stop))
                }
                !prep.isReady -> Button(onClick = onReviewScript, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.presentation_review_script))
                }
                !ui.connected -> OutlinedButton(onClick = onConnectGlasses, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.presentation_connect_glasses))
                }
                !ui.glassOpened -> OutlinedButton(
                    onClick = viewModel::openGlassView,
                    enabled = !ui.glassOpening,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(
                        if (ui.glassOpening) R.string.presentation_glass_opening else R.string.presentation_open_glass_step,
                    ))
                }
                else -> Button(
                    onClick = { onStart(onDevice) },
                    enabled = !ui.glassOpening,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.presentation_start_step))
                }
            }
        },
    ) {
        FlowStepIndicator(
            steps = listOf(
                stringResource(R.string.presentation_step_script),
                stringResource(R.string.presentation_step_glass),
                stringResource(R.string.presentation_step_start),
            ),
            currentIndex = if (!prep.isReady) 0 else if (!ui.glassOpened) 1 else 2,
        )
        DevStatusCard(
            title = stringResource(if (prep.isReady) R.string.presentation_script_done else R.string.presentation_need_prep),
            lines = listOf(stringResource(when {
                !prep.isReady -> R.string.presentation_flow_need_script
                !ui.connected -> R.string.presentation_flow_need_connection
                ui.glassOpening -> R.string.presentation_glass_opening
                !ui.glassOpened -> R.string.presentation_flow_open_glass
                running -> R.string.presentation_flow_running
                preparing -> R.string.presentation_flow_preparing
                else -> R.string.presentation_flow_ready
            })),
        )
        if (prep.isReady && !running && !preparing) {
            TextButton(onClick = onReviewScript) {
                Text(stringResource(R.string.presentation_review_script))
            }
        }
        ui.glassOpenError?.let {
            PrerequisiteHint(stringResource(
                if (it == GlassOpenError.TimedOut) R.string.presentation_glass_open_timeout
                else R.string.presentation_glass_open_failed
            ))
        }
        if (p.phase == PresentationPhase.Halted) {
            OutlinedButton(onClick = viewModel::stop, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.presentation_stop))
            }
        }
        DevStatusCard(
            title = liveStatus(p, ui),
            lines = listOfNotNull(
                // 시작 전·종료 상태에서는 제목이 이미 단계 표시이므로 반복하지 않는다.
                if (running) stringResource(R.string.presentation_phase_line, phaseLabel(p.phase)) else null,
                // 중지 사유가 있으면 그 원인만 보인다. 요청 실패 안내("서버 연결 확인")가
                // "서버에 대본 없음"과 함께 떠 원인이 엇갈리지 않게 한다.
                p.note?.takeUnless { preparing || (p.phase == PresentationPhase.Halted && it.kind != NoteKind.HideFailed) }?.let { noteText(it) },
                p.haltReason?.takeIf { p.phase == PresentationPhase.Halted }?.let { haltText(it, ui) },
            ),
        )
        ui.startError?.let { PrerequisiteHint(startErrorText(it, ui.startErrorDetail)) }

        PrerequisiteHint(stringResource(R.string.presentation_keep_screen))

        SceneSectionTitle(stringResource(R.string.presentation_hint_title))
        GlassPreview((p.hint ?: p.pendingHide?.hint)?.let { keywordLine(it.hint.keywords) })
        if (running && p.pendingHide != null && ui.glassOpened) {
            OutlinedButton(onClick = viewModel::retryHide, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.presentation_retry_hide))
            }
        }
        Text(
            stringResource(R.string.presentation_recent_line, p.recentFinals.joinToString(" / ").ifBlank { none }),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        TextButton(onClick = { showSettings = !showSettings }) {
            Text(stringResource(if (showSettings) R.string.presentation_settings_hide else R.string.presentation_settings_show))
        }
        if (showSettings) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(checked = onDevice, onCheckedChange = { onDevice = it }, enabled = !running && !preparing)
                Text(stringResource(R.string.presentation_on_device), modifier = Modifier.weight(1f))
            }
            SceneSectionTitle(stringResource(R.string.presentation_voice_title))
            DevStatusCard(
                title = stringResource(R.string.presentation_voice_line, voiceLabel(ui.voice), ui.silentMs / 1000.0),
                lines = listOf(
                    stringResource(R.string.presentation_rms_line, rmsText(ui.rms), rmsText(ui.peakRms), rmsText(ui.speechRms)),
                    stringResource(R.string.presentation_stt_line, ui.speech.status),
                    timing?.let {
                        stringResource(
                            R.string.presentation_timing_line, it.path.substringAfterLast('/'),
                            RequestTiming.formatMs(it.roundTripMs), RequestTiming.formatMs(it.serverMs),
                            RequestTiming.formatMs(it.networkMs),
                        )
                    } ?: stringResource(R.string.presentation_timing_none),
                    stringResource(R.string.presentation_partial_line, ui.speech.partial.ifBlank { none }),
                ),
            )
            Text(stringResource(R.string.presentation_pause_label, ui.pauseMs / 1000.0))
            Slider(
                value = ui.pauseMs.toFloat(),
                onValueChange = { viewModel.setPauseMs((it / 500).toLong() * 500) },
                valueRange = PAUSE_MS_MIN..PAUSE_MS_MAX,
            )
            Text(stringResource(R.string.presentation_threshold_label))
            Slider(
                value = ui.speechRms.toFloat(),
                onValueChange = { viewModel.setSpeechRms(it.toDouble()) },
                valueRange = SPEECH_RMS_MIN..SPEECH_RMS_MAX,
                enabled = false,
            )
        }
    }
}

private fun rmsText(value: Double) = String.format(Locale.ROOT, "%.4f", value)

/** 안경 화면 미리보기. 안경 템플릿과 같은 검은 배경·흰 굵은 글씨. 실제 안경 표시는 안경에서 확인한다. */
@Composable
private fun GlassPreview(text: String?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (text.isNullOrEmpty()) {
            Text(stringResource(R.string.presentation_hint_none), color = Color.Gray, textAlign = TextAlign.Center)
        } else {
            Text(text, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
    }
}

/** 지금 무슨 일이 일어나는지 한 줄로: 듣는 중 → 공백 감지 → 키워드 표시 중(남은 초). */
@Composable
private fun liveStatus(p: PresentationState, ui: PresentationUiState): String {
    ui.startStage?.let {
        return stringResource(if (it == StartStage.Server) R.string.presentation_checking_server
            else R.string.presentation_checking_audio_stt)
    }
    if (p.phase != PresentationPhase.Running) {
        return stringResource(R.string.presentation_phase_line, phaseLabel(p.phase))
    }
    val remaining = ui.hintRemainingMs
    return when {
        p.pendingHide != null -> stringResource(R.string.presentation_live_hiding)
        p.hint != null && remaining != null ->
            stringResource(R.string.presentation_live_showing, ((remaining + 999) / 1000).toInt())
        p.hint != null -> stringResource(R.string.presentation_live_sending)
        p.pauseInFlight -> stringResource(R.string.presentation_live_requesting)
        ui.voice == VoiceStatus.Silent -> stringResource(R.string.presentation_live_pause)
        else -> stringResource(R.string.presentation_live_listening)
    }
}

@Composable
private fun phaseLabel(phase: PresentationPhase): String = stringResource(
    when (phase) {
        PresentationPhase.Idle -> R.string.presentation_phase_idle
        PresentationPhase.Running -> R.string.presentation_phase_running
        PresentationPhase.Halted -> R.string.presentation_phase_halted
        PresentationPhase.Stopped -> R.string.presentation_phase_stopped
    }
)

@Composable
private fun voiceLabel(status: VoiceStatus): String = stringResource(
    when (status) {
        VoiceStatus.Waiting -> R.string.presentation_voice_waiting
        VoiceStatus.Speaking -> R.string.presentation_voice_speaking
        VoiceStatus.Silent -> R.string.presentation_voice_silent
        VoiceStatus.InputLost -> R.string.presentation_voice_lost
    }
)

@Composable
private fun haltText(reason: HaltReason, ui: PresentationUiState): String = when (reason) {
    HaltReason.ConnectionLost -> stringResource(R.string.presentation_halt_connection)
    HaltReason.AudioStopped -> stringResource(R.string.presentation_halt_audio)
    HaltReason.SttStopped -> stringResource(R.string.presentation_halt_stt, ui.speech.status)
    HaltReason.ScriptNotPrepared -> stringResource(R.string.presentation_halt_script)
    HaltReason.ServerUnavailable -> stringResource(R.string.presentation_halt_server)
    HaltReason.GlassViewClosed -> stringResource(R.string.presentation_halt_glass_closed)
}

@Composable
private fun noteText(note: PresentationNote): String {
    val d = note.detail.orEmpty()
    return when (note.kind) {
        NoteKind.Started -> stringResource(R.string.presentation_note_started)
        NoteKind.PauseRequested -> stringResource(R.string.presentation_note_pause_requested)
        NoteKind.NoHint -> stringResource(R.string.presentation_note_no_hint, d)
        NoteKind.PauseFailed -> stringResource(R.string.presentation_note_pause_failed)
        NoteKind.HintSent -> stringResource(R.string.presentation_note_hint_sent, d)
        NoteKind.HintConfirmed -> stringResource(R.string.presentation_note_hint_confirmed, d)
        NoteKind.HintNoAck -> stringResource(R.string.presentation_note_hint_no_ack, d)
        NoteKind.HintShowFailed -> stringResource(R.string.presentation_note_hint_show_failed, d)
        NoteKind.NotDeliveredYet -> stringResource(R.string.presentation_note_not_delivered)
        NoteKind.DeliveryCheckFailed -> stringResource(R.string.presentation_note_delivery_failed)
        NoteKind.Delivered -> stringResource(R.string.presentation_note_delivered, d)
        NoteKind.HintExpired -> stringResource(R.string.presentation_note_expired, d)
        NoteKind.HideFailed -> stringResource(R.string.presentation_note_hide_failed)
        NoteKind.Stopped -> stringResource(R.string.presentation_note_stopped)
    }
}

@Composable
private fun startErrorText(error: StartError, detail: String?): String = when (error) {
    StartError.ScriptNotReady -> stringResource(R.string.presentation_err_script)
    StartError.NoServer -> stringResource(R.string.presentation_err_server)
    StartError.NotConnected -> stringResource(R.string.presentation_err_connected)
    StartError.NoGlassMic -> stringResource(R.string.presentation_err_glass_mic)
    StartError.GlassViewClosed -> stringResource(R.string.presentation_err_view)
    StartError.NoPhoneMic -> stringResource(R.string.presentation_err_phone_mic)
    StartError.AudioRejected -> stringResource(R.string.presentation_err_audio_rejected)
    StartError.AudioFailed -> stringResource(R.string.presentation_err_audio_failed, detail.orEmpty())
    StartError.ServerUnavailable -> stringResource(R.string.presentation_start_server_failed)
    StartError.ServerTimeout -> stringResource(R.string.presentation_start_server_timeout)
    StartError.ScriptMissing -> stringResource(R.string.presentation_halt_script)
    StartError.ServerInvalid -> stringResource(R.string.presentation_start_server_invalid)
    StartError.SttFailed -> stringResource(R.string.presentation_halt_stt, detail.orEmpty())
    StartError.AudioMissing -> stringResource(R.string.presentation_start_audio_missing)
    StartError.SttTimeout -> stringResource(R.string.presentation_start_stt_timeout)
}
