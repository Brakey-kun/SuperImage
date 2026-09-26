//
// Created by Zhenxiang Chen on 04/01/23.
//

#ifndef SUPERVIDEO_UPSCALING_H
#define SUPERVIDEO_UPSCALING_H

#include <cstdint>
#include <functional>

#include "Eigen/Core"

#include "image_tile_interpreter.h"

/**
 * Row-major matrix of packed pixels. Each int32 holds RGBA bytes in memory order,
 * i.e. little-endian value A<<24 | B<<16 | G<<8 | R (FFmpeg AV_PIX_FMT_RGBA, Android ARGB_8888).
 */
using PixelMatrix = Eigen::Map<Eigen::Matrix<int32_t, Eigen::Dynamic, Eigen::Dynamic, Eigen::RowMajor>>;

/**
 * Upscales [in] into [out] (which must be scale times larger on both axes) tile by tile.
 * [padding] input pixels of overlap are added on inner tile edges and cropped from the output.
 * Output alpha is forced to 255.
 *
 * @return 0 on success, NativeError::Cancelled if [cancelled] returned true between tiles.
 */
int upscale_frame(
        const ImageTileInterpreter& interpreter,
        int scale,
        int padding,
        const PixelMatrix& in,
        PixelMatrix& out,
        const std::function<bool()>& cancelled);

#endif //SUPERVIDEO_UPSCALING_H
