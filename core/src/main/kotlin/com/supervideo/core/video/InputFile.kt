package com.supervideo.core.video

import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.PointerPointer

/**
 * An opened demuxer with stream info; close to release it.
 * [path] is a file path, or `fd:<n>` to read an already-open file descriptor (Android documents).
 */
internal class InputFile(path: String) : AutoCloseable {
    val context: AVFormatContext = AVFormatContext(null)

    init {
        FFmpeg // log level
        val options = AVDictionary(null)
        val url = if (path.startsWith(FD_PREFIX)) {
            // FFmpeg's fd protocol dups the descriptor, so several readers can share it.
            avutil.av_dict_set(options, "fd", path.removePrefix(FD_PREFIX), 0)
            FD_PREFIX
        } else {
            path
        }
        val open = try {
            avformat.avformat_open_input(context, url, null, options)
        } finally {
            avutil.av_dict_free(options)
        }
        if (open < 0) throw VideoProbeException("Cannot open video: ${FFmpeg.errorString(open)}")
        val info = avformat.avformat_find_stream_info(context, null as PointerPointer<*>?)
        if (info < 0) {
            avformat.avformat_close_input(context)
            throw VideoProbeException("Cannot read stream info: ${FFmpeg.errorString(info)}")
        }
    }

    companion object {
        const val FD_PREFIX = "fd:"
    }

    fun bestVideoStream(): Int =
        avformat.av_find_best_stream(context, avutil.AVMEDIA_TYPE_VIDEO, -1, -1, null as PointerPointer<*>?, 0)

    override fun close() {
        avformat.avformat_close_input(context)
    }
}

/** YUV <-> RGB matrix selection, so decode and encode round-trip without color shifts. */
internal object ColorSpace {
    /** swscale coefficient table for a stream colorspace (unspecified: BT.709 for HD, else BT.601). */
    fun swsTable(colorspace: Int, height: Int): Int = when (colorspace) {
        avutil.AVCOL_SPC_BT709 -> swscale.SWS_CS_ITU709
        avutil.AVCOL_SPC_BT470BG, avutil.AVCOL_SPC_SMPTE170M -> swscale.SWS_CS_ITU601
        avutil.AVCOL_SPC_BT2020_NCL -> swscale.SWS_CS_BT2020
        else -> if (height >= 720) swscale.SWS_CS_ITU709 else swscale.SWS_CS_ITU601
    }

    /**
     * Configures [sws] converting between YUV using [table] (limited range unless [yuvFullRange]) and full-range RGB.
     * [yuvIsSource] tells the direction.
     */
    fun configure(sws: SwsContext, table: Int, yuvFullRange: Boolean, yuvIsSource: Boolean) {
        val coefficients = swscale.sws_getCoefficients(table)
        val yuvRange = if (yuvFullRange) 1 else 0
        if (yuvIsSource) {
            swscale.sws_setColorspaceDetails(sws, coefficients, yuvRange, coefficients, 1, 0, 1 shl 16, 1 shl 16)
        } else {
            swscale.sws_setColorspaceDetails(sws, coefficients, 1, coefficients, yuvRange, 0, 1 shl 16, 1 shl 16)
        }
    }
}
