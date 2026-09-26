package com.supervideo.core.platform

import com.supervideo.core.job.JobManifest
import com.supervideo.core.model.ModelStore
import com.supervideo.core.pipeline.PipelineGate
import com.supervideo.core.upscale.NativeLibraryLoader
import java.io.Closeable
import java.io.File

/** A picked video opened for reading by FFmpeg (a file path, or `fd:<n>` for an open descriptor on Android). */
class OpenedSource(
    val path: String,
    val displayName: String,
    val sizeBytes: Long,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() = onClose()
}

class StagedSource(val path: String, val displayName: String, val sizeBytes: Long)

interface Platform {
    val dataDir: File
    val nativeLoader: NativeLibraryLoader
    val modelStore: ModelStore
    val defaultPreset: String
    /** Whether thermal/battery pausing settings apply on this platform. */
    val supportsPowerPausing: Boolean

    /** Opens a picked uri/path for probing and previews. */
    suspend fun openSource(pickedUri: String): OpenedSource

    /** Makes the picked source available to a job for its whole lifetime (copy into [jobDir] or keep path). */
    suspend fun stageSource(pickedUri: String, jobDir: File): StagedSource

    /** Moves the muxed [output] of [manifest] to its final location; returns the uri/path shown to the user. */
    suspend fun publishOutput(output: File, manifest: JobManifest): String

    /** Opens a published output in the system viewer. */
    fun openOutput(uri: String)

    fun gate(): PipelineGate
}
