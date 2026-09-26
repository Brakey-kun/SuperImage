package com.supervideo.core.model

import com.supervideo.core.model.UpscaleModel.Family.RRDB
import com.supervideo.core.model.UpscaleModel.Family.SRVGG

enum class UpscaleModel(
    val id: String,
    val fileName: String,
    val scale: Int,
    val family: Family,
    val defaultTileSize: Int,
    val displayName: String,
) {
    GENERAL_X2V3("general-x2v3", "realesr-general-x2v3.mnn", 2, SRVGG, 256, "General ×2 (fast)"),
    GENERAL_X4V3("general-x4v3", "realesr-general-x4v3.mnn", 4, SRVGG, 256, "General ×4 (fast)"),
    ANIME_VIDEO_V3("anime-video-v3", "realesr-animevideov3.mnn", 4, SRVGG, 256, "Anime video ×4 (fast)"),
    X2_PLUS("x2plus", "realesrgan-x2plus.mnn", 2, RRDB, 84, "RealESRGAN ×2 plus (very slow)"),
    X4_PLUS("x4plus", "realesrgan-x4plus.mnn", 4, RRDB, 84, "RealESRGAN ×4 plus (very slow)"),
    X4_PLUS_ANIME("x4plus-anime", "realesrgan-x4plus-anime.mnn", 4, RRDB, 84, "RealESRGAN ×4 anime (very slow)");

    enum class Family { SRVGG, RRDB }
}

/** Reads the serialized `.mnn` bytes of a model from platform storage (assets / resources dir). */
fun interface ModelStore {
    fun read(model: UpscaleModel): ByteArray
}
