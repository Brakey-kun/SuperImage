package com.supervideo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.supervideo.core.job.JobDraft
import com.supervideo.core.job.JobRepository
import com.supervideo.core.preview.PreviewResult
import com.supervideo.core.settings.UpscaleSettings
import com.supervideo.core.util.Durations
import com.supervideo.core.video.VideoInfo
import com.supervideo.core.video.VideoProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen(val title: String) { Home("Upscale"), Preview("Preview"), Jobs("Jobs"), Settings("Settings") }

/**
 * A picked and probed video. Every FFmpeg use opens it anew through [com.supervideo.core.platform.Platform.openSource]:
 * Android descriptors share their file offset, so one open descriptor cannot back two demuxers.
 */
class PickedSource(val uri: String, val displayName: String, val info: VideoInfo)

class PreviewImages(val result: PreviewResult, val original: ImageBitmap, val upscaled: ImageBitmap)

/** UI state that survives navigation between screens. */
class AppState(private val graph: AppGraph, private val scope: CoroutineScope) {
    var screen by mutableStateOf(Screen.Home)

    // Home
    var source by mutableStateOf<PickedSource?>(null)
        private set
    var draft by mutableStateOf(graph.settingsStore.settings.value.defaultUpscale)
    var trimStart by mutableStateOf("")
    var trimEnd by mutableStateOf("")
    var probing by mutableStateOf(false)
        private set
    var starting by mutableStateOf(false)
        private set
    var homeError by mutableStateOf<String?>(null)

    // Preview
    var previewAtUs by mutableStateOf(0L)
    var preview by mutableStateOf<PreviewImages?>(null)
        private set
    var rendering by mutableStateOf(false)
        private set
    var previewError by mutableStateOf<String?>(null)
    private var previewJob: Job? = null

    fun onPicked(uri: String, displayName: String) {
        probing = true
        homeError = null
        scope.launch {
            try {
                val picked = withContext(Dispatchers.IO) {
                    graph.platform.openSource(uri).use { opened ->
                        PickedSource(uri, displayName.ifBlank { opened.displayName }, VideoProbe.probe(opened.path))
                    }
                }
                source = picked
                trimStart = ""
                trimEnd = ""
                preview = null
                previewAtUs = picked.info.durationUs / 2
            } catch (e: Throwable) {
                homeError = "Cannot open video: ${e.message}"
            } finally {
                probing = false
            }
        }
    }

    /** Parsed trim range, or an error message. */
    fun trimRange(): Result<Pair<Long?, Long?>> {
        val info = source?.info ?: return Result.success(null to null)
        val start = trimStart.takeIf { it.isNotBlank() }?.let {
            Durations.parseTimestamp(it) ?: return Result.failure(IllegalArgumentException("Invalid start time"))
        }
        val end = trimEnd.takeIf { it.isNotBlank() }?.let {
            Durations.parseTimestamp(it) ?: return Result.failure(IllegalArgumentException("Invalid end time"))
        }
        val effectiveStart = start ?: 0L
        val effectiveEnd = end ?: info.durationUs
        if (effectiveStart < 0 || effectiveStart >= effectiveEnd || (info.durationUs > 0 && effectiveEnd > info.durationUs)) {
            return Result.failure(IllegalArgumentException("Trim must satisfy 0 ≤ start < end ≤ ${Durations.timestamp(info.durationUs)}"))
        }
        return Result.success(start to end)
    }

    /** Settings for the job, including the trim range. */
    fun jobSettings(): UpscaleSettings? {
        val (start, end) = trimRange().getOrNull() ?: return null
        return draft.copy(trimStartUs = start, trimEndUs = end)
    }

    fun outputSize(): Pair<Int, Int>? = source?.let { JobRepository.outputSize(it.info, draft) }

    fun defaultOutputName(): String =
        source?.let { JobRepository.outputName(it.displayName, draft) } ?: "output.mp4"

    fun startJob(outputTarget: String?) {
        val picked = source ?: return
        val settings = jobSettings() ?: run {
            homeError = trimRange().exceptionOrNull()?.message
            return
        }
        starting = true
        homeError = null
        scope.launch {
            try {
                graph.jobRunner.enqueue(JobDraft(picked.uri, picked.displayName, picked.info, settings, outputTarget))
                screen = Screen.Jobs
            } catch (e: Throwable) {
                homeError = "Cannot start job: ${e.message}"
            } finally {
                starting = false
            }
        }
    }

    fun renderPreview() {
        val picked = source ?: return
        previewJob?.cancel()
        rendering = true
        previewError = null
        previewJob = scope.launch {
            try {
                graph.platform.nativeLoader.let { com.supervideo.core.upscale.NativeLibraries.ensureLoaded(it) }
                val result = graph.platform.openSource(picked.uri).use { opened ->
                    graph.previewService.render(opened.path, picked.info, draft, previewAtUs)
                }
                val images = withContext(Dispatchers.Default) {
                    PreviewImages(
                        result,
                        graph.platformUi.rgbaToImageBitmap(result.originalRgba, result.width, result.height),
                        graph.platformUi.rgbaToImageBitmap(result.upscaledRgba, result.outWidth, result.outHeight),
                    )
                }
                preview = images
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                previewError = e.message ?: e.javaClass.simpleName
            } finally {
                rendering = false
            }
        }
    }
}
