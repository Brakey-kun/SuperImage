package com.supervideo.core.job

import com.supervideo.core.pipeline.JobProgress
import com.supervideo.core.settings.UpscaleSettings
import com.supervideo.core.video.VideoInfo
import kotlinx.coroutines.flow.StateFlow

/** Everything needed to create a job from the UI. */
data class JobDraft(
    val pickedUri: String,
    val displayName: String,
    val info: VideoInfo,
    val settings: UpscaleSettings,
    /** Desktop: path chosen in the save dialog; Android: null (Movies/SuperVideo). */
    val outputTarget: String? = null,
)

/** Sequential job queue; implementations run jobs in the background (WorkManager / coroutine). */
interface JobRunner {
    /** Stages the source, creates the manifest (QUEUED) and schedules it. */
    suspend fun enqueue(draft: JobDraft): JobManifest

    /** Live progress of a running or queued job. */
    fun progress(jobId: String): StateFlow<JobProgress>?

    fun cancel(jobId: String)

    /** Re-queues a CANCELLED/FAILED job; it resumes from its DONE segments. */
    fun resume(jobId: String)
}
