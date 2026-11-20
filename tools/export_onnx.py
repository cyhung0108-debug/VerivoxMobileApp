"""Export the VeriVox XLS-R classifier to an ONNX model.

This exporter intentionally leaves the original ASVspoof/VeriVox project
untouched. It imports the existing ``model.py`` from ``--program-dir``, loads
the composite checkpoint, and exports one input/output graph:

    input_values: float32 [1, 64000]              (default/fixed mode)
    input_values: float32 [1, audio_samples]     (--dynamic mode)
    logits:       float32 [1, 2]       # index 0=fake, index 1=real

    The Android app performs 16 kHz mono conversion and the same waveform
    normalization as Wav2Vec2FeatureExtractor before calling this graph.

Dynamic mode is bounded by the caller (the Android app uses up to 128,000
samples for one inference). It does not make arbitrary long audio cheap: the
mobile client still processes longer recordings as sequential chunks.
"""

from __future__ import annotations

import argparse
import gc
import importlib.util
import math
import sys
from pathlib import Path

import torch
import torch.nn as nn
from safetensors.torch import load_file
from transformers import Wav2Vec2FeatureExtractor


SAMPLE_COUNT = 64_000


def load_model(program_dir: Path) -> nn.Module:
    model_file = program_dir / "model.safetensors"
    if not model_file.exists():
        raise FileNotFoundError(f"Missing classifier checkpoint: {model_file}")

    sys.path.insert(0, str(program_dir))
    try:
        model_module = importlib.import_module("model")
        model = model_module.HFReadyModel(device="cpu")
    finally:
        sys.path.remove(str(program_dir))

    state_dict = load_file(str(model_file), device="cpu")
    model.load_state_dict(state_dict, strict=True)
    del state_dict
    gc.collect()
    model.eval()
    return model


