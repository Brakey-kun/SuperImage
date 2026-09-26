package com.supervideo.core.upscale

import com.supervideo.core.settings.UpscaleSettings
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

enum class NativeError(val code: Int, val message: String) {
    CREATE_INTERPRETER(1, "Failed to load the model"),
    CREATE_BACKEND(2, "The selected inference backend is not available on this device"),
    INVALID_ARGS(3, "Invalid tile size / padding / frame size"),
    CANCELLED(4, "Cancelled"),
    BUFFER_TOO_SMALL(5, "Frame buffer too small"),
    MODEL_SCALE_MISMATCH(6, "Model scale does not match its output size");

    companion object {
        fun fromCode(code: Int): NativeError? = entries.firstOrNull { it.code == code }
    }
}

class UpscaleException(val error: NativeError?, val code: Int) :
    Exception(error?.message ?: "Native upscaler error $code")

/** Inference backend requested by the user. Ordinal values are part of the JNI contract. */
enum class Backend(val nativeValue: Int, val displayName: String) {
    AUTO(0, "Auto (Vulkan → OpenCL)"),
    VULKAN(1, "Vulkan"),
    OPENCL(2, "OpenCL"),
    CPU(3, "CPU");

    companion object {
        /** Maps the MNN forward type reported by the session to a backend. */
        fun fromMnnForwardType(type: Int): Backend = when (type) {
            7 -> VULKAN
            3 -> OPENCL
            else -> CPU
        }
    }
}

enum class Precision(val nativeValue: Int, val displayName: String) {
    FP16(0, "FP16 (fast)"),
    FP32(1, "FP32 (accurate)"),
}

/**
 * One native MNN session sized for a fixed frame geometry; reuse it for every frame of a job.
 * Not thread-safe: call [upscale] from one thread at a time.
 */
class UpscaleSession private constructor(
    private var handle: Long,
    val width: Int,
    val height: Int,
    val scale: Int,
    val activeBackend: Backend,
) : AutoCloseable {

    val outputWidth: Int get() = width * scale
    val outputHeight: Int get() = height * scale

    /**
     * Upscales one RGBA frame. [input] holds `width * height * 4` bytes, [output] receives
     * `outputWidth * outputHeight * 4` bytes. Both must be direct buffers.
     *
     * @throws UpscaleException on failure, with [NativeError.CANCELLED] when [cancel] was set.
     */
    fun upscale(input: ByteBuffer, output: ByteBuffer, cancel: AtomicBoolean) {
        check(handle > 0) { "Session closed" }
        val result = NativeUpscaler.upscaleFrame(handle, input, output, cancel)
        if (result != 0) throw UpscaleException(NativeError.fromCode(result), result)
    }

    override fun close() {
        if (handle > 0) {
            NativeUpscaler.destroySession(handle)
            handle = 0
        }
    }

    companion object {
        /** @throws UpscaleException when the model or backend cannot be initialised. */
        fun open(model: ByteArray, modelScale: Int, width: Int, height: Int, settings: UpscaleSettings): UpscaleSession {
            val handle = NativeUpscaler.createSession(
                model = model,
                scale = modelScale,
                frameWidth = width,
                frameHeight = height,
                tileSize = settings.effectiveTileSize,
                tilePadding = settings.tilePadding,
                backend = settings.backend.nativeValue,
                precision = settings.precision.nativeValue,
                threads = settings.threads,
            )
            if (handle <= 0) {
                val code = (-handle).toInt()
                throw UpscaleException(NativeError.fromCode(code), code)
            }
            val backend = Backend.fromMnnForwardType(NativeUpscaler.activeBackend(handle))
            return UpscaleSession(handle, width, height, modelScale, backend)
        }
    }
}
