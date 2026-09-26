package com.supervideo.core.settings

import com.supervideo.core.model.UpscaleModel
import com.supervideo.core.upscale.Backend
import com.supervideo.core.upscale.Precision
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class VideoCodec(val encoderName: String, val displayName: String) {
    H264("libx264", "H.264 (libx264)"),
    HEVC("libx265", "HEVC (libx265)"),
}

@Serializable
sealed interface RateControl {
    /** Constant rate factor, 0 (lossless) .. 51 (worst). */
    @Serializable
    @SerialName("crf")
    data class Crf(val value: Int) : RateControl

    @Serializable
    @SerialName("bitrate")
    data class Bitrate(val kbps: Int) : RateControl
}

val X264_PRESETS = listOf("ultrafast", "superfast", "veryfast", "faster", "fast", "medium", "slow", "slower", "veryslow")

@Serializable
data class UpscaleSettings(
    val model: UpscaleModel = UpscaleModel.GENERAL_X2V3,
    /** Input tile edge in pixels; null means [UpscaleModel.defaultTileSize]. */
    val tileSize: Int? = null,
    val tilePadding: Int = 10,
    val backend: Backend = Backend.AUTO,
    val precision: Precision = Precision.FP16,
    /** CPU threads for MNN; 0 = all cores. */
    val threads: Int = 0,
    val codec: VideoCodec = VideoCodec.H264,
    val rateControl: RateControl = RateControl.Crf(18),
    val preset: String = "medium",
    /** Output height; null keeps the model's native size. Width follows the aspect ratio (even). */
    val outputHeight: Int? = null,
    val trimStartUs: Long? = null,
    val trimEndUs: Long? = null,
    val segmentFrames: Int = 300,
    val copyAudio: Boolean = true,
) {
    val effectiveTileSize: Int get() = tileSize ?: model.defaultTileSize

    companion object {
        const val MIN_TILE = 32
        const val MAX_TILE = 512
        const val TILE_STEP = 16
        const val MAX_PADDING = 32
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
data class AppSettings(
    val defaultUpscale: UpscaleSettings = UpscaleSettings(),
    val pauseOnThermal: Boolean = true,
    val onlyWhileCharging: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
)
