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
import com.supervideo.core.util.AppLog
import java.awt.Frame

fun main() {
    val platform = DesktopPlatform()
    AppLog.init(
        platform.logDir,
        header = mapOf(
            "App" to "SuperVideo desktop 0.1.0",
            "OS" to "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})",
            "Java" to "${System.getProperty("java.vendor")} ${System.getProperty("java.version")}",
            "CPUs" to Runtime.getRuntime().availableProcessors().toString(),
            "Max heap" to "${Runtime.getRuntime().maxMemory() shr 20} MB",
            "Data dir" to platform.dataDir.absolutePath,
        ),
    )
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
