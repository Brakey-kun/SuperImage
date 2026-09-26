package com.supervideo.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.supervideo.core.util.Durations
import com.supervideo.ui.AppGraph
import com.supervideo.ui.AppState
import com.supervideo.ui.Screen
import com.supervideo.ui.form.Section
import com.supervideo.ui.mono.MonoButton
import com.supervideo.ui.mono.MonoButtonIcon
import com.supervideo.ui.theme.spacing
import kotlin.math.roundToInt

@Composable
fun PreviewScreen(graph: AppGraph, state: AppState) {
    val source = state.source ?: run {
        androidx.compose.runtime.LaunchedEffect(Unit) { state.screen = Screen.Home }
        return
    }
    val prepareStart = graph.platformUi.rememberStartPreparation()
    graph.platformUi.BackHandler(enabled = true) { state.screen = Screen.Home }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(MaterialTheme.spacing.level5),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 1100.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.level5),
        ) {
            Section("Frame") {
                val duration = source.info.durationUs.coerceAtLeast(1)
                Text("Time: ${Durations.timestamp(state.previewAtUs)} / ${Durations.timestamp(duration)}")
                Slider(
                    value = state.previewAtUs.toFloat(),
                    onValueChange = { state.previewAtUs = it.toLong() },
                    valueRange = 0f..duration.toFloat(),
                )
                MonoButton(onClick = state::renderPreview, enabled = !state.rendering, modifier = Modifier.fillMaxWidth()) {
                    MonoButtonIcon(Icons.Outlined.AutoAwesome, contentDescription = null)
                    Text(if (state.rendering) "Rendering…" else "Render")
                }
                if (state.rendering) CircularProgressIndicator(modifier = Modifier.size(32.dp))
                state.previewError?.let { ErrorText(it) }
            }

            state.preview?.let { images ->
                val result = images.result
                Section("Before / after") {
                    Text(
                        "Frame at ${Durations.timestamp(result.frameUs)} · ${result.width}×${result.height} → ${result.outWidth}×${result.outHeight}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    BeforeAfter(images.original, images.upscaled, result.outWidth.toFloat() / result.outHeight)
                    val estimated = com.supervideo.core.job.JobRepository.estimateFrames(source.info, state.jobSettings() ?: state.draft)
                    Text(
                        "${result.upscaleMillis} ms per frame on ${result.activeBackend.displayName} → projected " +
                            "${Durations.period(result.upscaleMillis * estimated)} for this clip ($estimated frames)",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.level3)) {
                MonoButton(onClick = { state.screen = Screen.Home }, modifier = Modifier.weight(1f)) {
                    Text("Back to settings")
                }
                MonoButton(
                    onClick = { prepareStart(state.defaultOutputName()) { target -> state.startJob(target) } },
                    enabled = !state.starting && state.trimRange().isSuccess,
                    modifier = Modifier.weight(1f),
                ) {
                    MonoButtonIcon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Text("Start with these settings")
                }
            }
        }
    }
}

/** Upscaled image with the original drawn over its left part; drag or tap to move the divider. */
@Composable
private fun BeforeAfter(original: ImageBitmap, upscaled: ImageBitmap, aspectRatio: Float) {
    var split by remember { mutableFloatStateOf(0.5f) }
    val lineColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .pointerInput(Unit) {
                detectDragGestures { change, _ -> split = (change.position.x / size.width).coerceIn(0f, 1f) }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset -> split = (offset.x / size.width).coerceIn(0f, 1f) }
            }
    ) {
        val dst = IntSize(size.width.roundToInt(), size.height.roundToInt())
        drawImage(upscaled, dstOffset = IntOffset.Zero, dstSize = dst, filterQuality = FilterQuality.Medium)
        val x = size.width * split
        clipRect(right = x) {
            // Nearest-neighbour so the original's real pixels are visible.
            drawImage(original, dstOffset = IntOffset.Zero, dstSize = dst, filterQuality = FilterQuality.None)
        }
        drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        drawLine(Color.Black.copy(alpha = 0.4f), Offset(x + 2.dp.toPx(), 0f), Offset(x + 2.dp.toPx(), size.height))
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("◀ Original", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Text("Upscaled ▶", style = MaterialTheme.typography.labelMedium)
    }
}
