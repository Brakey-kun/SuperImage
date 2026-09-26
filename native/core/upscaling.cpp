//
// Created by Zhenxiang Chen on 04/01/23.
//

#include "upscaling.h"

#include <algorithm>
#include <utility>

namespace {

/** Writes an RGBA tile into an NCHW float tensor (3, H, W), values in [0, 1]. */
void pixels_to_tensor(const Eigen::Block<const PixelMatrix>& tile, float* tensor) {
    const Eigen::Index height = tile.rows();
    const Eigen::Index width = tile.cols();
    const Eigen::Index plane = height * width;
    float* r = tensor;
    float* g = tensor + plane;
    float* b = tensor + 2 * plane;
    constexpr float inv = 1.0f / 255.0f;
    for (Eigen::Index y = 0; y < height; y++) {
        for (Eigen::Index x = 0; x < width; x++) {
            // Alpha is ignored
            const auto pixel = static_cast<uint32_t>(tile(y, x));
            const Eigen::Index i = y * width + x;
            r[i] = static_cast<float>(pixel & 0xff) * inv;
            g[i] = static_cast<float>((pixel >> 8) & 0xff) * inv;
            b[i] = static_cast<float>((pixel >> 16) & 0xff) * inv;
        }
    }
}

inline uint32_t to_byte(float value) {
    return static_cast<uint32_t>(std::clamp(value * 255.0f + 0.5f, 0.0f, 255.0f));
}

/**
 * Copies the region [offset_y, offset_y + dest.rows()) x [offset_x, offset_x + dest.cols())
 * of an NCHW float tensor (3, tensor_height, tensor_width) into [dest] as opaque RGBA pixels.
 */
void tensor_to_pixels(const float* tensor,
                      const int tensor_width,
                      const int tensor_height,
                      const int offset_x,
                      const int offset_y,
                      Eigen::Block<PixelMatrix>& dest) {
    const long plane = static_cast<long>(tensor_width) * tensor_height;
    const float* r = tensor;
    const float* g = tensor + plane;
    const float* b = tensor + 2 * plane;
    for (Eigen::Index y = 0; y < dest.rows(); y++) {
        const long row = (offset_y + y) * static_cast<long>(tensor_width) + offset_x;
        for (Eigen::Index x = 0; x < dest.cols(); x++) {
            const long i = row + x;
            dest(y, x) = static_cast<int32_t>(
                    0xff000000u | to_byte(b[i]) << 16 | to_byte(g[i]) << 8 | to_byte(r[i]));
        }
    }
}

/** @return (leading, trailing) overlap of the tile starting at [position] along an axis. */
std::pair<int, int> calculate_axis_padding(const int position, const int axis_size, const int tile_size, const int padding) {
    if (axis_size == tile_size) {
        // No padding needed if there a single tile for given axis
        return {0, 0};
    } else if (position == 0) {
        // First tile
        return {0, padding};
    } else if (axis_size - position <= tile_size - padding) {
        // Final tile: shift it back so it ends exactly on the image edge
        return {tile_size - (axis_size - position), 0};
    } else {
        // Tiles in between
        return {padding, padding};
    }
}

}

int upscale_frame(
        const ImageTileInterpreter& interpreter,
        const int scale,
        const int padding,
        const PixelMatrix& in,
        PixelMatrix& out,
        const std::function<bool()>& cancelled) {

    const image_dimensions& tile = interpreter.tile_dimensions;
    const int height = static_cast<int>(in.rows());
    const int width = static_cast<int>(in.cols());
    const int out_tile_width = tile.width * scale;
    const int out_tile_height = tile.height * scale;

    int y = 0;
    while (y < height) {
        const std::pair<int, int> y_padding = calculate_axis_padding(y, height, tile.height, padding);
        const int row_height = tile.height - y_padding.first - y_padding.second;

        int x = 0;
        while (x < width) {
            if (cancelled()) {
                return Cancelled;
            }
            const std::pair<int, int> x_padding = calculate_axis_padding(x, width, tile.width, padding);
            const int col_width = tile.width - x_padding.first - x_padding.second;

            // Input tile includes the overlap, which is cropped from the output below
            const Eigen::Block<const PixelMatrix> input_tile = in.block(
                    y - y_padding.first,
                    x - x_padding.first,
                    tile.height,
                    tile.width);
            pixels_to_tensor(input_tile, interpreter.input_buffer);

            interpreter.inference();

            Eigen::Block<PixelMatrix> dest = out.block(
                    static_cast<Eigen::Index>(y) * scale,
                    static_cast<Eigen::Index>(x) * scale,
                    static_cast<Eigen::Index>(row_height) * scale,
                    static_cast<Eigen::Index>(col_width) * scale);
            tensor_to_pixels(
                    interpreter.output_buffer,
                    out_tile_width,
                    out_tile_height,
                    x_padding.first * scale,
                    y_padding.first * scale,
                    dest);

            x += col_width;
        }
        y += row_height;
    }
    return 0;
}
