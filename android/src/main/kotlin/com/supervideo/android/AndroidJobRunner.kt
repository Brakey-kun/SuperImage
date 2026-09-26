package com.supervideo.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.supervideo.core.job.JobDraft
import com.supervideo.core.job.JobManifest
import com.supervideo.core.job.JobRunner
import com.supervideo.core.job.JobService
import com.supervideo.core.job.JobStatus
import com.supervideo.core.pipeline.JobProgress
import com.supervideo.core.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Sequential queue backed by one unique WorkManager chain; each job is one [VideoUpscaleWorker]. */
class AndroidJobRunner(
    private val context: Context,
    val service: JobService,
    private val settings: SettingsStore,
) : JobRunner {
    private val repository = service.repository
    private val progress = ConcurrentHashMap<String, MutableStateFlow<JobProgress>>()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()

    /** Re-enqueues jobs interrupted by process death (queued, running, paused or muxing). */
    fun restoreInterruptedJobs() {
        repository.list().filter { it.status.isResumable }.sortedBy { it.createdAt }.forEach { schedule(it) }
    }

    fun flowFor(jobId: String): MutableStateFlow<JobProgress> = progress.getOrPut(jobId) { MutableStateFlow(JobProgress()) }

    fun cancelFlag(jobId: String): AtomicBoolean = cancelFlags.getOrPut(jobId) { AtomicBoolean(false) }

    fun clearCancelFlag(jobId: String) {
        cancelFlags.remove(jobId)
    }

    private fun schedule(manifest: JobManifest) {
        cancelFlags[manifest.jobId] = AtomicBoolean(false)
        flowFor(manifest.jobId).value = JobProgress(
            status = JobStatus.QUEUED,
            framesDone = manifest.framesDone,
            estimatedFrames = manifest.estimatedFrames,
        )
        val request = OneTimeWorkRequestBuilder<VideoUpscaleWorker>()
            .setInputData(workDataOf(VideoUpscaleWorker.JOB_ID to manifest.jobId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresCharging(settings.settings.value.onlyWhileCharging)
                    .build()
            )
            .addTag(manifest.jobId)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override suspend fun enqueue(draft: JobDraft): JobManifest {
        val manifest = service.create(draft)
        schedule(manifest)
        return manifest
    }

    override fun progress(jobId: String): StateFlow<JobProgress>? = progress[jobId]

    /** Stops the job at the next frame; the rest of the queue keeps running. */
    override fun cancel(jobId: String) {
        cancelFlag(jobId).set(true)
        val manifest = repository.get(jobId) ?: return
        if (manifest.status == JobStatus.QUEUED) {
            repository.save(manifest.copy(status = JobStatus.CANCELLED))
            flowFor(jobId).value = JobProgress(JobStatus.CANCELLED, manifest.framesDone, manifest.estimatedFrames)
        }
    }

    override fun resume(jobId: String) {
        val manifest = repository.get(jobId) ?: return
        if (manifest.status != JobStatus.CANCELLED && manifest.status != JobStatus.FAILED) return
        val queued = manifest.copy(status = JobStatus.QUEUED, error = null)
        repository.save(queued)
        schedule(queued)
    }

    private companion object {
        const val UNIQUE_WORK = "supervideo-jobs"
    }
}
