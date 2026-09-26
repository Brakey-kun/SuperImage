package com.supervideo.core.video

import java.nio.ByteBuffer

/** Rotation of tightly packed RGBA images. */
object Rotate {

    /**
     * Rotates the `width x height` RGBA image in [src] clockwise by [degrees] (0/90/180/270) into [dst].
     * For 90/270 the result is `height x width`. Buffer positions are ignored (absolute indexing).
     */
    fun rgba(src: ByteBuffer, width: Int, height: Int, degrees: Int, dst: ByteBuffer) {
        val srcInts = src.duplicate().order(java.nio.ByteOrder.nativeOrder()).asIntBuffer()
        val dstInts = dst.duplicate().order(java.nio.ByteOrder.nativeOrder()).asIntBuffer()
        when (degrees) {
            0 -> for (i in 0 until width * height) dstInts.put(i, srcInts.get(i))
            90 -> {
                // dst is height wide: pixel (x, y) moves to (height - 1 - y, x)
                for (y in 0 until height) {
                    val row = y * width
                    val dx = height - 1 - y
                    for (x in 0 until width) dstInts.put(x * height + dx, srcInts.get(row + x))
                }
            }
            180 -> {
                val last = width * height - 1
                for (i in 0..last) dstInts.put(last - i, srcInts.get(i))
            }
            270 -> {
                // pixel (x, y) moves to (y, width - 1 - x)
                for (y in 0 until height) {
                    val row = y * width
                    for (x in 0 until width) dstInts.put((width - 1 - x) * height + y, srcInts.get(row + x))
                }
            }
            else -> throw IllegalArgumentException("Unsupported rotation $degrees")
        }
    }
}
