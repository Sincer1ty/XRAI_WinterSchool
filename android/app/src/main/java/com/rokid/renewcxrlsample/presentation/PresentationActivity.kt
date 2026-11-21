package com.rokid.renewcxrlsample.presentation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.rokid.renewcxrlsample.R
import com.rokid.renewcxrlsample.activities.script.ScriptPrepActivity
import com.rokid.renewcxrlsample.activities.main.MainActivity
import com.rokid.renewcxrlsample.ui.feature.PresentationScreen
import com.rokid.renewcxrlsample.ui.theme.RenewCXRLSampleTheme

/** 대본 준비 다음 페이지: 안경 화면 열기 → 연결 확인 → 발표 시작. */
class PresentationActivity : ComponentActivity() {

    private val viewModel by viewModels<PresentationViewModel>()
    private var pendingOnDevice = true

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && !isFinishing && !isDestroyed) viewModel.start(pendingOnDevice)
        else Toast.makeText(this, R.string.presentation_mic_denied, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 발표 중 폰 화면이 꺼져 처리가 늦어지지 않게 한다.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            RenewCXRLSampleTheme {
                PresentationScreen(
                    viewModel = viewModel,
                    onBack = { viewModel.stop(); finish() },
                    onReviewScript = {
                        viewModel.stop()
                        ScriptPrepActivity.start(this)
                    },
                    onConnectGlasses = {
                        viewModel.stop()
                        startActivity(Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            putExtra(EXTRA_CONTINUE_PRESENTATION, true)
                        })
                    },
                    onStart = { onDevice ->
                        pendingOnDevice = onDevice
                        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) viewModel.start(onDevice)
                        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) viewModel.stop()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CONTINUE_PRESENTATION = "continue_presentation"

        fun start(context: Context) {
            context.startActivity(Intent(context, PresentationActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
    }
}
