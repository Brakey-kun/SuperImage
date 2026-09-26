package com.supervideo.core.video

import com.supervideo.core.job.JobManifest
import com.supervideo.core.job.SegmentEntry
import com.supervideo.core.util.moveTo
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import java.io.File

/**
 * Concatenates the encoded segments of a job into one MP4 and stream-copies the source audio.
 *
 * Every video packet gets back the source timestamp of its frame (segments store pts relative to
 * their first frame), so frame timing — including VFR — is identical to the source. When the job is
 * trimmed, all streams are shifted by the trim start so the output starts near zero.
 */
object Remuxer {

    private val MP4_AUDIO_CODECS = setOf(
        avcodec.AV_CODEC_ID_AAC, avcodec.AV_CODEC_ID_MP3, avcodec.AV_CODEC_ID_AC3, avcodec.AV_CODEC_ID_EAC3,
        avcodec.AV_CODEC_ID_OPUS, avcodec.AV_CODEC_ID_ALAC, avcodec.AV_CODEC_ID_FLAC,
    )
    private val NOPTS = avutil.AV_NOPTS_VALUE

    /** @return warnings produced while muxing (skipped audio streams). */
    fun mux(job: JobManifest, jobDir: File, output: File): List<String> {
        val segments = job.doneSegments.sortedBy { it.index }
        require(segments.isNotEmpty()) { "No encoded segments" }
        val warnings = mutableListOf<String>()
        val partFile = File(output.parentFile, output.name + ".part")
        partFile.delete()

        val sourceTb = job.source.timeBase
        val trimmed = job.settings.trimStartUs != null || job.settings.trimEndUs != null
        val shiftUs = job.settings.trimStartUs ?: 0L
        val shiftVideo = Rational.rescale(shiftUs, Rational.MICROSECONDS, sourceTb)
        val frameDuration = (sourceTb.den / (job.source.fps * sourceTb.num)).toLong().coerceAtLeast(1)
        val videoEndUs = Rational.rescale(segments.last().lastPts + frameDuration, sourceTb, Rational.MICROSECONDS)

        val out = AVFormatContext(null)
        FFmpeg.check(avformat.avformat_alloc_output_context2(out, null, "mp4", partFile.absolutePath), "avformat_alloc_output_context2")
        val videoPacket = avcodec.av_packet_alloc()
        val audioPacket = avcodec.av_packet_alloc()
        val video = SegmentPackets(jobDir, segments, videoPacket, sourceTb)
        var sourceInput: InputFile? = null
        try {
            // Video stream parameters come from the first segment.
            InputFile(File(jobDir, segments.first().file).absolutePath).use { first ->
                val index = first.bestVideoStream()
                check(index >= 0) { "Segment has no video stream" }
                val videoOut = avformat.avformat_new_stream(out, null)
                FFmpeg.check(avcodec.avcodec_parameters_copy(videoOut.codecpar(), first.context.streams(index).codecpar()), "avcodec_parameters_copy")
                videoOut.codecpar().codec_tag(0)
                videoOut.time_base(sourceTb.toAVRational())
                videoOut.avg_frame_rate(job.source.avgFrameRate.toAVRational())
            }

            // Audio streams stream-copied from the source.
            val audioMap = HashMap<Int, Int>() // source stream index -> output stream index
            if (job.settings.copyAudio && job.source.audioStreams.isNotEmpty()) {
                val input = InputFile(job.sourcePath)
                sourceInput = input
                for (audio in job.source.audioStreams) {
                    if (audio.codecId !in MP4_AUDIO_CODECS) {
                        warnings += "audio stream #${audio.index} (${audio.codecName}) skipped: not mp4-compatible"
                        continue
                    }
                    val inStream = input.context.streams(audio.index)
                    val outStream = avformat.avformat_new_stream(out, null)
                    FFmpeg.check(avcodec.avcodec_parameters_copy(outStream.codecpar(), inStream.codecpar()), "avcodec_parameters_copy")
                    outStream.codecpar().codec_tag(0)
                    outStream.time_base(inStream.time_base())
                    audioMap[audio.index] = outStream.index()
                }
                for (i in 0 until input.context.nb_streams()) {
                    if (i !in audioMap) input.context.streams(i).discard(avcodec.AVDISCARD_ALL)
                }
            }

            val pb = AVIOContext(null)
            FFmpeg.check(avformat.avio_open(pb, partFile.absolutePath, avformat.AVIO_FLAG_WRITE), "avio_open")
            out.pb(pb)
            val options = AVDictionary(null)
            avutil.av_dict_set(options, "movflags", "+faststart", 0)
            try {
                FFmpeg.check(avformat.avformat_write_header(out, options), "avformat_write_header")
            } finally {
                avutil.av_dict_free(options)
            }
            val videoOutTb = Rational.of(out.streams(0).time_base())
            val audioInTbs = audioMap.keys.associateWith { Rational.of(sourceInput!!.context.streams(it).time_base()) }
            val audioOutTbs = audioMap.mapValues { (_, outIndex) -> Rational.of(out.streams(outIndex).time_base()) }
            val lastDts = LongArray(out.nb_streams()) { Long.MIN_VALUE }

            fun write(packet: AVPacket, outIndex: Int, pts: Long, dts: Long, duration: Long) {
                var fixedDts = dts
                if (fixedDts != NOPTS && lastDts[outIndex] != Long.MIN_VALUE && fixedDts <= lastDts[outIndex]) {
                    fixedDts = lastDts[outIndex] + 1
                }
                if (fixedDts != NOPTS) lastDts[outIndex] = fixedDts
                packet.pts(pts)
                packet.dts(fixedDts)
                packet.duration(duration)
                packet.stream_index(outIndex)
                packet.pos(-1)
                FFmpeg.check(avformat.av_interleaved_write_frame(out, packet), "av_interleaved_write_frame")
            }

            fun shifted(ts: Long, shift: Long, from: Rational, to: Rational) =
                if (ts == NOPTS) NOPTS else Rational.rescale(ts - shift, from, to)

            var haveVideo = video.next()
            var haveAudio = sourceInput != null && audioMap.isNotEmpty() && readAudio(sourceInput, audioPacket, audioMap.keys)

            while (haveVideo || haveAudio) {
                val videoUs = if (haveVideo) Rational.rescale(video.dtsOrPts(), sourceTb, Rational.MICROSECONDS) else Long.MAX_VALUE
                val audioUs = if (haveAudio) {
                    Rational.rescale(audioPacket.dtsOrPts(), audioInTbs.getValue(audioPacket.stream_index()), Rational.MICROSECONDS)
                } else Long.MAX_VALUE

                if (haveVideo && videoUs <= audioUs) {
                    write(
                        videoPacket, 0,
                        shifted(video.pts(), shiftVideo, sourceTb, videoOutTb),
                        shifted(video.dts(), shiftVideo, sourceTb, videoOutTb),
                        Rational.rescale(video.duration(), sourceTb, videoOutTb),
                    )
                    avcodec.av_packet_unref(videoPacket)
                    haveVideo = video.next()
                } else {
                    val srcIndex = audioPacket.stream_index()
                    val inTb = audioInTbs.getValue(srcIndex)
                    val outTb = audioOutTbs.getValue(srcIndex)
                    val ptsUs = Rational.rescale(audioPacket.ptsOrDts(), inTb, Rational.MICROSECONDS)
                    if (!trimmed || (ptsUs >= shiftUs && ptsUs < videoEndUs)) {
                        val shift = Rational.rescale(shiftUs, Rational.MICROSECONDS, inTb)
                        write(
                            audioPacket, audioMap.getValue(srcIndex),
                            shifted(audioPacket.pts(), shift, inTb, outTb),
                            shifted(audioPacket.dts(), shift, inTb, outTb),
                            Rational.rescale(audioPacket.duration(), inTb, outTb),
                        )
                    }
                    avcodec.av_packet_unref(audioPacket)
                    haveAudio = !(trimmed && ptsUs >= videoEndUs) && readAudio(sourceInput!!, audioPacket, audioMap.keys)
                }
            }
            FFmpeg.check(avformat.av_write_trailer(out), "av_write_trailer")
        } catch (e: Throwable) {
            closeOutput(out)
            partFile.delete()
            throw e
        } finally {
            video.close()
            sourceInput?.close()
            avcodec.av_packet_free(videoPacket)
            avcodec.av_packet_free(audioPacket)
        }
        closeOutput(out)
        partFile.moveTo(output)
        return warnings
    }

