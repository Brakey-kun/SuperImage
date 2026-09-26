//
// Created by Zhenxiang Chen on 06/02/23.
//

#include <thread>
#include <utility>
#include <vector>

#include "image_tile_interpreter.h"

namespace {

/**
 * (type, backupType) pairs tried in order until MNN creates a session. AUTO walks down to CPU so
 * devices without Vulkan and OpenCL still work; explicit choices fall back to CPU only.
 */
std::vector<std::pair<MNNForwardType, MNNForwardType>> backend_candidates(int backend) {
    switch (backend) {
        case BackendVulkan:
            return {{MNN_FORWARD_VULKAN, MNN_FORWARD_CPU}};
        case BackendOpenCL:
            return {{MNN_FORWARD_OPENCL, MNN_FORWARD_CPU}};
        case BackendCpu:
            return {{MNN_FORWARD_CPU, MNN_FORWARD_CPU}};
        case BackendAuto:
        default:
            return {
                    {MNN_FORWARD_VULKAN, MNN_FORWARD_OPENCL},
                    {MNN_FORWARD_OPENCL, MNN_FORWARD_CPU},
                    {MNN_FORWARD_CPU, MNN_FORWARD_CPU},
            };
    }
}

MNN::BackendConfig::PrecisionMode precision_mode(int precision) {
    switch (precision) {
        case 1:
            return MNN::BackendConfig::Precision_Normal;
        case 2:
            return MNN::BackendConfig::Precision_High;
        case 0:
        default:
            return MNN::BackendConfig::Precision_Low;
    }
}

}

ImageTileInterpreter::ImageTileInterpreter(
        const mnn_model* model,
        const image_dimensions tile_dimensions,
        const int scale,
        const int backend,
        const int precision,
        const int threads) : tile_dimensions(tile_dimensions) {
    MNN::ScheduleConfig config;
    MNN::BackendConfig backendConfig;
    backendConfig.memory = MNN::BackendConfig::Memory_High;
    backendConfig.power = MNN::BackendConfig::Power_High;
    backendConfig.precision = precision_mode(precision);
    config.backendConfig = &backendConfig;
    config.numThread = threads > 0 ? threads : static_cast<int>(std::thread::hardware_concurrency());

    interpreter = MNN::Interpreter::createFromBuffer(model->data(), model->size());
    if (interpreter == nullptr) {
        throw ImageTileInterpreterException(CreateInterpreterFailed);
    }

    for (const auto& [type, backup] : backend_candidates(backend)) {
        config.type = type;
        config.backupType = backup;
        session = interpreter->createSession(config);
        if (session != nullptr) {
            break;
        }
    }
    if (session == nullptr) {
        release();
        throw ImageTileInterpreterException(CreateBackendFailed);
    }

    interpreter_input = interpreter->getSessionInput(session, nullptr);
    // NCHW: the tile is fed as (height, width) so the model sees the image upright.
    interpreter->resizeTensor(
            interpreter_input,
            1,
            REALESRGAN_IMAGE_CHANNELS,
            tile_dimensions.height,
            tile_dimensions.width);
    interpreter->resizeSession(session);
    interpreter_output = interpreter->getSessionOutput(session, nullptr);

    int backend_types[4] = {MNN_FORWARD_CPU, MNN_FORWARD_CPU, MNN_FORWARD_CPU, MNN_FORWARD_CPU};
    interpreter->getSessionInfo(session, MNN::Interpreter::BACKENDS, backend_types);
    active_backend = backend_types[0];

    const auto out_shape = interpreter_output->shape();
    if (out_shape.size() != 4 ||
        out_shape[1] != REALESRGAN_IMAGE_CHANNELS ||
        out_shape[2] != tile_dimensions.height * scale ||
        out_shape[3] != tile_dimensions.width * scale) {
        release();
        throw ImageTileInterpreterException(ModelScaleMismatch);
    }

    input_tensor = new MNN::Tensor(interpreter_input, MNN::Tensor::CAFFE);
    output_tensor = new MNN::Tensor(interpreter_output, MNN::Tensor::CAFFE);

    input_buffer = input_tensor->host<float>();
    output_buffer = output_tensor->host<float>();
}

void ImageTileInterpreter::inference() const {
    interpreter_input->copyFromHostTensor(input_tensor);
    interpreter->runSession(session);
    interpreter_output->copyToHostTensor(output_tensor);
}

void ImageTileInterpreter::release() {
    if (input_tensor != nullptr) {
        MNN::Tensor::destroy(input_tensor);
        input_tensor = nullptr;
    }
    if (output_tensor != nullptr) {
        MNN::Tensor::destroy(output_tensor);
        output_tensor = nullptr;
    }
    if (interpreter != nullptr) {
        if (session != nullptr) {
            interpreter->releaseSession(session);
            session = nullptr;
        }
        MNN::Interpreter::destroy(interpreter);
        interpreter = nullptr;
    }
}

ImageTileInterpreter::~ImageTileInterpreter() {
    release();
}
