package com.supervideo.core

import com.supervideo.core.model.UpscaleModel
import com.supervideo.core.settings.UpscaleSettings
import com.supervideo.core.upscale.Backend
import com.supervideo.core.upscale.NativeError
import com.supervideo.core.upscale.UpscaleException
import com.supervideo.core.upscale.UpscaleSession
import com.supervideo.core.video.allocateFrameBuffer
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeUpscalerTest {

    private val width = 96
    private val height = 48

    @BeforeTest
    fun setUp() = TestNative.requireNative()

    /** RGBA gradient that increases along x only: R = x * 255 / 95, G = 128, B = 40. */
    private fun gradient(): ByteBuffer {
        val buffer = allocateFrameBuffer(width, height)
        for (y in 0 until height) for (x in 0 until width) {
            val i = (y * width + x) * 4
            buffer.put(i, (x * 255 / (width - 1)).toByte())
            buffer.put(i + 1, 128.toByte())
            buffer.put(i + 2, 40.toByte())
            buffer.put(i + 3, 255.toByte())
        }
        return buffer
    }

    private fun settings(model: UpscaleModel) =
        UpscaleSettings(model = model, backend = Backend.CPU, tileSize = 64, tilePadding = 8)

    private fun checkUpscale(model: UpscaleModel) {
        val scale = model.scale
        UpscaleSession.open(TestNative.modelStore.read(model), scale, width, height, settings(model)).use { session ->
            assertEquals(width * scale, session.outputWidth)
            assertEquals(height * scale, session.outputHeight)
            val input = gradient()
            val output = allocateFrameBuffer(session.outputWidth, session.outputHeight)
            session.upscale(input, output, AtomicBoolean(false))

            val outW = session.outputWidth
            val outH = session.outputHeight
            fun red(x: Int, y: Int) = output.get((y * outW + x) * 4).toInt() and 0xff

            // Low frequencies preserved.
            var diff = 0.0
            for (y in 0 until outH) for (x in 0 until outW) {
                diff += abs(red(x, y) - (x / scale) * 255 / (width - 1))
            }
            val meanDiff = diff / (outW * outH)
            assertTrue(meanDiff < 24, "mean |R - R_in| = $meanDiff")

            // Gradient runs along x: no drops beyond ±2 levels of model noise (anime models band
            // smooth gradients, so exact monotonicity is too strict; a garbled/mis-strided
            // tensor readback breaks it massively).
            var monotone = 0
            var pairs = 0
            for (y in 0 until outH) for (x in 0 until outW - 1) {
                pairs++
                if (red(x + 1, y) >= red(x, y) - 2) monotone++
            }
            assertTrue(monotone >= pairs * 0.95, "monotone pairs $monotone / $pairs")

            // R must be (nearly) constant down each column.
            val columnVariances = (0 until outW).map { x ->
                val column = (0 until outH).map { red(x, it).toDouble() }
                val mean = column.average()
                column.sumOf { (it - mean) * (it - mean) } / outH
            }
            assertTrue(columnVariances.average() < 4, "mean column variance ${columnVariances.average()} (max ${columnVariances.max()})")

            // Alpha forced opaque.
            assertEquals(255, output.get(3).toInt() and 0xff)
        }
    }

    @Test
    fun generalX2UpscalesUpright() = checkUpscale(UpscaleModel.GENERAL_X2V3)

    @Test
    fun animeVideoV3UpscalesUpright() = checkUpscale(UpscaleModel.ANIME_VIDEO_V3)

    @Test
    fun wrongScaleIsRejected() {
        val model = UpscaleModel.ANIME_VIDEO_V3
        val error = assertFailsWith<UpscaleException> {
            UpscaleSession.open(TestNative.modelStore.read(model), 2, width, height, settings(model))
        }
        assertEquals(NativeError.MODEL_SCALE_MISMATCH, error.error)
    }

    @Test
    fun cancelFlagStopsBetweenTiles() {
        val model = UpscaleModel.GENERAL_X2V3
        UpscaleSession.open(TestNative.modelStore.read(model), 2, width, height, settings(model)).use { session ->
            val error = assertFailsWith<UpscaleException> {
                session.upscale(gradient(), allocateFrameBuffer(width * 2, height * 2), AtomicBoolean(true))
            }
            assertEquals(NativeError.CANCELLED, error.error)
        }
    }
}
