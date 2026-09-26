package com.supervideo.core.pipeline

import com.supervideo.core.job.JobStatus
import com.supervideo.core.upscale.Backend
import kotlinx.coroutines.channels.Channel
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class PauseReason(val label: String) { THERMAL("thermal"), BATTERY("battery") }

data class JobProgress(
    val status: JobStatus = JobStatus.QUEUED,
    val framesDone: Long = 0,
    val estimatedFrames: Long = 0,
    /** 1-based index of the segment being encoded. */
    val currentSegment: Int = 0,
    val msPerFrame: Double = 0.0,
    val etaMillis: Long? = null,
    val activeBackend: Backend? = null,
    val pausedReason: PauseReason? = null,
) {
    val fraction: Float
        get() = if (estimatedFrames <= 0) 0f else (framesDone.toFloat() / estimatedFrames).coerceIn(0f, 1f)
}

/** Lets the platform hold the pipeline between frames (thermal / battery). */
fun interface PipelineGate {
    /** Suspends while the job must pause, reporting the reason (null once runnable again). */
    suspend fun awaitRunnable(report: (PauseReason?) -> Unit)

    companion object {
        val ALWAYS_RUN = PipelineGate { }
    }
}

/** Fixed set of reusable direct frame buffers; [acquire] suspends until one is free. */
internal class DirectBufferPool(count: Int, bytes: Int) {
    private val free = Channel<ByteBuffer>(count)

    init {
        repeat(count) {
            free.trySend(ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()))
        }
    }

    suspend fun acquire(): ByteBuffer = free.receive()

    fun release(buffer: ByteBuffer) {
        free.trySend(buffer)
    }
}

/** Thrown inside the pipeline when the job's cancel flag is set. */
class JobCancelledException : Exception("Cancelled")

/** Exponential moving average (α = 0.1) of per-frame upscale time, shared across pipeline stages. */
internal class SpeedMeter {
    @Volatile
    var msPerFrame: Double = 0.0
        private set

    fun add(ms: Double) {
        msPerFrame = if (msPerFrame == 0.0) ms else msPerFrame * 0.9 + ms * 0.1
    }
}
