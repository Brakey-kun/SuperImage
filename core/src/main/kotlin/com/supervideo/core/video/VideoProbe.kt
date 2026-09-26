package com.supervideo.core.video

import org.bytedeco.ffmpeg.avcodec.AVCodecParameters
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.IntPointer
import java.io.File
import kotlin.math.roundToInt

object VideoProbe {

    /** @throws VideoProbeException when the file has no decodable video stream. */
    fun probe(path: String): VideoInfo = InputFile(path).use { input ->
        val fmt = input.context
        val videoIndex = input.bestVideoStream()
        if (videoIndex < 0) throw VideoProbeException("No video stream found")
        val stream = fmt.streams(videoIndex)
        val par = stream.codecpar()
        avcodec.avcodec_find_decoder(par.codec_id())
            ?: throw VideoProbeException("Unsupported video codec ${codecName(par.codec_id())}")
        if (par.width() <= 0 || par.height() <= 0) throw VideoProbeException("Invalid video size")

        val timeBase = Rational.of(stream.time_base())
        val frameRate = Rational.of(stream.avg_frame_rate()).takeIf { it.num > 0 && it.den > 0 }
            ?: Rational.of(stream.r_frame_rate()).takeIf { it.num > 0 && it.den > 0 }
            ?: Rational(30, 1)
        val durationUs = when {
            stream.duration() != avutil.AV_NOPTS_VALUE && stream.duration() > 0 ->
                Rational.rescale(stream.duration(), timeBase, Rational.MICROSECONDS)
            fmt.duration() != avutil.AV_NOPTS_VALUE && fmt.duration() > 0 -> fmt.duration()
            else -> 0L
        }
        val startPts = stream.start_time().takeIf { it != avutil.AV_NOPTS_VALUE } ?: 0L

        val audio = (0 until fmt.nb_streams()).mapNotNull { index ->
            val p = fmt.streams(index).codecpar()
            if (p.codec_type() == avutil.AVMEDIA_TYPE_AUDIO) {
                AudioStreamInfo(index, codecName(p.codec_id()), p.codec_id())
            } else null
        }

        val file = File(path)
        VideoInfo(
            width = par.width(),
            height = par.height(),
            rotationDegrees = rotationOf(par),
            streamIndex = videoIndex,
            timeBase = timeBase,
            avgFrameRate = frameRate,
            nbFrames = stream.nb_frames().coerceAtLeast(0),
            durationUs = durationUs,
            startPts = startPts,
            videoCodecName = codecName(par.codec_id()),
            audioStreams = audio,
            sizeBytes = file.length(),
            modifiedAt = file.lastModified(),
        )
    }

    internal fun codecName(id: Int): String = avcodec.avcodec_get_name(id)?.string ?: "unknown"

    /** Clockwise rotation (0/90/180/270) that the display matrix asks players to apply. */
    private fun rotationOf(par: AVCodecParameters): Int {
        val sideData = avcodec.av_packet_side_data_get(
            par.coded_side_data(), par.nb_coded_side_data(), avcodec.AV_PKT_DATA_DISPLAYMATRIX
        ) ?: return 0
        if (sideData.size() < 36) return 0
        val theta = avutil.av_display_rotation_get(IntPointer(sideData.data()))
        if (theta.isNaN()) return 0
        // av_display_rotation_get is counter-clockwise; players rotate by -theta.
        val clockwise = ((-theta / 90.0).roundToInt() * 90) % 360
        return (clockwise + 360) % 360
    }
}
