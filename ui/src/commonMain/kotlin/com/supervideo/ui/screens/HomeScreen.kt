package com.supervideo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.supervideo.core.util.Durations
import com.supervideo.core.video.VideoInfo
import com.supervideo.ui.AppGraph
import com.supervideo.ui.AppState
import com.supervideo.ui.Screen
import com.supervideo.ui.form.LabeledTextField
import com.supervideo.ui.form.Section
import com.supervideo.ui.mono.MonoButton
import com.supervideo.ui.mono.MonoButtonIcon
import com.supervideo.ui.theme.spacing

@Composable
fun HomeScreen(graph: AppGraph, state: AppState) {
    val pickVideo = graph.platformUi.rememberVideoPicker(state::onPicked)
    val prepareStart = graph.platformUi.rememberStartPreparation()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(MaterialTheme.spacing.level5),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.level5),
        ) {
            MonoButton(onClick = pickVideo, enabled = !state.probing, modifier = Modifier.fillMaxWidth()) {
                MonoButtonIcon(Icons.Outlined.VideoFile, contentDescription = null)
                Text(if (state.source == null) "Pick video" else "Pick another video")
            }
            if (state.probing) CircularProgressIndicator(modifier = Modifier.size(32.dp))
            state.homeError?.let { ErrorText(it) }

            val source = state.source
            if (source != null) {
                Section(source.displayName) { VideoInfoRows(source.info) }

                Section("Trim") {
                    val trimError = state.trimRange().exceptionOrNull()?.message
                    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.level3)) {
                        LabeledTextField(
                            label = "Start (m:ss.mmm)",
                            value = state.trimStart,
                            onChange = { state.trimStart = it },
                            placeholder = "0:00.000",
                            modifier = Modifier.weight(1f),
                        )
                        LabeledTextField(
                            label = "End (m:ss.mmm)",
                            value = state.trimEnd,
                            onChange = { state.trimEnd = it },
                            placeholder = Durations.timestamp(source.info.durationUs),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    trimError?.let { ErrorText(it) }
                }

                UpscaleSettingsForm(state.draft) { state.draft = it }

                state.outputSize()?.let { (w, h) ->
                    Text("Output: ${w}×$h", style = MaterialTheme.typography.titleMedium)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.level3)) {
                    MonoButton(onClick = { state.screen = Screen.Preview }, modifier = Modifier.weight(1f)) {
                        MonoButtonIcon(Icons.Outlined.AutoAwesome, contentDescription = null)
                        Text("Preview frame")
                    }
                    MonoButton(
                        onClick = { prepareStart(state.defaultOutputName()) { target -> state.startJob(target) } },
                        enabled = !state.starting && state.trimRange().isSuccess,
                        modifier = Modifier.weight(1f),
                    ) {
                        MonoButtonIcon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Text(if (state.starting) "Preparing…" else "Start")
                    }
                }
            }
        }
    }
}

@Composable
fun VideoInfoRows(info: VideoInfo) {
    KeyValue("Resolution", "${info.displayWidth}×${info.displayHeight}" + if (info.rotationDegrees != 0) " (rotated ${info.rotationDegrees}°)" else "")
    KeyValue("Frame rate", "%.3f fps".format(info.fps))
    KeyValue("Duration", Durations.timestamp(info.durationUs))
    KeyValue("Frames (est.)", info.estimatedTotalFrames.toString())
    KeyValue("Video codec", info.videoCodecName)
    KeyValue("Audio", info.audioStreams.joinToString { it.codecName }.ifEmpty { "none" })
}

@Composable
fun ErrorText(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}
