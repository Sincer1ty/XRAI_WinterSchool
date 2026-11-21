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
import com.rokid.renewcxrlsample.ui.feature.PresentationScreen
import com.rokid.renewcxrlsample.ui.theme.RenewCXRLSampleTheme

/** 발표 진행 화면. 안경 화면 모드에서 안경 화면을 연 뒤, 대본 준비가 끝나면 연다. */
class PresentationActivity : ComponentActivity() {

    private val viewModel by viewModels<PresentationViewModel>()
    private var pendingOnDevice = true

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.start(pendingOnDevice)
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
                    onBack = { finish() },
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

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, PresentationActivity::class.java))
        }
    }
}
