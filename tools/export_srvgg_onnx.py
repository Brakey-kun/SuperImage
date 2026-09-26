"""Export a Real-ESRGAN SRVGGNetCompact checkpoint (e.g. realesr-animevideov3.pth) to ONNX.

SRVGGNetCompact is copied from Real-ESRGAN (realesrgan/archs/srvgg_arch.py):

BSD 3-Clause License

Copyright (c) 2021, Xintao Wang
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
"""

import argparse

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F


class SRVGGNetCompact(nn.Module):
    """A compact VGG-style network structure for super-resolution (Real-ESRGAN)."""

    def __init__(self, num_in_ch=3, num_out_ch=3, num_feat=64, num_conv=16, upscale=4, act_type='prelu'):
        super().__init__()
        self.num_in_ch = num_in_ch
        self.num_out_ch = num_out_ch
        self.num_feat = num_feat
        self.num_conv = num_conv
        self.upscale = upscale
        self.act_type = act_type

        self.body = nn.ModuleList()
        self.body.append(nn.Conv2d(num_in_ch, num_feat, 3, 1, 1))
        self.body.append(self._activation())
        for _ in range(num_conv):
            self.body.append(nn.Conv2d(num_feat, num_feat, 3, 1, 1))
            self.body.append(self._activation())
        self.body.append(nn.Conv2d(num_feat, num_out_ch * upscale * upscale, 3, 1, 1))
        self.upsampler = nn.PixelShuffle(upscale)

    def _activation(self):
        if self.act_type == 'relu':
            return nn.ReLU(inplace=True)
        if self.act_type == 'prelu':
            return nn.PReLU(num_parameters=self.num_feat)
        if self.act_type == 'leakyrelu':
            return nn.LeakyReLU(negative_slope=0.1, inplace=True)
        raise ValueError(self.act_type)

    def forward(self, x):
        out = x
        for layer in self.body:
            out = layer(out)
        out = self.upsampler(out)
        # Residual: nearest-upsampled input
        base = F.interpolate(x, scale_factor=self.upscale, mode='nearest')
        return out + base


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--weights', required=True)
    parser.add_argument('--num-conv', type=int, default=16)
    parser.add_argument('--num-feat', type=int, default=64)
    parser.add_argument('--scale', type=int, default=4)
    parser.add_argument('--out', required=True)
    args = parser.parse_args()

    model = SRVGGNetCompact(num_feat=args.num_feat, num_conv=args.num_conv, upscale=args.scale, act_type='prelu')
    state = torch.load(args.weights, map_location='cpu', weights_only=True)
    for key in ('params_ema', 'params'):
        if key in state:
            state = state[key]
            break
    model.load_state_dict(state, strict=True)
    model.eval()

    dummy = torch.rand(1, 3, 64, 64)
    torch.onnx.export(
        model, dummy, args.out,
        opset_version=11,
        input_names=['input'],
        output_names=['output'],
        dynamic_axes={'input': {2: 'h', 3: 'w'}, 'output': {2: 'h', 3: 'w'}},
        dynamo=False,
    )

    # Parity check on a non-square input so an H/W mix-up would show.
    import onnxruntime as ort
    sample = torch.rand(1, 3, 48, 80)
    with torch.no_grad():
        expected = model(sample).numpy()
    session = ort.InferenceSession(args.out, providers=['CPUExecutionProvider'])
    actual = session.run(['output'], {'input': sample.numpy()})[0]
    assert actual.shape == expected.shape == (1, 3, 48 * args.scale, 80 * args.scale), actual.shape
    diff = float(np.abs(actual - expected).max())
    assert diff < 1e-3, f'ONNX parity failed: max abs diff {diff}'
    print(f'exported {args.out}; parity max abs diff {diff:.2e}')


if __name__ == '__main__':
    main()
