package com.supervideo.core.video

import com.supervideo.core.settings.VideoCodec
import org.bytedeco.ffmpeg.global.avcodec
import java.util.concurrent.ConcurrentHashMap

/** Which encoders the bundled FFmpeg build provides (HEVC is hidden when libx265 is missing). */
object EncoderSupport {
    private val cache = ConcurrentHashMap<VideoCodec, Boolean>()

    fun isAvailable(codec: VideoCodec): Boolean = cache.getOrPut(codec) {
        try {
            avcodec.avcodec_find_encoder_by_name(codec.encoderName) != null
        } catch (e: Throwable) {
            false
        }
    }
}
