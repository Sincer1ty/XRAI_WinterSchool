package com.rokid.renewcxrlsample.diagnostics

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rokid.renewcxrlsample.ui.design.DevSampleScaffold
import com.rokid.renewcxrlsample.ui.design.DevStatusCard
import com.rokid.renewcxrlsample.ui.design.PrerequisiteHint
import com.rokid.renewcxrlsample.ui.design.SceneSectionTitle
import com.rokid.renewcxrlsample.ui.theme.RenewCXRLSampleTheme
import java.util.Locale

class GlassDiagnosticsActivity : ComponentActivity() {
    private val viewModel by viewModels<GlassDiagnosticsViewModel>()
    private var pendingOnDevice = true
    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startSpeech(pendingOnDevice)
        else Toast.makeText(this, "권한 거부: STT는 시작하지 않았습니다. 안경 수신량은 계속 확인할 수 있습니다.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingOnDevice = savedInstanceState?.getBoolean("pendingOnDevice", true) ?: true
        enableEdgeToEdge()
        setContent {
            RenewCXRLSampleTheme {
                GlassDiagnosticsScreen(viewModel, onBack = { finish() }) { onDevice ->
                    pendingOnDevice = onDevice
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        viewModel.startSpeech(onDevice)
                    } else {
                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pendingOnDevice", pendingOnDevice)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            viewModel.stopCapture()
            viewModel.cancelSpeech()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) viewModel.close()
        super.onDestroy()
    }
}

@Composable
private fun GlassDiagnosticsScreen(
    viewModel: GlassDiagnosticsViewModel,
    onBack: () -> Unit,
    onStartSpeech: (Boolean) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var onDevice by rememberSaveable { mutableStateOf(true) }
    DevSampleScaffold(
        title = "안경 음성·STT 진단",
        subtitle = "실기기 동작은 사용자가 확인합니다.",
        showBack = true,
        onBack = onBack,
    ) {
        if (!state.connected) PrerequisiteHint("안경 연결 대기 중 · 연결 화면에서 안경 화면을 먼저 여세요.")

        SceneSectionTitle("1. 안경 음성 수신")
        DevStatusCard(
            title = state.captureStatus,
            lines = listOf(
                "수신: " + state.pcm.chunks + "청크 · " + state.pcm.bytes + " bytes · 잘못된 청크 " + state.pcm.invalidChunks,
                "마지막 수신 후: " + (state.inputAgeMs?.let { "$it ms" } ?: "수신 없음"),
                "소리 크기(RMS): 현재 " + rmsText(state.pcm.rms) + " · 최대 " + rmsText(state.pcm.peakRms),
            ),
        )
        if (state.pcm.bytes > 0 && state.pcm.peakRms < QUIET_PEAK_RMS) {
            PrerequisiteHint("말해도 최대값이 " + QUIET_PEAK_RMS + " 미만이면 거의 무음입니다. 음성 사용 화면에서 녹음·재생으로 소리를 확인하세요.")
        }
        ButtonPair(
            primary = "수신 시작", onPrimary = { viewModel.startCapture() },
            primaryEnabled = state.connected && !state.receiving,
            secondary = "중지", onSecondary = { viewModel.stopCapture() },
            secondaryEnabled = state.receiving,
        )

        SceneSectionTitle("2. 안경 표시")
        DevStatusCard(
            title = state.hintStatus,
            lines = listOf("숨김 후 수신: " + (state.pcm.bytesAfterHide?.let { "$it bytes" } ?: "숨김 시험 전")),
        )
        ButtonPair(
            primary = "시험 문구 표시", onPrimary = { viewModel.setHint(false) },
            primaryEnabled = state.connected,
            secondary = "숨김", onSecondary = { viewModel.setHint(true) },
            secondaryEnabled = state.connected,
        )

        SceneSectionTitle("3. STT")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = onDevice, onCheckedChange = { onDevice = it }, enabled = !state.speech.running)
            Text(
                when {
                    !onDevice -> "기본 인식기 · 외부 서버로 전송될 수 있음"
                    state.deviceRecognizerAvailable -> "기기 내 인식기"
                    else -> "기기 내 인식기 (이 폰에 없음)"
                }
            )
        }
        if (!state.receiving || state.pcm.bytes == 0L) PrerequisiteHint("1단계에서 수신을 시작하면 STT를 켤 수 있습니다.")
        DevStatusCard(
            title = state.speech.status,
            lines = listOf(
                "인식기에 보낸 바이트: " + state.pipeBytes,
                "중간 결과: " + state.speech.partial.ifBlank { "없음" },
                "확정 결과: " + state.speech.transcript.ifBlank { "없음" },
            ),
        )
        ButtonPair(
            primary = "STT 시작", onPrimary = { onStartSpeech(onDevice) },
            primaryEnabled = state.receiving && state.pcm.bytes > 0 && !state.speech.running,
            secondary = "종료·결과 받기", onSecondary = { viewModel.finishSpeech() },
            secondaryEnabled = state.speech.running,
        )
        OutlinedButton(
            onClick = { viewModel.cancelSpeech() },
            enabled = state.speech.running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("STT 취소") }
        Text("폰 마이크를 대신 쓰는 인식기도 있습니다. 폰과 안경을 떨어뜨리고 확인하세요. 화면을 나가면 수신이 멈춥니다.")
    }
}

// Heuristic for "nothing audible arrived"; tune on the real device.
private const val QUIET_PEAK_RMS = 0.01

private fun rmsText(value: Double) = String.format(Locale.ROOT, "%.4f", value)

@Composable
private fun ButtonPair(
    primary: String,
    onPrimary: () -> Unit,
    primaryEnabled: Boolean,
    secondary: String,
    onSecondary: () -> Unit,
    secondaryEnabled: Boolean,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onPrimary, enabled = primaryEnabled, modifier = Modifier.weight(1f)) { Text(primary) }
        OutlinedButton(onClick = onSecondary, enabled = secondaryEnabled, modifier = Modifier.weight(1f)) { Text(secondary) }
    }
}