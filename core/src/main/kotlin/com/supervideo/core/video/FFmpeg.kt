package com.supervideo.core.video

import org.bytedeco.ffmpeg.global.avutil

open class PipelineException(message: String, cause: Throwable? = null) : Exception(message, cause)

class VideoProbeException(message: String) : PipelineException(message)

internal object FFmpeg {
    val AVERROR_EOF: Int = avutil.AVERROR_EOF

    /** AVERROR(EAGAIN): EAGAIN is 11 on Linux/Android and in the Windows UCRT. */
    const val AVERROR_EAGAIN: Int = -11

    fun errorString(code: Int): String {
        val buffer = ByteArray(256)
        avutil.av_strerror(code, buffer, buffer.size.toLong())
        val length = buffer.indexOf(0).takeIf { it >= 0 } ?: buffer.size
        return String(buffer, 0, length, Charsets.UTF_8)
    }

    /** Throws [PipelineException] when [code] is a negative FFmpeg error. */
    fun check(code: Int, what: String): Int {
        if (code < 0) throw PipelineException("$what failed: ${errorString(code)} ($code)")
        return code
    }

    init {
        avutil.av_log_set_level(avutil.AV_LOG_ERROR)
    }
}
