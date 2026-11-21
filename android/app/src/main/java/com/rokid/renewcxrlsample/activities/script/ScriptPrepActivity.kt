package com.rokid.renewcxrlsample.activities.script

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.rokid.renewcxrlsample.ui.feature.ScriptPrepScreen
import com.rokid.renewcxrlsample.ui.theme.RenewCXRLSampleTheme

/** 파트 1: 발표 대본 입력 → 서버 분석 → 결과 확인 → 준비 완료. 안경 연결 없이도 사용할 수 있다. */
class ScriptPrepActivity : ComponentActivity() {

    private val viewModel by viewModels<ScriptPrepViewModel>()

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::loadFile)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RenewCXRLSampleTheme {
                ScriptPrepScreen(
                    viewModel = viewModel,
                    onBack = { finish() },
                    onPickFile = {
                        pickFile.launch(com.rokid.renewcxrlsample.script.ScriptFileReader.PICKER_MIME_TYPES)
                    }
                )
            }
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ScriptPrepActivity::class.java))
        }
    }
}
