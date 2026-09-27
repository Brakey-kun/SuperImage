package com.supervideo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.supervideo.core.job.JobRepository
import com.supervideo.core.job.JobRunner
import com.supervideo.core.platform.Platform
import com.supervideo.core.preview.PreviewService
import com.supervideo.core.settings.SettingsStore
import java.nio.ByteBuffer

/** Platform-specific UI hooks implemented by the Android and desktop apps. */
interface PlatformUi {
    val versionName: String

    /** Label of the Settings action that hands the logs to the user ("Open logs folder" / "Share logs"). */
    val logsActionLabel: String

    /** Opens the logs folder (desktop) or shares the collected logs (Android). */
    fun openLogs()

    /** Returns a launcher that opens the platform video picker; [onPicked] gets (uri or path, display name). */
    @Composable
    fun rememberVideoPicker(onPicked: (uri: String, displayName: String) -> Unit): () -> Unit

    /**
     * Returns a function that prepares a job start: asks for notification permission (Android) or an
     * output file (desktop). The callback receives the output target (null = platform default), or is
     * not called when the user aborts.
     */
    @Composable
    fun rememberStartPreparation(): (defaultOutputName: String, onReady: (outputTarget: String?) -> Unit) -> Unit

    /** Converts a tightly packed RGBA buffer into an image. */
    fun rgbaToImageBitmap(buffer: ByteBuffer, width: Int, height: Int): ImageBitmap

    @Composable
    fun BackHandler(enabled: Boolean, onBack: () -> Unit)

    @Composable
    fun SystemBars(lightMode: Boolean)
}

/** Manual dependency graph shared by all screens. */
class AppGraph(
    val platform: Platform,
    val platformUi: PlatformUi,
    val settingsStore: SettingsStore,
    val jobRepository: JobRepository,
    val jobRunner: JobRunner,
) {
    val previewService = PreviewService(platform.modelStore)
}
