"""Create an experimental dynamic INT8 ONNX model.

Only MatMul/Gemm weights are quantized in this first pass. This keeps the
convolution, LayerNorm and activation operators in floating point, which is a
more conservative starting point for Wav2Vec2 than quantizing every operator.
Always compare the quantized model with real audio before distributing it.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from onnxruntime.quantization import QuantType, quantize_dynamic


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    print(f"Quantizing {args.input} -> {args.output}")
    quantize_dynamic(
        model_input=str(args.input),
        model_output=str(args.output),
        weight_type=QuantType.QInt8,
        per_channel=True,
        reduce_range=False,
        op_types_to_quantize=["MatMul", "Gemm"],
    )
    print(f"Wrote {args.output} ({args.output.stat().st_size:,} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
