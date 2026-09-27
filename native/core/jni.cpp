// JNI bindings for com.supervideo.core.upscale.NativeUpscaler (shared by Android and desktop).

#include <jni.h>

#include <cstdint>
#include <new>

#include "session.h"
#include "upscaling.h"

namespace {

constexpr int kMinTileSize = 16;

UpscaleSession* from_handle(jlong handle) {
    return reinterpret_cast<UpscaleSession*>(static_cast<intptr_t>(handle));
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
        jint threads) {
    if (model == nullptr || scale <= 0 || frame_width <= 0 || frame_height <= 0 ||
        tile_size < kMinTileSize || tile_padding < 0 || tile_padding * 2 >= tile_size) {
        return -InvalidArgs;
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
        return -static_cast<jlong>(e.error);
    } catch (const std::bad_alloc&) {
        return -CreateBackendFailed;
    } catch (...) {
        return -CreateInterpreterFailed;
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
    } catch (...) {
        // Never let a C++ exception unwind through the JNI frame.
        return CreateBackendFailed;
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

}
