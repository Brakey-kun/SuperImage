package com.supervideo.core.video

import kotlinx.serialization.Serializable
import org.bytedeco.ffmpeg.avutil.AVRational
import org.bytedeco.ffmpeg.global.avutil

@Serializable
data class Rational(val num: Int, val den: Int) {
    val value: Double get() = if (den == 0) 0.0 else num.toDouble() / den

    fun toAVRational(): AVRational = avutil.av_make_q(num, den)

    override fun toString() = "$num/$den"

    companion object {
        val MICROSECONDS = Rational(1, 1_000_000)

        /** Rescales [ts] from [from] units to [to] units, rounding to nearest (half away from zero) like av_rescale_q. */
        fun rescale(ts: Long, from: Rational, to: Rational): Long {
            if (from == to) return ts
            val b = from.num.toLong() * to.den
            val c = from.den.toLong() * to.num
            require(c != 0L) { "Invalid time base $from -> $to" }
            return try {
                roundDiv(Math.multiplyExact(ts, b), c)
            } catch (e: ArithmeticException) {
                val n = java.math.BigInteger.valueOf(ts).multiply(java.math.BigInteger.valueOf(b))
                val d = java.math.BigInteger.valueOf(c)
                val half = d.abs().shiftRight(1)
                val q = if (n.signum() * d.signum() >= 0) n.abs().add(half).divide(d.abs())
                else n.abs().add(half).divide(d.abs()).negate()
                q.toLong()
            }
        }

        private fun roundDiv(n: Long, d: Long): Long {
            val half = Math.abs(d) / 2
            val q = (Math.abs(n) + half) / Math.abs(d)
            return if ((n < 0) != (d < 0) && n != 0L) -q else q
        }

        fun of(r: AVRational) = Rational(r.num(), r.den())
    }
}

@Serializable
data class AudioStreamInfo(val index: Int, val codecName: String, val codecId: Int)

@Serializable
data class VideoInfo(
    /** Coded size of the video stream (before rotation). */
    val width: Int,
    val height: Int,
    /** Clockwise rotation needed for display: 0, 90, 180 or 270. */
    val rotationDegrees: Int,
    val streamIndex: Int,
    val timeBase: Rational,
    val avgFrameRate: Rational,
    /** Frame count from the container, 0 when unknown. */
    val nbFrames: Long,
    val durationUs: Long,
    /** First video pts in [timeBase] units (0 when unknown). */
    val startPts: Long,
    val videoCodecName: String,
    val audioStreams: List<AudioStreamInfo>,
    val sizeBytes: Long,
    val modifiedAt: Long,
) {
    /** Size of decoded frames after applying [rotationDegrees]. */
    val displayWidth: Int get() = if (rotationDegrees % 180 == 0) width else height
    val displayHeight: Int get() = if (rotationDegrees % 180 == 0) height else width

    val fps: Double get() = avgFrameRate.value.takeIf { it > 0 } ?: 30.0

    /** Frame count of the whole stream (container count, else derived from duration and fps). */
    val estimatedTotalFrames: Long
        get() = if (nbFrames > 0) nbFrames else (durationUs * fps / 1_000_000.0).toLong().coerceAtLeast(1)
}
