package com.supervideo.core.job

import com.supervideo.core.pipeline.JobProgress
import com.supervideo.core.pipeline.VideoUpscalePipeline
import com.supervideo.core.platform.Platform
import com.supervideo.core.upscale.NativeLibraries
import com.supervideo.core.video.VideoProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Platform-independent job creation and execution used by the Android and desktop runners. */
class JobService(
    private val platform: Platform,
    val repository: JobRepository,
) {
    private val pipeline = VideoUpscalePipeline(repository, platform.modelStore)

    /** Stages the source into a new job directory and persists a QUEUED manifest. */
    suspend fun create(draft: JobDraft): JobManifest = withContext(Dispatchers.IO) {
        val jobId = repository.newJobId()
        val jobDir = repository.jobDir(jobId)
        try {
            val staged = platform.stageSource(draft.pickedUri, jobDir)
            // Re-probe the staged file: size/mtime guard resumes against a changed source.
            val info = VideoProbe.probe(staged.path)
            repository.create(jobId, staged.path, draft.displayName, info, draft.settings, draft.outputTarget)
        } catch (e: Throwable) {
            jobDir.deleteRecursively()
            throw e
        }
    }

    /** Runs (or resumes) [jobId] to completion. */
    suspend fun execute(jobId: String, progress: MutableStateFlow<JobProgress>, cancel: AtomicBoolean): JobManifest {
        val manifest = repository.get(jobId) ?: throw IllegalStateException("Unknown job $jobId")
        NativeLibraries.ensureLoaded(platform.nativeLoader)
        return pipeline.run(manifest, platform.gate(), progress, cancel) { output, m ->
            platform.publishOutput(output, m)
        }
    }
}
