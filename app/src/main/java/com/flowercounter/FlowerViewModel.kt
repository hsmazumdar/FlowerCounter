/*
 * Flower Counter
 * Himanshu S Mazumdar
 * Dharmsinh Desai University, Nadiad, INDIA
 */

package com.flowercounter

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FlowerViewModel : ViewModel() {

    private val _params = MutableStateFlow(FlowerParams())
    val params: StateFlow<FlowerParams> = _params.asStateFlow()

    private val _source = MutableStateFlow<Bitmap?>(null)
    val source: StateFlow<Bitmap?> = _source.asStateFlow()

    private val _result = MutableStateFlow<FlowerResult?>(null)
    val result: StateFlow<FlowerResult?> = _result.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** True while the live camera is the screen to return to (vs. the start page). */
    private val _cameraOpen = MutableStateFlow(false)
    val cameraOpen: StateFlow<Boolean> = _cameraOpen.asStateFlow()

    private val _sample = MutableStateFlow<Bitmap?>(null)
    val sample: StateFlow<Bitmap?> = _sample.asStateFlow()

    fun loadSample(load: () -> Bitmap) {
        if (_sample.value != null) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching(load) }
                .onSuccess { _sample.value = it }
                .onFailure { _error.value = "Could not load the sample photo: ${it.message}" }
        }
    }

    fun countSample() {
        _sample.value?.let { setSource(it) }
    }

    fun openCamera() {
        _source.value = null
        _cameraOpen.value = true
    }

    /** Back from results returns to wherever the photo came from; back from the camera goes home. */
    fun back(): Boolean = when {
        _source.value != null -> { _source.value = null; true }
        _cameraOpen.value -> { _cameraOpen.value = false; true }
        else -> false
    }

    init {
        // Re-run the pipeline whenever the image or any slider changes. The short debounce
        // plus collectLatest keeps slider drags responsive: stale runs are simply dropped.
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            combine(_source, _params) { src, p -> src to p }
                .debounce(60)
                .collectLatest { (src, p) ->
                    if (src == null) {
                        _result.value = null
                        _busy.value = false
                        return@collectLatest
                    }
                    _busy.value = true
                    val r = withContext(Dispatchers.Default) {
                        runCatching { FlowerCounter.count(src, p) }
                    }
                    r.onSuccess { _result.value = it }
                        .onFailure { _error.value = "Processing failed: ${it.message}" }
                    _busy.value = false
                }
        }
    }

    fun updateParams(transform: (FlowerParams) -> FlowerParams) = _params.update(transform)

    fun resetParams() {
        _params.value = FlowerParams()
    }

    /** Safe to call from any thread. [bitmap] must already be oriented and working-sized. */
    fun setSource(bitmap: Bitmap) {
        _result.value = null
        _source.value = bitmap
    }

    fun showError(message: String) {
        _error.value = message
    }

    fun clearError() {
        _error.value = null
    }
}
