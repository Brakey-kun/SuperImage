#ifndef SUPERVIDEO_SESSION_H
#define SUPERVIDEO_SESSION_H

#include <memory>

#include "mnn_model.h"
#include "image_tile_interpreter.h"

/** A model + interpreter sized for one frame geometry, reused for every frame of a job. */
struct UpscaleSession {
    mnn_model model;
    std::unique_ptr<ImageTileInterpreter> interpreter;
    int scale;
    int padding;
    int width;
    int height;

    /**
     * @throws ImageTileInterpreterException on MNN failures or when the model output does not match [scale].
     */
    static std::unique_ptr<UpscaleSession> create(
            mnn_model model,
            int scale,
            int width,
            int height,
            int tile_size,
            int padding,
            int backend,
            int precision,
            int threads);
};

#endif //SUPERVIDEO_SESSION_H
