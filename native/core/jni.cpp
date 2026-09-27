// JNI bindings for com.supervideo.core.upscale.NativeUpscaler (shared by Android and desktop).

#include <jni.h>

#include <cstdint>
#include <cstdio>
#include <exception>
#include <new>

#include "native_log.h"

#include "session.h"
#include "upscaling.h"

namespace {

constexpr int kMinTileSize = 16;

UpscaleSession* from_handle(jlong handle) {
    return reinterpret_cast<UpscaleSession*>(static_cast<intptr_t>(handle));
}

/** Stores [code] in error_out[0] and returns the "no session" handle. */
jlong fail(JNIEnv* env, jintArray error_out, jint code) {
    if (error_out != nullptr && env->GetArrayLength(error_out) > 0) {
        env->SetIntArrayRegion(error_out, 0, 1, &code);
    }
    return 0;
}

}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_supervideo_core_upscale_NativeUpscaler_createSession(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray model,
        jint scale,
        jint frame_width,
        jint frame_height,
        jint tile_size,
        jint tile_padding,
        jint backend,
        jint precision,
        jint threads,
        jintArray error_out) {
    // The handle is an opaque pointer: on arm64 Android heap pointers are tagged in the top
    // byte and read as negative jlongs, so errors are reported via error_out, never the sign.
    if (model == nullptr || scale <= 0 || frame_width <= 0 || frame_height <= 0 ||
        tile_size < kMinTileSize || tile_padding < 0 || tile_padding * 2 >= tile_size) {
        return fail(env, error_out, InvalidArgs);
    }
    try {
        auto session = UpscaleSession::create(
                mnn_model_from_jbytes(env, model),
                scale,
                frame_width,
                frame_height,
                tile_size,
                tile_padding,
                backend,
                precision,
                threads);
        return static_cast<jlong>(reinterpret_cast<intptr_t>(session.release()));
    } catch (const ImageTileInterpreterException& e) {
        return fail(env, error_out, e.error);
    } catch (const std::bad_alloc&) {
        SV_LOG("createSession: out of memory");
        return fail(env, error_out, CreateBackendFailed);
    } catch (const std::exception& e) {
        SV_LOG("createSession: unexpected exception: %s", e.what());
        return fail(env, error_out, NativeException);
    } catch (...) {
        SV_LOG("createSession: unknown exception");
        return fail(env, error_out, NativeException);
    }
}

JNIEXPORT jint JNICALL
Java_com_supervideo_core_upscale_NativeUpscaler_upscaleFrame(
        JNIEnv* env,
        jobject /* this */,
        jlong handle,
        jobject in_buffer,
        jobject out_buffer,
        jobject cancel_flag) {
    UpscaleSession* session = from_handle(handle);
    if (session == nullptr || in_buffer == nullptr || out_buffer == nullptr || cancel_flag == nullptr) {
        return InvalidArgs;
    }
    auto* in_address = static_cast<int32_t*>(env->GetDirectBufferAddress(in_buffer));
    auto* out_address = static_cast<int32_t*>(env->GetDirectBufferAddress(out_buffer));
    if (in_address == nullptr || out_address == nullptr) {
        return InvalidArgs;
    }
    const jlong in_pixels = static_cast<jlong>(session->width) * session->height;
    const jlong out_pixels = in_pixels * session->scale * session->scale;
    if (env->GetDirectBufferCapacity(in_buffer) < in_pixels * 4 ||
        env->GetDirectBufferCapacity(out_buffer) < out_pixels * 4) {
        return BufferTooSmall;
    }

    jclass flag_class = env->GetObjectClass(cancel_flag);
    jmethodID get_method = env->GetMethodID(flag_class, "get", "()Z");
    env->DeleteLocalRef(flag_class);
    if (get_method == nullptr) {
        return InvalidArgs;
    }

    const PixelMatrix in(in_address, session->height, session->width);
    PixelMatrix out(out_address, static_cast<Eigen::Index>(session->height) * session->scale,
                    static_cast<Eigen::Index>(session->width) * session->scale);

    try {
        return upscale_frame(
                *session->interpreter,
                session->scale,
                session->padding,
                in,
                out,
                [env, cancel_flag, get_method]() {
                    return env->CallBooleanMethod(cancel_flag, get_method) == JNI_TRUE;
                });
    } catch (const std::exception& e) {
        // Never let a C++ exception unwind through the JNI frame.
        SV_LOG("upscaleFrame: unexpected exception: %s", e.what());
        return NativeException;
    } catch (...) {
        SV_LOG("upscaleFrame: unknown exception");
        return NativeException;
    }
}

JNIEXPORT jint JNICALL
Java_com_supervideo_core_upscale_NativeUpscaler_activeBackend(
        JNIEnv* /* env */,
        jobject /* this */,
        jlong handle) {
    UpscaleSession* session = from_handle(handle);
    return session == nullptr ? -1 : session->interpreter->active_backend;
}

JNIEXPORT void JNICALL
Java_com_supervideo_core_upscale_NativeUpscaler_destroySession(
        JNIEnv* /* env */,
        jobject /* this */,
        jlong handle) {
    delete from_handle(handle);
}

/**
 * Appends native stdout/stderr (MNN_PRINT/MNN_ERROR and SV_LOG) to [path]. Used by the desktop app,
 * where native output otherwise goes nowhere; Android native output already reaches logcat.
 */
JNIEXPORT jboolean JNICALL
Java_com_supervideo_core_upscale_NativeUpscaler_redirectNativeOutput(
        JNIEnv* env,
        jobject /* this */,
        jstring path) {
#ifdef _WIN32
    const jchar* chars = env->GetStringChars(path, nullptr);
    const auto* wide = reinterpret_cast<const wchar_t*>(chars);
    const bool out_ok = _wfreopen(wide, L"a", stdout) != nullptr;
    const bool err_ok = _wfreopen(wide, L"a", stderr) != nullptr;
    env->ReleaseStringChars(path, chars);
#else
    const char* chars = env->GetStringUTFChars(path, nullptr);
    const bool out_ok = std::freopen(chars, "a", stdout) != nullptr;
    const bool err_ok = std::freopen(chars, "a", stderr) != nullptr;
    env->ReleaseStringUTFChars(path, chars);
#endif
    // Unbuffered so a native crash does not lose the last lines.
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    std::setvbuf(stderr, nullptr, _IONBF, 0);
    return out_ok && err_ok ? JNI_TRUE : JNI_FALSE;
}

}
