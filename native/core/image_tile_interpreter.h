//
// Created by Zhenxiang Chen on 06/02/23.
//

#ifndef SUPERVIDEO_IMAGE_TILE_INTERPRETER_H
#define SUPERVIDEO_IMAGE_TILE_INTERPRETER_H

#include <exception>

#include "MNN/Interpreter.hpp"

#include "mnn_model.h"
#include "image_dimensions.h"

#define REALESRGAN_IMAGE_CHANNELS 3

/** Error codes shared with Kotlin `NativeError`. */
enum NativeError {
    CreateInterpreterFailed = 1,
    CreateBackendFailed = 2,
    InvalidArgs = 3,
    Cancelled = 4,
    BufferTooSmall = 5,
    ModelScaleMismatch = 6,
    NativeException = 7,
};

class ImageTileInterpreterException : public std::exception {

public:
    const NativeError error;

    explicit ImageTileInterpreterException(NativeError error) : error(error) {}

    const char* what() const noexcept override {
        switch (error) {
            case CreateInterpreterFailed:
                return "Failed to create MNN interpreter";
            case CreateBackendFailed:
                return "Failed to create MNN backend";
            case ModelScaleMismatch:
                return "Model output size does not match the declared scale";
            default:
                return "Upscaling error";
        }
    }
};

/** Requested backend, matches Kotlin `Backend` ordinal values. */
enum RequestedBackend {
    BackendAuto = 0,
    BackendVulkan = 1,
    BackendOpenCL = 2,
    BackendCpu = 3,
};

/**
 * One MNN session sized for a fixed input tile, reused for every tile of every frame.
 * Tensors are NCHW: (1, 3, tile.height, tile.width) in, (1, 3, tile.height * scale, tile.width * scale) out.
 */
class ImageTileInterpreter {

public:
    ImageTileInterpreter(const mnn_model* model, image_dimensions tile_dimensions, int scale,
                         int backend, int precision, int threads);
    ~ImageTileInterpreter();

    ImageTileInterpreter(const ImageTileInterpreter&) = delete;
    ImageTileInterpreter& operator=(const ImageTileInterpreter&) = delete;

    const image_dimensions tile_dimensions;

    float* input_buffer;
    float* output_buffer;

    /** MNN forward type actually used by the session (MNN_FORWARD_CPU = 0, OPENCL = 3, VULKAN = 7). */
    int active_backend;

    void inference() const;

private:
    MNN::Interpreter* interpreter = nullptr;
    MNN::Session* session = nullptr;
    MNN::Tensor* interpreter_input = nullptr;
    MNN::Tensor* interpreter_output = nullptr;
    MNN::Tensor* input_tensor = nullptr;
    MNN::Tensor* output_tensor = nullptr;

    void release();
};

#endif //SUPERVIDEO_IMAGE_TILE_INTERPRETER_H
