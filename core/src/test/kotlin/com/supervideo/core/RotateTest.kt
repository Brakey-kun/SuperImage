package com.supervideo.core

import com.supervideo.core.video.Rotate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals

class RotateTest {

    private fun buffer(vararg pixels: Int): ByteBuffer =
        ByteBuffer.allocateDirect(pixels.size * 4).order(ByteOrder.nativeOrder()).apply {
            asIntBuffer().put(pixels)
        }

    private fun ByteBuffer.pixels(): IntArray = IntArray(capacity() / 4).also { asIntBuffer().get(it) }

    // 3x2 source:
    // 1 2 3
    // 4 5 6
    private val source = buffer(1, 2, 3, 4, 5, 6)

    @Test
    fun rotate90Clockwise() {
        val dst = buffer(0, 0, 0, 0, 0, 0)
        Rotate.rgba(source, 3, 2, 90, dst)
        // 2x3 result:
        // 4 1
        // 5 2
        // 6 3
        assertContentEquals(intArrayOf(4, 1, 5, 2, 6, 3), dst.pixels())
    }

    @Test
    fun rotate270Clockwise() {
        val dst = buffer(0, 0, 0, 0, 0, 0)
        Rotate.rgba(source, 3, 2, 270, dst)
        // 3 6
        // 2 5
        // 1 4
        assertContentEquals(intArrayOf(3, 6, 2, 5, 1, 4), dst.pixels())
    }

    @Test
    fun rotate180() {
        val dst = buffer(0, 0, 0, 0, 0, 0)
        Rotate.rgba(source, 3, 2, 180, dst)
        assertContentEquals(intArrayOf(6, 5, 4, 3, 2, 1), dst.pixels())
    }
}
