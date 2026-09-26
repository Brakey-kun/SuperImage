package com.supervideo.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.supervideo.core.job.JobRepository
import com.supervideo.core.job.JobService
import com.supervideo.core.settings.AppSettings
import com.supervideo.core.settings.SettingsStore
import com.supervideo.core.settings.UpscaleSettings
import com.supervideo.ui.App
import com.supervideo.ui.AppGraph
import java.awt.Frame

fun main() {
    val platform = DesktopPlatform()
    val repository = JobRepository(platform.dataDir)
    val settingsStore = SettingsStore(
        platform.dataDir,
        defaults = AppSettings(defaultUpscale = UpscaleSettings(preset = platform.defaultPreset)),
    )
    val runner = DesktopJobRunner(JobService(platform, repository))
    var frame: Frame? = null
    val graph = AppGraph(platform, DesktopPlatformUi { frame }, settingsStore, repository, runner)

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "SuperVideo",
            state = rememberWindowState(width = 1100.dp, height = 760.dp),
        ) {
            frame = window
            App(graph)
        }
    }
}
