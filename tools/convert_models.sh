#!/usr/bin/env bash
# Converts Real-ESRGAN realesr-animevideov3 (PyTorch) -> ONNX -> MNN with the MNN 2.4.3 converter
# built from native/core/MNN, so the model matches the bundled runtime.
#
# Run from the repo root (WSL Ubuntu on Windows):  wsl -d Ubuntu bash tools/convert_models.sh
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${SUPERVIDEO_WORK:-/tmp/supervideo-convert}"
MNN_BUILD="$WORK/mnn-build"
VENV="$WORK/venv"
WEIGHTS_URL="https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-animevideov3.pth"
mkdir -p "$WORK"

need=()
command -v cmake >/dev/null || need+=(cmake)
command -v g++ >/dev/null || need+=(build-essential)
python3 -c 'import venv, ensurepip' 2>/dev/null || need+=(python3-venv)
if [ ${#need[@]} -gt 0 ]; then
    sudo apt-get update -y
    sudo apt-get install -y "${need[@]}"
fi

# 1. MNNConvert (MNN 2.4.3 fork)
if [ ! -x "$MNN_BUILD/MNNConvert" ]; then
    # Copy sources to the Linux filesystem: building from /mnt is slow and CRLF-sensitive.
    rm -rf "$WORK/MNN"
    cp -r "$REPO/native/core/MNN" "$WORK/MNN"
    cmake -S "$WORK/MNN" -B "$MNN_BUILD" \
        -DMNN_BUILD_CONVERTER=ON -DMNN_BUILD_SHARED_LIBS=OFF -DMNN_BUILD_TOOLS=OFF \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
        -DCMAKE_CXX_FLAGS="-include cstdint"
    cmake --build "$MNN_BUILD" --target MNNConvert -j"$(nproc)"
fi

# 2. PyTorch -> ONNX
if [ ! -x "$VENV/bin/python" ]; then
    python3 -m venv "$VENV"
    "$VENV/bin/pip" install --upgrade pip
    "$VENV/bin/pip" install torch --index-url https://download.pytorch.org/whl/cpu
    "$VENV/bin/pip" install onnx onnxruntime numpy
fi
[ -f "$WORK/realesr-animevideov3.pth" ] || curl -fL -o "$WORK/realesr-animevideov3.pth" "$WEIGHTS_URL"
"$VENV/bin/python" "$REPO/tools/export_srvgg_onnx.py" \
    --weights "$WORK/realesr-animevideov3.pth" --num-conv 16 --scale 4 \
    --out "$WORK/realesr-animevideov3.onnx"

# 3. ONNX -> MNN
"$MNN_BUILD/MNNConvert" -f ONNX \
    --modelFile "$WORK/realesr-animevideov3.onnx" \
    --MNNModel "$REPO/native/models/realesr-animevideov3.mnn" \
    --bizCode supervideo
ls -l "$REPO/native/models/realesr-animevideov3.mnn"
