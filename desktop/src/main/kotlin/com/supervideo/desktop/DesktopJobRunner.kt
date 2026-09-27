package com.supervideo.desktop

import com.supervideo.core.job.JobDraft
import com.supervideo.core.job.JobManifest
import com.supervideo.core.job.JobRunner
import com.supervideo.core.job.JobService
import com.supervideo.core.job.JobStatus
import com.supervideo.core.pipeline.JobProgress
import com.supervideo.core.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Runs jobs one at a time on a background coroutine for the lifetime of the app. */
class DesktopJobRunner(private val service: JobService) : JobRunner {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val progress = ConcurrentHashMap<String, MutableStateFlow<JobProgress>>()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val repository = service.repository

    init {
        // Pick up jobs interrupted by a previous exit, oldest first.
        repository.list().filter { it.status.isResumable }.sortedBy { it.createdAt }.forEach { schedule(it) }
        scope.launch {
            for (jobId in queue) {
                val manifest = repository.get(jobId)?.takeIf { it.status.isResumable } ?: continue
                val cancel = cancelFlags.getOrPut(jobId) { AtomicBoolean(false) }
                if (cancel.get()) {
                    repository.save(manifest.copy(status = JobStatus.CANCELLED))
                    continue
                }
                try {
                    service.execute(jobId, flowFor(jobId), cancel)
                } catch (e: Throwable) {
                    // Saved as FAILED by the pipeline; keep the queue running.
                    AppLog.e("Jobs", "Job $jobId ended with an error", e)
                } finally {
                    cancelFlags.remove(jobId)
                }
            }
        }
    }

    private fun flowFor(jobId: String) = progress.getOrPut(jobId) { MutableStateFlow(JobProgress()) }

    private fun schedule(manifest: JobManifest) {
        cancelFlags[manifest.jobId] = AtomicBoolean(false)
        flowFor(manifest.jobId).value = JobProgress(
            status = JobStatus.QUEUED,
            framesDone = manifest.framesDone,
            estimatedFrames = manifest.estimatedFrames,
        )
        queue.trySend(manifest.jobId)
    }

    override suspend fun enqueue(draft: JobDraft): JobManifest {
        val manifest = service.create(draft)
        schedule(manifest)
        return manifest
    }

    override fun progress(jobId: String): StateFlow<JobProgress>? = progress[jobId]

    override fun cancel(jobId: String) {
        cancelFlags[jobId]?.set(true)
        val manifest = repository.get(jobId) ?: return
        if (manifest.status == JobStatus.QUEUED) {
            repository.save(manifest.copy(status = JobStatus.CANCELLED))
            progress[jobId]?.value = JobProgress(status = JobStatus.CANCELLED, framesDone = manifest.framesDone, estimatedFrames = manifest.estimatedFrames)
        }
    }

    override fun resume(jobId: String) {
        val manifest = repository.get(jobId) ?: return
        if (manifest.status != JobStatus.CANCELLED && manifest.status != JobStatus.FAILED) return
        val queued = manifest.copy(status = JobStatus.QUEUED, error = null)
        repository.save(queued)
        schedule(queued)
    }
}
