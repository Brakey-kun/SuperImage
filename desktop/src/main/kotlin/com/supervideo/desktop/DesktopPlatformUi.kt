package com.supervideo.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.supervideo.ui.PlatformUi
import com.supervideo.core.util.AppLog
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.ByteBuffer

class DesktopPlatformUi(private val window: () -> Frame?) : PlatformUi {

    override val versionName: String = "0.1.0"

    override val logsActionLabel: String = "Open logs folder"

    override fun openLogs() {
        val dir = AppLog.logDir ?: return
        if (Desktop.isDesktopSupported()) {
            Thread { runCatching { Desktop.getDesktop().open(dir) } }.start()
        }
    }

    @Composable
    override fun rememberVideoPicker(onPicked: (uri: String, displayName: String) -> Unit): () -> Unit = {
        val dialog = FileDialog(window(), "Pick a video", FileDialog.LOAD).apply {
            file = "*.mp4;*.mkv;*.mov;*.webm;*.avi;*.m4v"
            setFilenameFilter { _, name -> VIDEO_EXTENSIONS.any { name.lowercase().endsWith(it) } }
            isVisible = true
        }
        val directory = dialog.directory
        val name = dialog.file
        if (directory != null && name != null) {
            onPicked(File(directory, name).absolutePath, name)
        }
    }

    @Composable
    override fun rememberStartPreparation(): (String, (String?) -> Unit) -> Unit = { defaultName, onReady ->
        val dialog = FileDialog(window(), "Save upscaled video", FileDialog.SAVE).apply {
            file = defaultName
            isVisible = true
        }
        val directory = dialog.directory
        val name = dialog.file
        if (directory != null && name != null) {
            val fileName = if (name.lowercase().endsWith(".mp4")) name else "$name.mp4"
            onReady(File(directory, fileName).absolutePath)
        }
    }

    override fun rgbaToImageBitmap(buffer: ByteBuffer, width: Int, height: Int): ImageBitmap {
        val bytes = ByteArray(width * height * 4)
        buffer.duplicate().apply { clear() }.get(bytes)
        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE))
        bitmap.installPixels(bytes)
        return bitmap.asComposeImageBitmap()
    }

    @Composable
    override fun BackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

    @Composable
    override fun SystemBars(lightMode: Boolean) = Unit

    private companion object {
        val VIDEO_EXTENSIONS = listOf(".mp4", ".mkv", ".mov", ".webm", ".avi", ".m4v")
    }
}
