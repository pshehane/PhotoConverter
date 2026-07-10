package net.shehane.photoconverter

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import net.shehane.photoconverter.core.AnalysisResult
import net.shehane.photoconverter.core.ConversionResult
import net.shehane.photoconverter.core.Pipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val running: Boolean = false,
    val phaseLabel: String? = null,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val analysis: AnalysisResult? = null,
    val heifToJpeg: ConversionResult? = null,
    val jpegToHeif: ConversionResult? = null,
    val heifToAvif: ConversionResult? = null,
    val jpegToAvif: ConversionResult? = null,
    val statusMessage: String? = null,
)

class PipelineViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val pipeline = Pipeline(
        app,
        onLog = { android.util.Log.i("HEIFConv", it) },
        onProgress = { phase, done, total ->
            _state.update { it.copy(phaseLabel = phase, progressDone = done, progressTotal = total) }
        },
    )

    fun start(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.running) return
        _state.value = UiState(running = true, statusMessage = "Processing ${uris.size} images…")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val analysis = pipeline.analyze(uris)
                _state.update { it.copy(analysis = analysis) }

                val h2j = pipeline.convertHeifToJpeg(analysis.heifs)
                _state.update { it.copy(heifToJpeg = h2j) }

                val j2h = pipeline.convertJpegToHeif(analysis.jpegs)
                _state.update { it.copy(jpegToHeif = j2h) }

                val h2a = pipeline.convertHeifToAvif(analysis.heifs)
                _state.update { it.copy(heifToAvif = h2a) }

                val j2a = pipeline.convertJpegToAvif(analysis.jpegs)
                _state.update { it.copy(jpegToAvif = j2a) }

                pipeline.writeReport(analysis, h2j, j2h, h2a, j2a)
                _state.update {
                    it.copy(
                        running = false, phaseLabel = null,
                        statusMessage = "Done. Temp files in ${pipeline.store.root.absolutePath}",
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(running = false, phaseLabel = null, statusMessage = "Failed: ${t.message}")
                }
            }
        }
    }

    fun cleanup() {
        if (_state.value.running) return
        viewModelScope.launch(Dispatchers.IO) {
            val (files, bytes) = pipeline.store.cleanup()
            _state.update {
                UiState(statusMessage = "Cleanup: removed $files files (${bytes / 1024} KiB)")
            }
        }
    }
}
