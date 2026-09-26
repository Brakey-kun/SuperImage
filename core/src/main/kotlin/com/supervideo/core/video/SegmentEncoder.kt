package com.supervideo.core.video

import com.supervideo.core.settings.RateControl
import com.supervideo.core.settings.UpscaleSettings
import com.supervideo.core.settings.VideoCodec
import com.supervideo.core.util.moveTo
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Encodes RGBA frames into an MP4 segment at `<file>.part`, renamed to [file] by [finish].
 * Frame pts are given in [timeBase] (the source stream time base) and are stored relative
 * to the segment's first frame, so every segment starts at pts 0.
 */
class SegmentEncoder(
    private val file: File,
    val width: Int,
    val height: Int,
    private val timeBase: Rational,
    frameRate: Rational,
    settings: UpscaleSettings,
) : AutoCloseable {

    private val partFile = File(file.parentFile, file.name + ".part")
    private val output = AVFormatContext(null)
    private var codecContext: AVCodecContext? = null
    private val stream: AVStream
    private val frame: AVFrame = avutil.av_frame_alloc()
    private val packet: AVPacket = avcodec.av_packet_alloc()
    private var sws: SwsContext? = null
    private var swsSource = 0 to 0
    private val srcData = PointerPointer<BytePointer>(4)
    private val srcStride = IntPointer(4L)
    private var basePts: Long? = null
    private var headerWritten = false
    private var finished = false
    private val frameDuration: Long
    private val encoderTimeBase = timeBase.toAVRational()

    /** Time base the muxer chose for the segment's video stream (read after the header). */
    lateinit var segmentTimeBase: Rational
        private set

    init {
        require(width % 2 == 0 && height % 2 == 0) { "Output size must be even: ${width}x$height" }
        try {
            partFile.delete()
            FFmpeg.check(avformat.avformat_alloc_output_context2(output, null, "mp4", partFile.absolutePath), "avformat_alloc_output_context2")
            val encoder = avcodec.avcodec_find_encoder_by_name(settings.codec.encoderName)
                ?: throw PipelineException("encoder ${settings.codec.encoderName} not available")
            val ctx = avcodec.avcodec_alloc_context3(encoder)
            codecContext = ctx
            val fps = frameRate.value.takeIf { it > 0 } ?: 30.0
            ctx.width(width)
            ctx.height(height)
            ctx.pix_fmt(avutil.AV_PIX_FMT_YUV420P)
            ctx.time_base(encoderTimeBase)
            ctx.framerate(frameRate.toAVRational())
            ctx.sample_aspect_ratio(avutil.av_make_q(1, 1))
            ctx.gop_size((fps * 2).roundToInt().coerceAtLeast(1))
            ctx.thread_count(Runtime.getRuntime().availableProcessors().coerceAtMost(16))
            ctx.colorspace(avutil.AVCOL_SPC_BT709)
            ctx.color_primaries(avutil.AVCOL_PRI_BT709)
            ctx.color_trc(avutil.AVCOL_TRC_BT709)
            ctx.color_range(avutil.AVCOL_RANGE_MPEG)
            avutil.av_opt_set(ctx.priv_data(), "preset", settings.preset, 0)
            when (val rc = settings.rateControl) {
                is RateControl.Crf -> avutil.av_opt_set(ctx.priv_data(), "crf", rc.value.coerceIn(0, 51).toString(), 0)
                is RateControl.Bitrate -> ctx.bit_rate(rc.kbps.toLong() * 1000)
            }
            if (settings.codec == VideoCodec.HEVC) {
                avutil.av_opt_set(ctx.priv_data(), "x265-params", "log-level=error", 0)
            }
            if (output.oformat().flags() and avformat.AVFMT_GLOBALHEADER != 0) {
                ctx.flags(ctx.flags() or avcodec.AV_CODEC_FLAG_GLOBAL_HEADER)
            }
            FFmpeg.check(avcodec.avcodec_open2(ctx, encoder, null as AVDictionary?), "avcodec_open2 (${settings.codec.encoderName})")

            stream = avformat.avformat_new_stream(output, null)
                ?: throw PipelineException("avformat_new_stream failed")
            FFmpeg.check(avcodec.avcodec_parameters_from_context(stream.codecpar(), ctx), "avcodec_parameters_from_context")
            stream.time_base(encoderTimeBase)
            stream.avg_frame_rate(frameRate.toAVRational())

            val pb = AVIOContext(null)
            FFmpeg.check(avformat.avio_open(pb, partFile.absolutePath, avformat.AVIO_FLAG_WRITE), "avio_open")
            output.pb(pb)
            FFmpeg.check(avformat.avformat_write_header(output, null as AVDictionary?), "avformat_write_header")
            headerWritten = true
            segmentTimeBase = Rational.of(stream.time_base())

            frameDuration = (timeBase.den / (fps * timeBase.num)).roundToLong().coerceAtLeast(1)

            frame.format(avutil.AV_PIX_FMT_YUV420P)
            frame.width(width)
            frame.height(height)
            FFmpeg.check(avutil.av_frame_get_buffer(frame, 0), "av_frame_get_buffer")
        } catch (e: Throwable) {
            release()
            partFile.delete()
            throw e
        }
    }

    /** Encodes the `srcWidth x srcHeight` RGBA frame in [rgba] with source timestamp [pts]. */
    fun encode(rgba: ByteBuffer, srcWidth: Int, srcHeight: Int, pts: Long) {
        check(!finished) { "Segment already finished" }
        val ctx = codecContext!!
        if (sws == null || swsSource != srcWidth to srcHeight) {
            sws?.let { swscale.sws_freeContext(it) }
            val flags = if (srcWidth == width && srcHeight == height) swscale.SWS_POINT else swscale.SWS_LANCZOS
            sws = swscale.sws_getContext(
                srcWidth, srcHeight, avutil.AV_PIX_FMT_RGBA,
                width, height, avutil.AV_PIX_FMT_YUV420P,
                flags or swscale.SWS_ACCURATE_RND, null, null, null as DoublePointer?
            ) ?: throw PipelineException("sws_getContext failed")
            ColorSpace.configure(sws!!, swscale.SWS_CS_ITU709, yuvFullRange = false, yuvIsSource = false)
            swsSource = srcWidth to srcHeight
        }
        FFmpeg.check(avutil.av_frame_make_writable(frame), "av_frame_make_writable")
        srcData.put(0, BytePointer(rgba))
        srcStride.put(0L, srcWidth * 4)
        swscale.sws_scale(sws, srcData, srcStride, 0, srcHeight, frame.data(), frame.linesize())

        val base = basePts ?: pts.also { basePts = it }
        frame.pts(pts - base)
        frame.duration(frameDuration)
        FFmpeg.check(avcodec.avcodec_send_frame(ctx, frame), "avcodec_send_frame")
        drain()
    }

    private fun drain() {
        val ctx = codecContext!!
        while (true) {
            val received = avcodec.avcodec_receive_packet(ctx, packet)
            if (received == FFmpeg.AVERROR_EAGAIN || received == FFmpeg.AVERROR_EOF) return
            FFmpeg.check(received, "avcodec_receive_packet")
            packet.stream_index(stream.index())
            avcodec.av_packet_rescale_ts(packet, encoderTimeBase, stream.time_base())
            val written = avformat.av_interleaved_write_frame(output, packet)
            avcodec.av_packet_unref(packet)
            FFmpeg.check(written, "av_interleaved_write_frame")
        }
    }

    /** Flushes the encoder, writes the trailer and renames `.part` to the final file. */
    fun finish() {
        check(!finished)
        FFmpeg.check(avcodec.avcodec_send_frame(codecContext!!, null as AVFrame?), "avcodec_send_frame (flush)")
        drain()
        FFmpeg.check(avformat.av_write_trailer(output), "av_write_trailer")
        finished = true
        release()
        partFile.moveTo(file)
    }

    private fun release() {
        sws?.let { swscale.sws_freeContext(it) }
        sws = null
        codecContext?.let { avcodec.avcodec_free_context(it) }
        codecContext = null
        avutil.av_frame_free(frame)
        avcodec.av_packet_free(packet)
        if (!output.isNull) {
            output.pb()?.let { avformat.avio_close(it) }
            output.pb(null)
            avformat.avformat_free_context(output)
        }
        srcData.close()
        srcStride.close()
    }

    /** Abandons the segment, leaving any `.part` file for the caller's cleanup. */
    override fun close() {
        if (!finished) {
            finished = true
            release()
        }
    }
}
