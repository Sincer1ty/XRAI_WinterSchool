package com.rokid.renewcxrlsample.ui.feature

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rokid.renewcxrlsample.R
import com.rokid.renewcxrlsample.activities.script.ScriptPrepViewModel
import com.rokid.renewcxrlsample.script.PrepFailure
import com.rokid.renewcxrlsample.script.PrepPhase
import com.rokid.renewcxrlsample.script.RequestTiming
import com.rokid.renewcxrlsample.script.ScriptFileReader
import com.rokid.renewcxrlsample.script.ScriptPart
import com.rokid.renewcxrlsample.script.ScriptPrepState
import com.rokid.renewcxrlsample.ui.design.DevSampleScaffold
import com.rokid.renewcxrlsample.ui.design.DevStatusCard
import com.rokid.renewcxrlsample.ui.design.FlowStepIndicator
import com.rokid.renewcxrlsample.ui.design.PrerequisiteHint
import com.rokid.renewcxrlsample.ui.design.SceneSectionTitle

@Composable
fun ScriptPrepScreen(
    viewModel: ScriptPrepViewModel,
    onBack: () -> Unit,
    onPickFile: () -> Unit,
    onReady: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val fileError by viewModel.fileError.collectAsState()
    val fileLoading by viewModel.fileLoading.collectAsState()
    val timing by viewModel.lastTiming.collectAsState()
    val analyzing = state.phase == PrepPhase.Analyzing

    DevSampleScaffold(
        title = stringResource(R.string.script_prep_title),
        subtitle = stringResource(R.string.script_prep_subtitle),
        showBack = true,
        onBack = onBack,
        responsive = true,
        bottomBar = {
            when {
                analyzing -> OutlinedButton(onClick = viewModel::cancelAnalysis, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.script_prep_cancel))
                }
                state.phase == PrepPhase.Reviewing || state.isReady -> ReadyControls(
                    state = state,
                    onConfirm = {
                        viewModel.confirmReady()
                        if (viewModel.state.value.isReady) onReady()
                    },
                    onContinue = onReady,
                )
                else -> Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = viewModel::analyze,
                    enabled = state.canSubmit && serverUrl.isNotBlank(),
                ) {
                    Text(stringResource(
                        if (state.phase == PrepPhase.Editing) R.string.script_prep_analyze
                        else R.string.script_prep_reanalyze,
                    ))
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
            currentIndex = 0,
        )
        DevStatusCard(
            title = stringResource(R.string.script_prep_status_title),
            lines = listOfNotNull(
                stringResource(R.string.script_prep_status_line, phaseLabel(state.phase)),
                timing?.let {
                    stringResource(
                        R.string.script_prep_timing_line, RequestTiming.formatMs(it.roundTripMs),
                        RequestTiming.formatMs(it.llmMs), RequestTiming.formatMs(it.serverMs),
                        RequestTiming.formatMs(it.networkMs),
                    )
                },
            )
        )

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = serverUrl,
            onValueChange = viewModel::onServerUrlChanged,
            label = { Text(stringResource(R.string.script_prep_server_label)) },
            placeholder = { Text(stringResource(R.string.script_prep_server_placeholder)) },
            singleLine = true,
            enabled = !analyzing,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            isError = serverUrl.isBlank(),
            supportingText = if (serverUrl.isBlank()) {
                { Text(stringResource(R.string.script_prep_server_required)) }
            } else null
        )

        SceneSectionTitle(stringResource(R.string.script_prep_script_label))
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onPickFile,
            enabled = !fileLoading
        ) { Text(stringResource(R.string.script_prep_load_file)) }
        state.sourceName?.let {
            Text(
                stringResource(R.string.script_prep_loaded_file, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        fileError?.let { PrerequisiteHint(fileErrorMessage(it)) }

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = state.script,
            onValueChange = viewModel::onScriptChanged,
            placeholder = { Text(stringResource(R.string.script_prep_script_placeholder)) },
            minLines = 8,
            maxLines = 16,
            isError = state.isScriptBlank,
            supportingText = {
                Text(
                    if (state.isScriptBlank) {
                        stringResource(R.string.script_prep_script_required)
                    } else {
                        stringResource(R.string.script_prep_char_count, state.script.length)
                    }
                )
            }
        )

        if (analyzing) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                Text(stringResource(R.string.script_prep_analyzing), modifier = Modifier.weight(1f))
            }
        } else if (state.phase == PrepPhase.Reviewing || state.isReady) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = viewModel::analyze,
                enabled = state.canSubmit && serverUrl.isNotBlank()
            ) {
                Text(
                    stringResource(
                        if (state.phase == PrepPhase.Editing) R.string.script_prep_analyze
                        else R.string.script_prep_reanalyze
                    )
                )
            }
        }

        state.failure?.takeIf { state.phase == PrepPhase.Failed }?.let { FailureCard(it) }

        state.analysis?.let { analysis ->
            if (state.phase == PrepPhase.Reviewing || state.phase == PrepPhase.Ready) {
                SceneSectionTitle(stringResource(R.string.script_prep_review_title, analysis.parts.size))
                if (analysis.analyzer == "keybert") {
                    PrerequisiteHint(stringResource(R.string.script_prep_local_analysis_notice))
                }
                if (state.phase == PrepPhase.Reviewing) {
                    PrerequisiteHint(stringResource(R.string.script_prep_review_hint))
                }
                analysis.parts.forEach { PartCard(it) }
                if (state.isReady) {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Text(
                            stringResource(R.string.script_prep_ready_message),
                            modifier = Modifier.padding(14.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadyControls(state: ScriptPrepState, onConfirm: () -> Unit, onContinue: () -> Unit) {
    if (state.phase == PrepPhase.Reviewing) {
        Button(modifier = Modifier.fillMaxWidth(), onClick = onConfirm) {
            Text(stringResource(R.string.script_prep_confirm))
        }
    } else if (state.isReady) {
        Button(modifier = Modifier.fillMaxWidth(), onClick = onContinue) {
            Text(stringResource(R.string.presentation_continue))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PartCard(part: ScriptPart) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.script_prep_part_title, part.index, part.title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                stringResource(R.string.script_prep_part_body),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (part.body.isNotBlank()) {
                Text(part.body, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
            } else {
                PrerequisiteHint(stringResource(R.string.script_prep_part_body_unavailable))
            }
            Text(
                stringResource(R.string.script_prep_part_keywords),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                part.keywords.forEach { keyword ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            keyword,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FailureCard(failure: PrepFailure) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.script_prep_failed_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(failureMessage(failure), color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun phaseLabel(phase: PrepPhase): String = stringResource(
    when (phase) {
        PrepPhase.Editing -> R.string.script_prep_phase_editing
        PrepPhase.Analyzing -> R.string.script_prep_phase_analyzing
        PrepPhase.Reviewing -> R.string.script_prep_phase_reviewing
        PrepPhase.Ready -> R.string.script_prep_phase_ready
        PrepPhase.Failed -> R.string.script_prep_phase_failed
    }
)

@Composable
private fun failureMessage(failure: PrepFailure): String = when (failure) {
    PrepFailure.InvalidServerUrl -> stringResource(R.string.script_prep_error_invalid_url)
    PrepFailure.Network -> stringResource(R.string.script_prep_error_network)
    PrepFailure.Timeout -> stringResource(R.string.script_prep_error_timeout)
    is PrepFailure.InvalidFormat -> stringResource(R.string.script_prep_error_format)
    is PrepFailure.Server -> stringResource(R.string.script_prep_error_server, failure.message ?: failure.code)
}

@Composable
private fun fileErrorMessage(error: ScriptFileReader.Error): String = when (error) {
    ScriptFileReader.Error.UnsupportedType -> stringResource(R.string.script_prep_file_unsupported)
    ScriptFileReader.Error.TooLarge ->
        stringResource(R.string.script_prep_file_too_large, ScriptFileReader.MAX_BYTES / 1024)
    ScriptFileReader.Error.Empty -> stringResource(R.string.script_prep_file_empty)
    ScriptFileReader.Error.Unreadable -> stringResource(R.string.script_prep_file_unreadable)
}

/** 발표 시작 버튼. 대본 준비가 끝나기 전에는 비활성화한다. 세션 화면에서 사용. */
@Composable
fun PresentationStartSection(
    prepState: ScriptPrepState,
    onOpenPrep: () -> Unit,
    onStart: () -> Unit,
) {
    SceneSectionTitle(stringResource(R.string.presentation_section))
    DevStatusCard(
        title = stringResource(R.string.script_prep_status_title),
        lines = listOf(
            if (prepState.isReady) {
                stringResource(R.string.presentation_ready_line, prepState.analysis?.parts?.size ?: 0)
            } else {
                stringResource(R.string.presentation_not_ready_line)
            }
        )
    )
    if (!prepState.isReady) {
        PrerequisiteHint(stringResource(R.string.presentation_need_prep))
    }
    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onOpenPrep) {
        Text(stringResource(R.string.presentation_open_prep))
    }
    Button(modifier = Modifier.fillMaxWidth(), onClick = onStart, enabled = prepState.isReady) {
        Text(stringResource(R.string.presentation_continue))
    }
}
