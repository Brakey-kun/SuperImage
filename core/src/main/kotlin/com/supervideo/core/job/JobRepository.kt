package com.supervideo.core.job

import com.supervideo.core.settings.UpscaleSettings
import com.supervideo.core.util.AppJson
import com.supervideo.core.util.writeTextAtomically
import com.supervideo.core.video.VideoInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** Job manifests stored under `<dataDir>/jobs/<jobId>/manifest.json`. */
class JobRepository(dataDir: File) {
    val jobsDir = File(dataDir, "jobs").apply { mkdirs() }
    private val state = MutableStateFlow(scan())

    /** All jobs, newest first; re-emits on every [save]/[delete]. */
    fun observe(): StateFlow<List<JobManifest>> = state.asStateFlow()

    fun list(): List<JobManifest> = state.value

    fun get(jobId: String): JobManifest? = state.value.firstOrNull { it.jobId == jobId }

    fun jobDir(jobId: String) = File(jobsDir, jobId)

    /** Reserves a new job id and its (empty) directory. */
    fun newJobId(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        while (true) {
            val id = "$stamp-%04x".format(Random.nextInt(0x10000))
            val dir = jobDir(id)
            if (dir.mkdirs()) return id
        }
    }

    fun create(
        jobId: String,
        sourcePath: String,
        sourceName: String,
        info: VideoInfo,
        settings: UpscaleSettings,
        outputTarget: String?,
    ): JobManifest {
        val (outWidth, outHeight) = outputSize(info, settings)
        val manifest = JobManifest(
            jobId = jobId,
            createdAt = System.currentTimeMillis(),
            sourcePath = sourcePath,
            sourceName = sourceName,
            source = info,
            settings = settings,
            outputName = outputName(sourceName, settings),
            outputWidth = outWidth,
            outputHeight = outHeight,
            estimatedFrames = estimateFrames(info, settings),
            outputTarget = outputTarget,
        )
        save(manifest)
        return manifest
    }

    @Synchronized
    fun save(manifest: JobManifest) {
        File(jobDir(manifest.jobId), MANIFEST).writeTextAtomically(AppJson.encodeToString(JobManifest.serializer(), manifest))
        state.value = (state.value.filterNot { it.jobId == manifest.jobId } + manifest).sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun delete(jobId: String) {
        jobDir(jobId).deleteRecursively()
        state.value = state.value.filterNot { it.jobId == jobId }
    }

    private fun scan(): List<JobManifest> =
        (jobsDir.listFiles() ?: emptyArray()).mapNotNull { dir ->
            val file = File(dir, MANIFEST)
            if (!file.isFile) return@mapNotNull null
            try {
                AppJson.decodeFromString(JobManifest.serializer(), file.readText())
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.createdAt }

    companion object {
        const val MANIFEST = "manifest.json"

        /** Encoded output size: model-native unless [UpscaleSettings.outputHeight] is set (aspect kept, even). */
        fun outputSize(info: VideoInfo, settings: UpscaleSettings): Pair<Int, Int> {
            val nativeW = info.displayWidth * settings.model.scale
            val nativeH = info.displayHeight * settings.model.scale
            val target = settings.outputHeight ?: return even(nativeW) to even(nativeH)
            val height = even(target)
            val width = even(Math.round(nativeW.toDouble() * height / nativeH).toInt())
            return width to height
        }

        private fun even(v: Int) = (v / 2 * 2).coerceAtLeast(2)

        fun outputName(sourceName: String, settings: UpscaleSettings): String =
            sourceName.substringBeforeLast('.') + "_" + settings.model.id + ".mp4"

        /** Frames expected in the (trimmed) job; the pipeline's count is authoritative afterwards. */
        fun estimateFrames(info: VideoInfo, settings: UpscaleSettings): Long {
            val total = info.estimatedTotalFrames
            if (info.durationUs <= 0) return total
            val start = settings.trimStartUs ?: 0L
            val end = settings.trimEndUs ?: info.durationUs
            val fraction = ((end - start).toDouble() / info.durationUs).coerceIn(0.0, 1.0)
            return (total * fraction).toLong().coerceAtLeast(1)
        }
    }
}
