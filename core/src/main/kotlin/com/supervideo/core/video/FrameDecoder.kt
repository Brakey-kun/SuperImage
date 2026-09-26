package com.supervideo.core.video

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the video stream of [path] into tightly packed RGBA frames of `width x height`
 * (the display size: rotation from the container's display matrix is applied to the pixels).
 * Timestamps are reported in the stream time base ([VideoInfo.timeBase]). Not thread-safe.
 */
class FrameDecoder(path: String, private val info: VideoInfo) : AutoCloseable {

    val width: Int = info.displayWidth
    val height: Int = info.displayHeight

    private val input = InputFile(path)
    private val streamIndex = info.streamIndex
    private val codecContext: AVCodecContext
    private val packet: AVPacket = avcodec.av_packet_alloc()
    private val frame = avutil.av_frame_alloc()
    private var sws: SwsContext? = null
    private var swsKey: List<Int> = emptyList()
    private val dstData = PointerPointer<BytePointer>(4)
    private val dstStride = IntPointer(4L)
    private val rotateScratch: ByteBuffer? =
        if (info.rotationDegrees != 0) allocateFrameBuffer(info.width, info.height) else null
    private var inputEof = false

    init {
        try {
            val stream = input.context.streams(streamIndex)
            val decoder = avcodec.avcodec_find_decoder(stream.codecpar().codec_id())
                ?: throw VideoProbeException("Unsupported video codec ${info.videoCodecName}")
            codecContext = avcodec.avcodec_alloc_context3(decoder)
            FFmpeg.check(avcodec.avcodec_parameters_to_context(codecContext, stream.codecpar()), "avcodec_parameters_to_context")
            codecContext.thread_count(Runtime.getRuntime().availableProcessors().coerceAtMost(16))
            codecContext.pkt_timebase(stream.time_base())
            FFmpeg.check(avcodec.avcodec_open2(codecContext, decoder, null as AVDictionary?), "avcodec_open2 (decoder)")
            // Ignore every other stream in the demuxer.
            for (i in 0 until input.context.nb_streams()) {
                if (i != streamIndex) input.context.streams(i).discard(avcodec.AVDISCARD_ALL)
            }
        } catch (e: Throwable) {
            releaseNative()
            throw e
        }
    }

    /** Seeks to the keyframe at or before [pts] (stream time base); following [next] calls start there. */
    fun seekTo(pts: Long) {
        FFmpeg.check(avformat.av_seek_frame(input.context, streamIndex, pts, avformat.AVSEEK_FLAG_BACKWARD), "av_seek_frame")
        avcodec.avcodec_flush_buffers(codecContext)
        inputEof = false
    }

    /**
     * Decodes the next frame into [into] (at least `width * height * 4` bytes, direct).
     * @return the frame pts in stream time base, or null at end of stream.
     */
    fun next(into: ByteBuffer): Long? {
        while (true) {
            val received = avcodec.avcodec_receive_frame(codecContext, frame)
            if (received == 0) {
                try {
                    val pts = frame.best_effort_timestamp()
                    if (pts == avutil.AV_NOPTS_VALUE) throw PipelineException("frame without timestamp")
                    convert(into)
                    return pts
                } finally {
                    avutil.av_frame_unref(frame)
                }
            }
            if (received == FFmpeg.AVERROR_EOF) return null
            if (received != FFmpeg.AVERROR_EAGAIN) FFmpeg.check(received, "avcodec_receive_frame")
            if (inputEof) return null

            val read = avformat.av_read_frame(input.context, packet)
            if (read < 0) {
                // End of file (or unreadable tail): drain the decoder.
                inputEof = true
                avcodec.avcodec_send_packet(codecContext, null as AVPacket?)
                continue
            }
            try {
                if (packet.stream_index() == streamIndex) {
                    val sent = avcodec.avcodec_send_packet(codecContext, packet)
                    if (sent < 0 && sent != FFmpeg.AVERROR_EAGAIN && sent != avutil.AVERROR_INVALIDDATA) {
                        FFmpeg.check(sent, "avcodec_send_packet")
                    }
                }
            } finally {
                avcodec.av_packet_unref(packet)
            }
        }
    }

    private fun convert(into: ByteBuffer) {
        require(into.isDirect && into.capacity() >= width * height * 4) { "Frame buffer too small" }
        val target = rotateScratch ?: into
        val key = listOf(frame.width(), frame.height(), frame.format(), frame.colorspace(), frame.color_range())
        if (key != swsKey) {
            sws?.let { swscale.sws_freeContext(it) }
            sws = swscale.sws_getContext(
                frame.width(), frame.height(), frame.format(),
                info.width, info.height, avutil.AV_PIX_FMT_RGBA,
                swscale.SWS_BICUBIC or swscale.SWS_ACCURATE_RND, null, null, null as DoublePointer?
            ) ?: throw PipelineException("Unsupported pixel format ${frame.format()}")
            ColorSpace.configure(
                sws!!,
                ColorSpace.swsTable(frame.colorspace(), frame.height()),
                yuvFullRange = frame.color_range() == avutil.AVCOL_RANGE_JPEG,
                yuvIsSource = true,
            )
            swsKey = key
        }
        dstData.put(0, BytePointer(target))
        dstStride.put(0L, info.width * 4)
        swscale.sws_scale(sws, frame.data(), frame.linesize(), 0, frame.height(), dstData, dstStride)
        if (rotateScratch != null) {
            Rotate.rgba(rotateScratch, info.width, info.height, info.rotationDegrees, into)
        }
    }

    private fun releaseNative() {
        sws?.let { swscale.sws_freeContext(it) }
        sws = null
        avutil.av_frame_free(frame)
        avcodec.av_packet_free(packet)
        dstData.close()
        dstStride.close()
        input.close()
    }

    override fun close() {
        avcodec.avcodec_free_context(codecContext)
        releaseNative()
    }
}

/** Allocates a native-order direct buffer for a `width x height` RGBA frame. */
fun allocateFrameBuffer(width: Int, height: Int): ByteBuffer =
    ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
