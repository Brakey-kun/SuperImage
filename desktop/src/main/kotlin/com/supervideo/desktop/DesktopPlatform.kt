package com.supervideo.desktop

import com.supervideo.core.job.JobManifest
import com.supervideo.core.model.ModelStore
import com.supervideo.core.pipeline.PipelineGate
import com.supervideo.core.platform.OpenedSource
import com.supervideo.core.platform.Platform
import com.supervideo.core.platform.StagedSource
import com.supervideo.core.upscale.NativeLibraryLoader
import com.supervideo.core.upscale.NativeUpscaler
import com.supervideo.core.util.AppLog
import com.supervideo.core.util.moveTo
import java.awt.Desktop
import java.io.File

class DesktopPlatform : Platform {

    /** Set by both `run` and the packaged app (Compose `appResourcesRootDir`). */
    private val resourcesDir: File = File(
        System.getProperty("compose.application.resources.dir")
            ?: error("compose.application.resources.dir is not set")
    )

    override val dataDir: File = File(
        System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),
        "SuperVideo",
    ).apply { mkdirs() }

    val logDir: File = File(dataDir, "logs")

    override val nativeLoader = NativeLibraryLoader {
        System.load(File(resourcesDir, "realesrgan.dll").absolutePath)
        // MNN reports backend/driver problems on native stdout/stderr, invisible in a GUI app. The redirect
        // covers the whole process's stdout/stderr, so console.log also interleaves the app log echo.
        val consoleLog = File(logDir, CONSOLE_LOG)
        if (consoleLog.length() > MAX_CONSOLE_LOG_BYTES) {
            File(logDir, "console.1.log").let { it.delete(); consoleLog.renameTo(it) }
        }
        logDir.mkdirs()
        val redirected = NativeUpscaler.redirectNativeOutput(consoleLog.absolutePath)
        AppLog.i("Native", "Loaded realesrgan.dll; native output ${if (redirected) "-> ${consoleLog.name}" else "not redirected"}")
    }

    override val modelStore = ModelStore { model -> File(resourcesDir, "models/${model.fileName}").readBytes() }

    override val defaultPreset: String = "medium"

    override val supportsPowerPausing: Boolean = false

    override suspend fun openSource(pickedUri: String): OpenedSource {
        val file = File(pickedUri)
        return OpenedSource(file.absolutePath, file.name, file.length())
    }

    /** Desktop jobs read the original file in place; the manifest's size/mtime guard detects changes. */
    override suspend fun stageSource(pickedUri: String, jobDir: File): StagedSource {
        val file = File(pickedUri)
        return StagedSource(file.absolutePath, file.name, file.length())
    }

    override suspend fun publishOutput(output: File, manifest: JobManifest): String {
        val target = manifest.outputTarget?.let(::File)
            ?: File(File(manifest.sourcePath).parentFile, manifest.outputName)
        output.moveTo(target)
        return target.absolutePath
    }

    override fun openOutput(uri: String) {
        val file = File(uri)
        if (file.isFile && Desktop.isDesktopSupported()) {
            Thread { runCatching { Desktop.getDesktop().open(file) } }.start()
        }
    }

    override fun gate(): PipelineGate = PipelineGate.ALWAYS_RUN

    private companion object {
        const val CONSOLE_LOG = "console.log"
        const val MAX_CONSOLE_LOG_BYTES = 1L shl 20
    }
}
