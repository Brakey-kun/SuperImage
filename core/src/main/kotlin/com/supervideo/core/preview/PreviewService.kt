package com.supervideo.core.preview

import com.supervideo.core.model.ModelStore
import com.supervideo.core.settings.UpscaleSettings
import com.supervideo.core.upscale.Backend
import com.supervideo.core.upscale.UpscaleSession
import com.supervideo.core.video.FrameDecoder
import com.supervideo.core.video.PipelineException
import com.supervideo.core.video.Rational
import com.supervideo.core.video.VideoInfo
import com.supervideo.core.video.allocateFrameBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class PreviewResult(
    val originalRgba: ByteBuffer,
    val width: Int,
    val height: Int,
    val upscaledRgba: ByteBuffer,
    val outWidth: Int,
    val outHeight: Int,
    val upscaleMillis: Long,
    val activeBackend: Backend,
    /** Actual timestamp of the rendered frame. */
    val frameUs: Long,
)

/** Upscales a single frame with the job settings to show quality and measured speed. */
class PreviewService(private val modelStore: ModelStore) {

    /** @throws com.supervideo.core.upscale.UpscaleException when the backend/model cannot run. */
    suspend fun render(sourcePath: String, info: VideoInfo, settings: UpscaleSettings, atUs: Long): PreviewResult =
        withContext(Dispatchers.IO) {
            val target = Rational.rescale(atUs.coerceAtLeast(0), Rational.MICROSECONDS, info.timeBase) + info.startPts
            val (frame, pts) = FrameDecoder(sourcePath, info).use { decoder ->
                val buffer = allocateFrameBuffer(decoder.width, decoder.height)
                decoder.seekTo(target)
                var found: Long? = null
                var last: Long? = null
                while (true) {
                    val pts = decoder.next(buffer) ?: break
                    last = pts
                    if (pts >= target) {
                        found = pts
                        break
                    }
                }
                val pts = found ?: last ?: throw PipelineException("No frame at the requested time")
                buffer to pts
            }
            val width = info.displayWidth
            val height = info.displayHeight
            val model = modelStore.read(settings.model)
            UpscaleSession.open(model, settings.model.scale, width, height, settings).use { session ->
                val output = allocateFrameBuffer(session.outputWidth, session.outputHeight)
                val cancel = AtomicBoolean(false)
                fun timedUpscale(): Long {
                    val began = System.nanoTime()
                    session.upscale(frame, output, cancel)
                    return (System.nanoTime() - began) / 1_000_000
                }
                // The first inference includes backend warm-up (GPU pipeline/shader creation), so time a
                // second pass when that is cheap enough; slow models report the single cold pass.
                val first = timedUpscale()
                val ms = if (first < WARM_UP_LIMIT_MS) timedUpscale() else first
                PreviewResult(
                    originalRgba = frame,
                    width = width,
                    height = height,
                    upscaledRgba = output,
                    outWidth = session.outputWidth,
                    outHeight = session.outputHeight,
                    upscaleMillis = ms,
                    activeBackend = session.activeBackend,
                    frameUs = Rational.rescale(pts - info.startPts, info.timeBase, Rational.MICROSECONDS),
                )
            }
        }

    private companion object {
        const val WARM_UP_LIMIT_MS = 5_000L
    }
}
