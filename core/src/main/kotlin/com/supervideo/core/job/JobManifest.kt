package com.supervideo.core.job

import com.supervideo.core.settings.UpscaleSettings
import com.supervideo.core.video.VideoInfo
import kotlinx.serialization.Serializable

enum class JobStatus {
    QUEUED, RUNNING, PAUSED, MUXING, DONE, FAILED, CANCELLED;

    /** A job interrupted in one of these states is picked up again at startup. */
    val isResumable: Boolean get() = this == QUEUED || this == RUNNING || this == PAUSED || this == MUXING
    val isActive: Boolean get() = this == RUNNING || this == PAUSED || this == MUXING
}

enum class SegmentStatus { IN_PROGRESS, DONE }

@Serializable
data class SegmentEntry(
    val index: Int,
    /** File name inside the job directory, `seg_%06d.mp4`. */
    val file: String,
    val firstFrameIndex: Long,
    /** Source pts (stream time base) of the first and last frame in the segment. */
    val firstPts: Long,
    val lastPts: Long = firstPts,
    val frameCount: Int = 0,
    val status: SegmentStatus = SegmentStatus.IN_PROGRESS,
) {
    companion object {
        fun fileName(index: Int) = "seg_%06d.mp4".format(index)
    }
}

/** Persistent state of one upscaling job, stored as `<jobDir>/manifest.json`. */
@Serializable
data class JobManifest(
    val version: Int = 1,
    val jobId: String,
    val createdAt: Long,
    val sourcePath: String,
    val sourceName: String,
    val source: VideoInfo,
    val settings: UpscaleSettings,
    val outputName: String,
    val outputWidth: Int,
    val outputHeight: Int,
    val estimatedFrames: Long,
    val segments: List<SegmentEntry> = emptyList(),
    val status: JobStatus = JobStatus.QUEUED,
    val error: String? = null,
    /** Destination chosen by the user (desktop save dialog); null lets the platform decide. */
    val outputTarget: String? = null,
    /** Where the finished video was published (file path or content URI). */
    val outputUri: String? = null,
    val framesDone: Long = 0,
    val upscaleMillis: Long = 0,
    /** True once every frame of the range is encoded into DONE segments (only muxing remains). */
    val encodeComplete: Boolean = false,
    val warnings: List<String> = emptyList(),
) {
    val doneSegments: List<SegmentEntry> get() = segments.filter { it.status == SegmentStatus.DONE }
}
