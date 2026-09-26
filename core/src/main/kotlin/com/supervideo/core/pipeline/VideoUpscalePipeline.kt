package com.supervideo.core.pipeline

import com.supervideo.core.job.JobManifest
import com.supervideo.core.job.JobRepository
import com.supervideo.core.job.JobStatus
import com.supervideo.core.job.SegmentEntry
import com.supervideo.core.job.SegmentStatus
import com.supervideo.core.model.ModelStore
import com.supervideo.core.upscale.NativeError
import com.supervideo.core.upscale.UpscaleException
import com.supervideo.core.upscale.UpscaleSession
import com.supervideo.core.video.FrameDecoder
import com.supervideo.core.video.PipelineException
import com.supervideo.core.video.Rational
import com.supervideo.core.video.Remuxer
import com.supervideo.core.video.SegmentEncoder
import com.supervideo.core.video.allocateFrameBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streaming decode → upscale → encode of one job into checkpointed MP4 segments, then remux with
 * the source audio. Resumable: DONE segments from a previous run are kept and decoding restarts
 * right after the last encoded frame.
 */
class VideoUpscalePipeline(
    private val repository: JobRepository,
    private val modelStore: ModelStore,
) {

    private class Frame(val pts: Long, val buffer: ByteBuffer)

    /** The job's manifest, persisted on every change. */
    private class ManifestState(initial: JobManifest, private val repository: JobRepository) {
        @Volatile
        var value: JobManifest = initial
            private set

        @Synchronized
        fun update(transform: (JobManifest) -> JobManifest): JobManifest {
            value = transform(value)
            repository.save(value)
            return value
        }
    }

    /**
     * Runs [initial] to completion. [publish] moves `<jobDir>/output.mp4` to its final location and
     * returns the uri shown to the user.
     *
     * @return the final manifest: DONE, or CANCELLED when [cancel] was set. Failures are saved as
     * FAILED and rethrown; coroutine cancellation leaves the job resumable.
     */
    suspend fun run(
        initial: JobManifest,
        gate: PipelineGate,
        progress: MutableStateFlow<JobProgress>,
        cancel: AtomicBoolean,
        publish: suspend (output: File, manifest: JobManifest) -> String,
    ): JobManifest {
        val jobDir = repository.jobDir(initial.jobId)
        val state = ManifestState(initial, repository)
        try {
            reconcile(state, jobDir)
            val running = state.update { it.copy(status = JobStatus.RUNNING, error = null) }
            progress.value = JobProgress(
                status = JobStatus.RUNNING,
                framesDone = running.framesDone,
                estimatedFrames = running.estimatedFrames,
                currentSegment = running.segments.size + 1,
            )

            if (!state.value.encodeComplete) {
                encode(state, jobDir, gate, progress, cancel)
                state.update { it.copy(encodeComplete = true) }
            }
            if (state.value.framesDone == 0L) throw PipelineException("No frames in the selected range")

            val muxing = state.update { it.copy(status = JobStatus.MUXING) }
            progress.update { it.copy(status = JobStatus.MUXING, etaMillis = null, pausedReason = null) }
            val output = File(jobDir, OUTPUT_FILE)
            val warnings = withContext(Dispatchers.IO) { Remuxer.mux(muxing, jobDir, output) }
            val muxed = state.update { it.copy(warnings = (it.warnings + warnings).distinct()) }
            val uri = publish(output, muxed)
            cleanupAfterSuccess(muxed, jobDir)
            val done = state.update { it.copy(status = JobStatus.DONE, outputUri = uri) }
            progress.update {
                it.copy(status = JobStatus.DONE, framesDone = done.framesDone, estimatedFrames = done.framesDone, etaMillis = 0)
            }
            return done
        } catch (e: JobCancelledException) {
            return cancelled(state, jobDir, progress)
        } catch (e: UpscaleException) {
            if (e.error == NativeError.CANCELLED) return cancelled(state, jobDir, progress)
            fail(state, e, progress)
            throw e
        } catch (e: CancellationException) {
            // Coroutine cancelled (e.g. the worker was stopped): keep the job resumable.
            withContext(NonCancellable) { dropPartialSegments(state, jobDir) }
            throw e
        } catch (e: Throwable) {
            fail(state, e, progress)
            throw e
        }
    }

    private fun fail(state: ManifestState, e: Throwable, progress: MutableStateFlow<JobProgress>) {
        state.update { it.copy(status = JobStatus.FAILED, error = e.message ?: e.javaClass.simpleName) }
        progress.update { it.copy(status = JobStatus.FAILED, etaMillis = null, pausedReason = null) }
    }

    private fun cancelled(state: ManifestState, jobDir: File, progress: MutableStateFlow<JobProgress>): JobManifest {
        dropPartialSegments(state, jobDir)
        val result = state.update { it.copy(status = JobStatus.CANCELLED) }
        progress.update { it.copy(status = JobStatus.CANCELLED, etaMillis = null, pausedReason = null) }
        return result
    }

    /** Removes `.part` files and IN_PROGRESS segment entries so the job can resume from DONE segments. */
    private fun dropPartialSegments(state: ManifestState, jobDir: File) {
        jobDir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
        state.update { m ->
            val done = m.segments.filter { it.status == SegmentStatus.DONE }
            m.segments.filterNot { it in done }.forEach { File(jobDir, it.file).delete() }
            m.copy(segments = done, framesDone = done.sumOf { it.frameCount.toLong() })
        }
    }

    /** Aligns a (possibly interrupted) manifest with what is on disk. */
    private fun reconcile(state: ManifestState, jobDir: File) {
        jobDir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
        val manifest = state.value
        val source = File(manifest.sourcePath)
        if (!source.isFile || source.length() != manifest.source.sizeBytes || source.lastModified() != manifest.source.modifiedAt) {
            throw PipelineException("source changed")
        }
        var done = manifest.segments.filter { it.status == SegmentStatus.DONE }
        // A DONE entry whose file vanished invalidates everything after it.
        val firstMissing = done.indexOfFirst { !File(jobDir, it.file).isFile }
        if (firstMissing >= 0) done = done.take(firstMissing)
        manifest.segments.filterNot { it in done }.forEach { File(jobDir, it.file).delete() }
        state.update {
            it.copy(
                segments = done,
                framesDone = done.sumOf { s -> s.frameCount.toLong() },
                encodeComplete = it.encodeComplete && done.size == it.segments.size && done.isNotEmpty(),
            )
        }
    }

    private suspend fun encode(
        state: ManifestState,
        jobDir: File,
        gate: PipelineGate,
        progress: MutableStateFlow<JobProgress>,
        cancel: AtomicBoolean,
    ) {
        val job = state.value
        val info = job.source
        val settings = job.settings
        val tb = info.timeBase
        val startPts = settings.trimStartUs?.let { Rational.rescale(it, Rational.MICROSECONDS, tb) }
        val endPts = settings.trimEndUs?.let { Rational.rescale(it, Rational.MICROSECONDS, tb) }
        val modelBytes = withContext(Dispatchers.IO) { modelStore.read(settings.model) }

        // MNN sessions are created, used and destroyed on one dedicated thread.
        val upscaleExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "supervideo-upscale").apply { isDaemon = true } }
        val upscaleDispatcher = upscaleExecutor.asCoroutineDispatcher()
        val decoder = withContext(Dispatchers.IO) { FrameDecoder(job.sourcePath, info) }
        var session: UpscaleSession? = null
        try {
            val upscaler = withContext(upscaleDispatcher) {
                UpscaleSession.open(modelBytes, settings.model.scale, decoder.width, decoder.height, settings)
            }
            session = upscaler
            progress.update { it.copy(activeBackend = upscaler.activeBackend) }

            withContext(Dispatchers.IO) { positionDecoder(state, decoder, jobDir, startPts) }

            val inputPool = DirectBufferPool(3, decoder.width * decoder.height * 4)
            val outputPool = DirectBufferPool(2, upscaler.outputWidth * upscaler.outputHeight * 4)
            val decoded = Channel<Frame>(2)
            val upscaled = Channel<Frame>(2)
            val speed = SpeedMeter()

            fun checkCancel() {
                if (cancel.get()) throw JobCancelledException()
            }

            coroutineScope {
                // Stage 1: decode
                launch(Dispatchers.IO) {
                    try {
                        while (true) {
                            checkCancel()
                            val buffer = inputPool.acquire()
                            val pts = decoder.next(buffer)
                            if (pts == null || (endPts != null && pts > endPts)) {
                                inputPool.release(buffer)
                                break
                            }
                            if (startPts != null && pts < startPts) {
                                inputPool.release(buffer)
                                continue
                            }
                            decoded.send(Frame(pts, buffer))
                        }
                    } finally {
                        decoded.close()
                    }
                }

                // Stage 2: upscale
                launch(upscaleDispatcher) {
                    try {
                        for (frame in decoded) {
                            gate.awaitRunnable { reason ->
                                progress.update {
                                    it.copy(pausedReason = reason, status = if (reason != null) JobStatus.PAUSED else JobStatus.RUNNING)
                                }
                            }
                            checkCancel()
                            val out = outputPool.acquire()
                            val began = System.nanoTime()
                            upscaler.upscale(frame.buffer, out, cancel)
                            val ms = (System.nanoTime() - began) / 1_000_000.0
                            speed.add(ms)
                            inputPool.release(frame.buffer)
                            upscaled.send(Frame(frame.pts, out))
                        }
                    } finally {
                        upscaled.close()
                    }
                }

                // Stage 3: encode into checkpointed segments
                launch(Dispatchers.IO) {
                    var encoder: SegmentEncoder? = null
                    var segment: SegmentEntry? = null
                    var framesDone = state.value.framesDone
                    var spentMillis = 0.0

                    fun finishSegment() {
                        encoder!!.finish()
                        encoder = null
                        val finished = segment!!.copy(status = SegmentStatus.DONE)
                        val spent = spentMillis.toLong()
                        spentMillis = 0.0
                        state.update { m ->
                            m.copy(
                                segments = m.segments.map { if (it.index == finished.index) finished else it },
                                framesDone = framesDone,
                                upscaleMillis = m.upscaleMillis + spent,
                            )
                        }
                        segment = null
                    }

                    try {
                        for (frame in upscaled) {
                            checkCancel()
                            if (encoder == null) {
                                val index = state.value.segments.size
                                val entry = SegmentEntry(
                                    index = index,
                                    file = SegmentEntry.fileName(index),
                                    firstFrameIndex = framesDone,
                                    firstPts = frame.pts,
                                )
                                state.update { it.copy(segments = it.segments + entry) }
                                segment = entry
                                encoder = SegmentEncoder(
                                    File(jobDir, entry.file), job.outputWidth, job.outputHeight,
                                    tb, info.avgFrameRate, settings,
                                )
                            }
                            encoder!!.encode(frame.buffer, upscaler.outputWidth, upscaler.outputHeight, frame.pts)
                            outputPool.release(frame.buffer)
                            framesDone++
                            val msPerFrame = speed.msPerFrame
                            spentMillis += msPerFrame
                            val current = segment!!.copy(lastPts = frame.pts, frameCount = segment!!.frameCount + 1)
                            segment = current
                            val estimated = maxOf(job.estimatedFrames, framesDone)
                            progress.update {
                                it.copy(
                                    framesDone = framesDone,
                                    estimatedFrames = estimated,
                                    currentSegment = current.index + 1,
                                    msPerFrame = msPerFrame,
                                    etaMillis = ((estimated - framesDone) * msPerFrame).toLong(),
                                )
                            }
                            if (current.frameCount >= settings.segmentFrames) finishSegment()
                        }
                        if (encoder != null) finishSegment()
                    } finally {
                        encoder?.close()
                    }
                }
            }
        } finally {
            withContext(NonCancellable + upscaleDispatcher) { session?.close() }
            upscaleExecutor.shutdown()
            withContext(NonCancellable + Dispatchers.IO) { decoder.close() }
        }
    }

    /** Positions [decoder] right after the last DONE segment (or at the trim start). */
    private fun positionDecoder(state: ManifestState, decoder: FrameDecoder, jobDir: File, startPts: Long?) {
        val resumeFrom = state.value.segments.lastOrNull()
        if (resumeFrom != null) {
            if (positionAfter(decoder, resumeFrom.lastPts)) return
            state.update { m ->
                m.segments.forEach { File(jobDir, it.file).delete() }
                m.copy(segments = emptyList(), framesDone = 0, warnings = m.warnings + "resume mismatch, restarting")
            }
            decoder.seekTo(startPts ?: state.value.source.startPts)
        } else if (startPts != null) {
            decoder.seekTo(startPts)
        }
    }

    /**
     * Seeks so the next decoded frame is the one after [lastPts].
     * @return false when the frame with [lastPts] cannot be found exactly.
     */
    private fun positionAfter(decoder: FrameDecoder, lastPts: Long): Boolean {
        val scratch = allocateFrameBuffer(decoder.width, decoder.height)
        decoder.seekTo(lastPts)
        while (true) {
            val pts = decoder.next(scratch) ?: return false
            if (pts == lastPts) return true
            if (pts > lastPts) return false
        }
    }

    private fun cleanupAfterSuccess(manifest: JobManifest, jobDir: File) {
        manifest.segments.forEach { File(jobDir, it.file).delete() }
        // A staged copy of the source (Android) lives in the job dir; the user's original is untouched.
        val source = File(manifest.sourcePath)
        if (source.parentFile?.canonicalFile == jobDir.canonicalFile) source.delete()
    }

    companion object {
        const val OUTPUT_FILE = "output.mp4"
    }
}
