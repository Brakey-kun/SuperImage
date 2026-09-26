#include "session.h"

#include <algorithm>

std::unique_ptr<UpscaleSession> UpscaleSession::create(
        mnn_model model,
        const int scale,
        const int width,
        const int height,
        const int tile_size,
        const int padding,
        const int backend,
        const int precision,
        const int threads) {
    auto session = std::make_unique<UpscaleSession>();
    session->model = std::move(model);
    session->scale = scale;
    session->padding = padding;
    session->width = width;
    session->height = height;
    const image_dimensions tile{
            std::min(tile_size, width),
            std::min(tile_size, height),
    };
    session->interpreter = std::make_unique<ImageTileInterpreter>(
            &session->model, tile, scale, backend, precision, threads);
    return session;
}
