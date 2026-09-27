# SuperVideo

Just a fun little project,
an AI video upscaler for Android (arm64) and Windows (x64), forked from
[SuperImage](https://github.com/Lucchetto/SuperImage).

Video in → streaming decode → per-frame Real-ESRGAN upscale (MNN, Vulkan/OpenCL/CPU) → H.264/HEVC
encode in checkpointed segments → MP4 with the source audio stream-copied and every frame
timestamp preserved (VFR included).

And I already said it, but UP UP UP, it's an android video upscaler xD

## Features

- Models: Real-ESRGAN general v3 ×2/×4 and anime video v3 ×4 (fast, SRVGG), plus the RRDB
  x2plus/x4plus/x4plus-anime models (very slow, short clips).
- One reusable inference session per job; tunable tile size, tile padding, backend
  (Auto/Vulkan/OpenCL/CPU), precision (FP16/FP32) and threads.
- Single-frame before/after preview with measured speed and projected job time.
- Trim range, codec (H.264/HEVC), CRF or bitrate, x264/x265 preset, target output height.
- Resume after the app is killed: DONE segments are kept, decoding restarts after the last encoded frame.
- Sequential job queue; Android pauses on severe thermal status or low battery.

## Layout

| Path | Contents |
| --- | --- |
| `native/core` | C++ upscaler (MNN session reuse, tiling, JNI), MNN + Eigen submodules |
| `native/models` | `.mnn` model files |
| `native/android` | Android library building `librealesrgan.so` + MNN backends |
| `core` | Kotlin/JVM pipeline: FFmpeg (bytedeco) decode/encode/remux, jobs, settings |
| `ui` | Compose Multiplatform screens shared by both apps |
| `android` | Android app (WorkManager foreground worker) |
| `desktop` | Compose Desktop app for Windows (MinGW-built `realesrgan.dll`) |
| `tools` | Model conversion (PyTorch → ONNX → MNN 2.4.3) |

## Building

See `AGENTS.md` for prerequisites. `.\gradlew.bat packageExecutables` builds the APKs and the
Windows app into `executables/`.

## Credits and license

GPLv3 (see `LICENSE`). Based on SuperImage by Zhenxiang Chen. Models by
[Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) (BSD-3-Clause), inference by
[MNN](https://github.com/alibaba/MNN) (Apache-2.0), video I/O by FFmpeg (GPL build) through
[bytedeco JavaCPP presets](https://github.com/bytedeco/javacpp-presets).