class ExportableVeriVox(nn.Module):
    """The trained model without the Python-only feature extractor call."""

    def __init__(self, trained_model: nn.Module, dynamic_pool: bool = False) -> None:
        super().__init__()
        self.model = trained_model
        self.dynamic_pool = dynamic_pool

    @staticmethod
    def _dynamic_adaptive_pool(emb: torch.Tensor) -> torch.Tensor:
        """Reproduce AdaptiveAvgPool2d((200, C)) with symbolic time length.

        XLS-R returns ``[B, T, C]`` and the classifier expects 200 time bins.
        ``torch.nn.AdaptiveAvgPool2d`` is exact but is awkward for the legacy
        ONNX exporter when T is symbolic. For each output bin i, PyTorch's
        adaptive pooling averages the half-open input interval

            start = floor(i*T/200)
            end   = ceil((i+1)*T/200)       # exclusive

        The index arithmetic below stays in the graph and Gather handles the
        dynamic T. It is exactly equivalent for positive T, including T < 200.
        """
        shape = torch._shape_as_tensor(emb)
        batch = shape[0]
        time_steps = shape[1]
        feature_dim = shape[2]

        bins = torch.arange(200, device=emb.device, dtype=torch.int64)
        starts = torch.div(bins * time_steps, 200, rounding_mode="floor")
        # ceil(n / 200) == floor((n + 199) / 200) for n >= 0.
        ends = torch.div((bins + 1) * time_steps + 199, 200, rounding_mode="floor")

        # Use a prefix sum so a bin containing more than two frames is still
        # averaged exactly. Averaging only the first and last frame is close
        # for T near 200, but is wrong for longer clips with more than 200
        # feature frames where adaptive pooling creates wider bins.
        prefix = torch.cumsum(emb, dim=1)
        prefix = torch.cat((torch.zeros_like(emb[:, :1, :]), prefix), dim=1)

        # Expand [200] indices to [B, 200, C] for torch.gather(dim=1).
        start_index = starts.reshape(1, 200, 1).expand(batch, 200, feature_dim)
        end_index = ends.reshape(1, 200, 1).expand(batch, 200, feature_dim)
        start_values = torch.gather(prefix, 1, start_index)
        end_values = torch.gather(prefix, 1, end_index)
        counts = (ends - starts).reshape(1, 200, 1).to(dtype=emb.dtype)
        return (end_values - start_values) / counts

    def forward(self, input_values: torch.Tensor) -> torch.Tensor:
        # The Android preprocessing already performs do_normalize=True. The
        # original model's FeatureExtractor is intentionally bypassed here so
        # it does not become a Python operation inside the ONNX graph.
        emb = self.model.ssl.wav2vec2_model(input_values).last_hidden_state

        # For a 4-second/64,000-sample input XLS-R produces 199 frames. The
        # original AdaptiveAvgPool2d maps 199 -> 200, which the legacy ONNX
        # exporter rejects because the output size is not a divisor. Rebuild
        # the same adaptive-average bins. Dynamic mode keeps the time length
        # symbolic; fixed mode uses Python slices for a simpler graph.
        if self.dynamic_pool:
            x = self._dynamic_adaptive_pool(emb)
        else:
            input_time = emb.shape[1]
            pooled_bins = []
            for index in range(200):
                start = math.floor(index * input_time / 200)
                end = math.ceil((index + 1) * input_time / 200)
                pooled_bins.append(emb[:, start:end, :].mean(dim=1, keepdim=True))
            x = torch.cat(pooled_bins, dim=1)
        x = self.model.fc1(x)
        batch, time_steps, feature_dim = x.size()
        x = x.reshape(-1, feature_dim)
        x = self.model.bn1(x)
        x = x.reshape(batch, time_steps, feature_dim)
        x = self.model.relu1(x)
        x = self.model.fc2(x)
        x = x.transpose(1, 2)
        x = self.model.resnet_2(x)
        x = x.transpose(1, 2)

        x_4d = x.unsqueeze(1)
        x_tplus = self.model.conv1(x_4d)
        difference = x_tplus[:, :, 1:, :] - x_4d[:, :, :-1, :]
        difference = torch.nn.functional.pad(difference, (0, 0, 0, 1), value=0.0)

        p1 = self.model.conv2(difference)
        p2 = self.model.downsample(p1)
        p2 = self.model.relu3(p2)
        p2 = self.model.conv3(p2)
        p2 = self.model.upsample(p2)

        gate = self.model.conv4(p1 + p2).squeeze(1)
        gate = self.model.sigmoid(gate)
        x = gate * x
        x = self.model.dense1(x)
        return self.model.dense2(x.squeeze(-1))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--program-dir",
        type=Path,
        required=True,
        help="Folder containing model.py, model.safetensors, and XLS-R folders",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("app/src/main/assets/models/verivox_model.onnx"),
        help="Output ONNX path; *.onnx is ignored by Git until explicitly added",
    )
    parser.add_argument("--opset", type=int, default=17)
    parser.add_argument(
        "--dynamic",
        action="store_true",
        help=(
            "Export a symbolic audio-sample axis. The graph is used by the "
            "Android client for bounded calls up to 128,000 samples; longer "
            "recordings are chunked by the client."
        ),
    )
    args = parser.parse_args()

    program_dir = args.program_dir.resolve()
    feature_dir = program_dir / "wav2vec2-xls-r-300m-feature"
    if not feature_dir.exists():
        raise FileNotFoundError(f"Missing feature extractor directory: {feature_dir}")

    print(f"Loading VeriVox model from {program_dir}")
    trained_model = load_model(program_dir)
    export_model = ExportableVeriVox(trained_model, dynamic_pool=args.dynamic).eval()

    # Produce the exact normalized shape that Android will send. The parity
    # check catches accidental changes to the custom classifier head before a
    # potentially very large ONNX file is written.
    feature_extractor = Wav2Vec2FeatureExtractor.from_pretrained(str(feature_dir))
    # FeatureExtractor expects one raw waveform here and adds the batch
    # dimension itself. Passing [1, samples] would create [1, 1, samples].
    raw = torch.randn(SAMPLE_COUNT, dtype=torch.float32)
    normalized = feature_extractor(
        raw,
        sampling_rate=16_000,
        padding=True,
        return_tensors="pt",
    ).input_values
    with torch.inference_mode():
        reference_logits = trained_model(normalized)
        wrapper_logits = export_model(normalized)
    max_error = (reference_logits - wrapper_logits).abs().max().item()
    print(f"Parity check max absolute logit error: {max_error:.8g}")
    if max_error > 1e-4:
        raise RuntimeError("Parity check failed; refusing to export a mismatched graph")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    mode = "dynamic input [1, audio_samples]" if args.dynamic else f"fixed input [1, {SAMPLE_COUNT}]"
    print(f"Exporting {mode} to {args.output}")
    dynamic_axes = {"input_values": {1: "audio_samples"}} if args.dynamic else None
    torch.onnx.export(
        export_model,
        (normalized,),
        str(args.output),
        input_names=["input_values"],
        output_names=["logits"],
        opset_version=args.opset,
        do_constant_folding=True,
        dynamo=False,
        dynamic_axes=dynamic_axes,
    )
    print(f"Wrote {args.output} ({args.output.stat().st_size:,} bytes)")
    print("Output convention: logits[0]=fake, logits[1]=real")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
