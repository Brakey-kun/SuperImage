package com.supervideo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.supervideo.core.settings.ThemeMode
import com.supervideo.core.util.AppLog
import com.supervideo.ui.mono.MonoButton
import com.supervideo.ui.AppGraph
import com.supervideo.ui.form.LabeledDropdown
import com.supervideo.ui.form.Section
import com.supervideo.ui.form.SwitchRow
import com.supervideo.ui.theme.spacing

@Composable
fun SettingsScreen(graph: AppGraph) {
    val settings by graph.settingsStore.settings.collectAsState()
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
            Section("Appearance") {
                LabeledDropdown(
                    label = "Theme",
                    options = ThemeMode.entries,
                    selected = settings.theme,
                    optionText = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                    onSelect = { mode -> graph.settingsStore.update { it.copy(theme = mode) } },
                )
            }

            if (graph.platform.supportsPowerPausing) {
                Section("Power") {
                    SwitchRow(
                        label = "Pause when the device is hot",
                        checked = settings.pauseOnThermal,
                        onChange = { value -> graph.settingsStore.update { it.copy(pauseOnThermal = value) } },
                        description = "Pauses at severe thermal status, resumes once back to moderate",
                    )
                    SwitchRow(
                        label = "Only while charging",
                        checked = settings.onlyWhileCharging,
                        onChange = { value -> graph.settingsStore.update { it.copy(onlyWhileCharging = value) } },
                        description = "Applies to newly queued jobs",
                    )
                }
            }

            Text("Default job settings", style = MaterialTheme.typography.headlineSmall)
            UpscaleSettingsForm(settings.defaultUpscale) { upscale ->
                graph.settingsStore.update { it.copy(defaultUpscale = upscale) }
            }

            Section("Logs") {
                Text(
                    "Errors, backend selection and job progress are recorded here. Attach these logs when reporting a problem.",
                    style = MaterialTheme.typography.bodySmall,
                )
                KeyValue("Log folder", AppLog.logDir?.absolutePath ?: "not initialised")
                MonoButton(onClick = graph.platformUi::openLogs) {
                    Text(graph.platformUi.logsActionLabel)
                }
            }

            Section("About") {
                KeyValue("Version", graph.platformUi.versionName)
                KeyValue("Data directory", graph.platform.dataDir.absolutePath)
                Text(
                    "Based on SuperImage by Zhenxiang Chen (GPLv3). Models: Real-ESRGAN by Xintao Wang et al. " +
                        "Inference: Alibaba MNN. Video: FFmpeg via bytedeco JavaCPP.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
