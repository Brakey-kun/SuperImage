package com.supervideo.desktop

import com.supervideo.core.job.JobManifest
import com.supervideo.core.model.ModelStore
import com.supervideo.core.pipeline.PipelineGate
import com.supervideo.core.platform.OpenedSource
import com.supervideo.core.platform.Platform
import com.supervideo.core.platform.StagedSource
import com.supervideo.core.upscale.NativeLibraryLoader
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

    override val nativeLoader = NativeLibraryLoader {
        System.load(File(resourcesDir, "realesrgan.dll").absolutePath)
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
}
