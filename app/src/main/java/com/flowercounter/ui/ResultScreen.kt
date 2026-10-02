package com.flowercounter.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flowercounter.FlowerParams
import com.flowercounter.FlowerResult
import kotlin.math.roundToInt

@Composable
fun ResultScreen(
    source: Bitmap,
    result: FlowerResult?,
    busy: Boolean,
    params: FlowerParams,
    onParamsChange: ((FlowerParams) -> FlowerParams) -> Unit,
    onReset: () -> Unit,
    onCamera: () -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
        ) {
            ZoomableImage(result?.bitmap ?: source)

            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(
                        text = if (result == null) "Counting…" else "≈ ${result.count} flowers",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (result != null && params.estimateClusters) {
                        Text(
                            "${result.blobCount} single + ~${result.clusterEstimate} in clusters",
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(24.dp),
                    strokeWidth = 3.dp,
                    color = Color.White,
                )
            }
        }

        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            LabeledSlider(
                label = "White Sensitivity",
                valueText = "${params.whiteSensitivity}  (V≥${params.minBrightness}, S≤${params.maxSaturation})",
                value = params.whiteSensitivity.toFloat(),
                range = 0f..100f,
                steps = 99,
                onChange = { v -> onParamsChange { it.copy(whiteSensitivity = v.roundToInt()) } },
            )
            LabeledSlider(
                label = "Min Blob Size",
                valueText = "${params.minBlobArea} px²",
                value = params.minBlobArea.toFloat(),
                range = 10f..600f,
                steps = 0,
                onChange = { v -> onParamsChange { it.copy(minBlobArea = v.roundToInt()) } },
            )
            LabeledSlider(
                label = "Blur Amount",
                valueText = if (params.blurAmount == 0) "off" else "${params.blurKernel}×${params.blurKernel}",
                value = params.blurAmount.toFloat(),
                range = 0f..10f,
                steps = 9,
                onChange = { v -> onParamsChange { it.copy(blurAmount = v.roundToInt()) } },
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LabeledSwitch("Clusters", params.estimateClusters) { on ->
                    onParamsChange { it.copy(estimateClusters = on) }
                }
                LabeledSwitch("Show mask", params.showMask) { on ->
                    onParamsChange { it.copy(showMask = on) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) { Text("Reset sliders") }
                Button(onClick = onCamera, modifier = Modifier.weight(1f)) { Text("Camera") }
            }
        }
    }
}

/** Pinch to zoom / drag to pan, double-tap to reset - the flowers are tiny on a phone screen. */
@Composable
private fun ZoomableImage(bitmap: Bitmap) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }

    Image(
        bitmap = image,
        contentDescription = "Detected flowers",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale = 1f
                    offset = Offset.Zero
                })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(valueText, style = MaterialTheme.typography.labelMedium)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
