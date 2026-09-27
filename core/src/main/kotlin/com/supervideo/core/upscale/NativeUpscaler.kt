package com.supervideo.core.upscale

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** JNI surface of `librealesrgan` / `realesrgan.dll` (see native/core/jni.cpp). */
object NativeUpscaler {

    /**
     * @return an opaque session handle, or `0` on failure with the [NativeError] code in `errorOut[0]`.
     * The handle is a native pointer and may be negative (tagged pointers on arm64 Android).
     */
    external fun createSession(
        model: ByteArray,
        scale: Int,
        frameWidth: Int,
        frameHeight: Int,
        tileSize: Int,
        tilePadding: Int,
        backend: Int,
        precision: Int,
        threads: Int,
        errorOut: IntArray,
    ): Long

    /** @return 0 on success or a [NativeError] code. Buffers must be direct, RGBA, tightly packed. */
    external fun upscaleFrame(session: Long, inBuffer: ByteBuffer, outBuffer: ByteBuffer, cancelFlag: AtomicBoolean): Int

    /** @return MNN forward type used by the session (0 CPU, 3 OpenCL, 7 Vulkan). */
    external fun activeBackend(session: Long): Int

    external fun destroySession(session: Long)

    /** Appends native stdout/stderr (MNN diagnostics) to [path]. Desktop only; Android native output goes to logcat. */
    external fun redirectNativeOutput(path: String): Boolean
}
