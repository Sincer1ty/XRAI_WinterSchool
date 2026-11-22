package com.rokid.renewcxrlsample.activities.script

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.renewcxrlsample.script.AnalysisTicket
import com.rokid.renewcxrlsample.script.ScriptAnalysisClient
import com.rokid.renewcxrlsample.script.ScriptFileReader
import com.rokid.renewcxrlsample.script.ScriptPrepRepository
import com.rokid.renewcxrlsample.script.ScriptPrepState
import com.rokid.renewcxrlsample.script.RequestTiming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScriptPrepViewModel(application: Application) : AndroidViewModel(application) {

    private val controller = ScriptPrepRepository.controller
    private val _lastTiming = MutableStateFlow<RequestTiming?>(null)
    /** 마지막 분석 요청의 AI 대기·서버 처리·네트워크 시간. */
    val lastTiming: StateFlow<RequestTiming?> = _lastTiming.asStateFlow()
    private val client = ScriptAnalysisClient(onTiming = { timing ->
        Log.i("ServerTiming", timing.logLine())
        _lastTiming.value = timing
    })
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val state: StateFlow<ScriptPrepState> = controller.state

    private val _serverUrl = MutableStateFlow(prefs.getString(KEY_SERVER_URL, "").orEmpty())
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _fileError = MutableStateFlow<ScriptFileReader.Error?>(null)
    val fileError: StateFlow<ScriptFileReader.Error?> = _fileError.asStateFlow()

    private val _fileLoading = MutableStateFlow(false)
    val fileLoading: StateFlow<Boolean> = _fileLoading.asStateFlow()

    private var analysisJob: Job? = null
    private var analysisTicket: AnalysisTicket? = null
    private var fileLoadJob: Job? = null
    private var fileLoadVersion = 0L

    fun onScriptChanged(text: String) {
        if (text == state.value.script) return
        cancelFileLoad()
        analysisJob?.cancel()
        _fileError.value = null
        controller.editScript(text)
    }

    fun onServerUrlChanged(url: String) {
        _serverUrl.value = url
        prefs.edit { putString(KEY_SERVER_URL, url.trim()) }
        if (controller.onServerUrlChanged(url)) analysisJob?.cancel()
    }

    fun loadFile(uri: Uri) {
        fileLoadJob?.cancel()
        val version = ++fileLoadVersion
        val revision = state.value.revision
        _fileLoading.value = true
        _fileError.value = null
        fileLoadJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    ScriptFileReader.read(getApplication<Application>().contentResolver, uri)
                }
                if (version != fileLoadVersion || revision != state.value.revision) return@launch
                when (result) {
                    is ScriptFileReader.Result.Success -> {
                        val changed = result.text != state.value.script
                        if (controller.loadScript(result.text, result.name, revision) && changed) {
                            analysisJob?.cancel()
                        }
                    }
                    is ScriptFileReader.Result.Failure -> _fileError.value = result.error
                }
            } finally {
                if (version == fileLoadVersion) {
                    _fileLoading.value = false
                    fileLoadJob = null
                }
            }
        }
    }

    private fun cancelFileLoad() {
        fileLoadVersion++
        fileLoadJob?.cancel()
        fileLoadJob = null
        _fileLoading.value = false
    }

    fun analyze() {
        if (_serverUrl.value.isBlank()) return
        val url = _serverUrl.value
        val ticket = controller.beginAnalysis(url) ?: return
        analysisTicket = ticket
        analysisJob = viewModelScope.launch {
            try {
                val outcome = client.analyze(url, ticket.script, ticket.revision)
                controller.completeAnalysis(ticket, outcome)
            } finally {
                // 이전 작업의 취소·종료가 새 요청을 정리하지 않도록 요청 번호를 확인한다.
                controller.cancelAnalysis(ticket)
                if (analysisTicket == ticket) {
                    analysisTicket = null
                    analysisJob = null
                }
            }
        }
    }

    fun cancelAnalysis() {
        analysisJob?.cancel()
        controller.cancelAnalysis()
    }

    override fun onCleared() {
        analysisTicket?.let { controller.cancelAnalysis(it) }
        analysisTicket = null
        analysisJob?.cancel()
        analysisJob = null
        cancelFileLoad()
        super.onCleared()
    }

    fun confirmReady() {
        controller.confirmReady()
    }

    private companion object {
        const val PREFS_NAME = "script_prep"
        const val KEY_SERVER_URL = "server_url"
    }
}
