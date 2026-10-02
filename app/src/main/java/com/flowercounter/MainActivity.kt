package com.flowercounter

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowercounter.ui.CameraScreen
import com.flowercounter.ui.FlowerCounterTheme
import com.flowercounter.ui.HomeScreen
import com.flowercounter.ui.ResultScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val viewModel: FlowerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FlowerCounterTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (FlowerCounterApp.openCvReady) {
                        FlowerCounterRoot(viewModel)
                    } else {
                        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("OpenCV failed to load on this device, so flowers can't be counted.")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FlowerCounterRoot(vm: FlowerViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val source by vm.source.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val params by vm.params.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val cameraOpen by vm.cameraOpen.collectAsStateWithLifecycle()
    val sample by vm.sample.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadSample { loadSampleBitmap(context.applicationContext) } }
    BackHandler(enabled = source != null || cameraOpen) { vm.back() }

    val pickPhoto: (Uri) -> Unit = { uri ->
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { loadWorkingBitmap(context, uri) } }
                .onSuccess(vm::setSource)
                .onFailure { vm.showError("Could not open the photo: ${it.message}") }
        }
    }

    val current = source
    when {
        current != null -> ResultScreen(
            source = current,
            result = result,
            busy = busy,
            params = params,
            onParamsChange = vm::updateParams,
            onReset = vm::resetParams,
            onCamera = vm::openCamera,
        )
        cameraOpen -> CameraScreen(
            onImageReady = vm::setSource,
            onPickPhoto = pickPhoto,
            onError = vm::showError,
        )
        else -> HomeScreen(
            sample = sample,
            onCountSample = vm::countSample,
            onOpenCamera = vm::openCamera,
            onPickPhoto = pickPhoto,
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = vm::clearError,
            confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } },
            title = { Text("Something went wrong") },
            text = { Text(message) },
        )
    }
}