    private fun closeOutput(out: AVFormatContext) {
        if (out.isNull) return
        out.pb()?.let { avformat.avio_close(it) }
        out.pb(null)
        avformat.avformat_free_context(out)
    }

    private fun AVPacket.dtsOrPts(): Long = if (dts() != NOPTS) dts() else pts()
    private fun AVPacket.ptsOrDts(): Long = if (pts() != NOPTS) pts() else dts()

    private fun readAudio(input: InputFile, packet: AVPacket, streams: Set<Int>): Boolean {
        while (true) {
            if (avformat.av_read_frame(input.context, packet) < 0) return false
            if (packet.stream_index() in streams && packet.dtsOrPts() != NOPTS) return true
            avcodec.av_packet_unref(packet)
        }
    }

    /** Sequential reader over the video packets of all segments, restoring source timestamps. */
    private class SegmentPackets(
        private val jobDir: File,
        private val segments: List<SegmentEntry>,
        private val packet: AVPacket,
        private val sourceTb: Rational,
    ) : AutoCloseable {
        private var segmentIndex = -1
        private var input: InputFile? = null
        private var streamIndex = -1
        private var segmentTb = sourceTb

        /** Reads the next video packet into [packet]; false after the last segment. */
        fun next(): Boolean {
            while (true) {
                input?.let { current ->
                    while (avformat.av_read_frame(current.context, packet) >= 0) {
                        if (packet.stream_index() == streamIndex) return true
                        avcodec.av_packet_unref(packet)
                    }
                    current.close()
                    input = null
                }
                segmentIndex++
                if (segmentIndex >= segments.size) return false
                val opened = InputFile(File(jobDir, segments[segmentIndex].file).absolutePath)
                input = opened
                streamIndex = opened.bestVideoStream()
                check(streamIndex >= 0) { "Segment ${segments[segmentIndex].file} has no video" }
                segmentTb = Rational.of(opened.context.streams(streamIndex).time_base())
            }
        }

        private fun toSource(ts: Long) =
            if (ts == NOPTS) NOPTS else Rational.rescale(ts, segmentTb, sourceTb) + segments[segmentIndex].firstPts

        /** Timestamps of the current packet in the source stream time base. */
        fun pts(): Long = toSource(packet.pts())
        fun dts(): Long = toSource(packet.dts())
        fun dtsOrPts(): Long = if (packet.dts() != NOPTS) dts() else pts()
        fun duration(): Long = Rational.rescale(packet.duration(), segmentTb, sourceTb)

        override fun close() {
            input?.close()
            input = null
        }
    }
}
