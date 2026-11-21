package com.rokid.renewcxrlsample.activities.script

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.renewcxrlsample.script.ScriptAnalysisClient
import com.rokid.renewcxrlsample.script.ScriptFileReader
import com.rokid.renewcxrlsample.script.ScriptPrepRepository
import com.rokid.renewcxrlsample.script.ScriptPrepState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScriptPrepViewModel(application: Application) : AndroidViewModel(application) {

    private val controller = ScriptPrepRepository.controller
    private val client = ScriptAnalysisClient()
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val state: StateFlow<ScriptPrepState> = controller.state

    private val _serverUrl = MutableStateFlow(prefs.getString(KEY_SERVER_URL, "").orEmpty())
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _fileError = MutableStateFlow<ScriptFileReader.Error?>(null)
    val fileError: StateFlow<ScriptFileReader.Error?> = _fileError.asStateFlow()

    private val _fileLoading = MutableStateFlow(false)
    val fileLoading: StateFlow<Boolean> = _fileLoading.asStateFlow()

    private var analysisJob: Job? = null

    fun onScriptChanged(text: String) {
        if (text == state.value.script) return
        analysisJob?.cancel()
        _fileError.value = null
        controller.editScript(text)
    }

    fun onServerUrlChanged(url: String) {
        _serverUrl.value = url
        prefs.edit { putString(KEY_SERVER_URL, url.trim()) }
    }

    fun loadFile(uri: Uri) {
        _fileLoading.value = true
        _fileError.value = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ScriptFileReader.read(getApplication<Application>().contentResolver, uri)
            }
            _fileLoading.value = false
            when (result) {
                is ScriptFileReader.Result.Success -> {
                    if (result.text != state.value.script) analysisJob?.cancel()
                    controller.loadScript(result.text, result.name)
                }
                is ScriptFileReader.Result.Failure -> _fileError.value = result.error
            }
        }
    }

    fun analyze() {
        if (_serverUrl.value.isBlank()) return
        val ticket = controller.beginAnalysis() ?: return
        val url = _serverUrl.value
        analysisJob = viewModelScope.launch {
            val outcome = client.analyze(url, ticket.script, ticket.revision)
            // 대본이 그사이 바뀌었으면 controller가 결과를 버린다.
            controller.completeAnalysis(ticket, outcome)
        }
    }

    fun cancelAnalysis() {
        analysisJob?.cancel()
        controller.cancelAnalysis()
    }

    fun confirmReady() {
        controller.confirmReady()
    }

    private companion object {
        const val PREFS_NAME = "script_prep"
        const val KEY_SERVER_URL = "server_url"
    }
}
