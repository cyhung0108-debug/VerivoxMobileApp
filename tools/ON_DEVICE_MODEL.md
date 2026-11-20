# VeriVox on-device model (first phase)

The Android app now contains an ONNX Runtime CPU path. The converted model is
intentionally not included in Git because it is large. The model is about
315.7 million parameters; the original FP32 checkpoint is roughly 1.26 GB.
The current local parity-test APK asset is the dynamic FP32 export at about
1.26 GB. It is intentionally large so probability comparisons with the
desktop/cloud PyTorch model are meaningful. A dynamic INT8 export of about
356 MB is kept as an optional size/runtime experiment, not an accuracy claim.

## Export

Use the Python environment that already loads the VeriVox model. From the
Android repository root, Command Prompt syntax is:

```bat
"C:\path\to\asvspoof\venv\Scripts\python.exe" -m pip install -r tools\requirements-export.txt
"C:\path\to\asvspoof\venv\Scripts\python.exe" tools\export_onnx.py ^
  --program-dir "C:\path\to\asvspoof\program" ^
  --output "app\src\main\assets\models\verivox_model.onnx"
```

PowerShell uses `&` before a quoted executable path:

```powershell
& "C:\path\to\asvspoof\venv\Scripts\python.exe" -m pip install -r tools\requirements-export.txt
& "C:\path\to\asvspoof\venv\Scripts\python.exe" tools\export_onnx.py `
  --program-dir "C:\path\to\asvspoof\program" `
  --output "app\src\main\assets\models\verivox_model.onnx"
```

The exporter performs a PyTorch-vs-wrapper parity check first. It refuses to
write the ONNX file when the maximum logit difference is above `1e-4`.

For the bounded variable-length mobile path, add `--dynamic`:

```powershell
& "C:\path\to\asvspoof\venv\Scripts\python.exe" tools\export_onnx.py `
  --program-dir "C:\path\to\asvspoof\program" `
  --output "tools\model_artifacts\verivox_model_dynamic_fp32.onnx" `
  --dynamic
```

The dynamic graph accepts a symbolic sample axis. The Android client accepts
at least 16,000 samples and keeps recordings up to 128,000 samples (8 seconds
at 16 kHz) in one call so they follow the cloud one-shot path. Longer
recordings are split into sequential windows to bound memory. The exporter does
not enforce this policy in ONNX Runtime. The dynamic graph was checked on CPU
at 16,000, 49,152 and 64,000 samples; ONNX Runtime
logits differed from the PyTorch wrapper by less than `1e-5` on those checks.
The default command remains the fixed `[1, 64000]` export and is kept as a
fallback if a target phone has an operator or memory issue with dynamic input.

The `program` folder must contain the composite `model.safetensors`,
`model.py`, `wav2vec2-xls-r-300m-model/`, and
`wav2vec2-xls-r-300m-feature/`. Do not point it at only one of the two
backbone folders.

## Optional INT8 experiment

The FP32 model is useful for the first correctness check but is about 1.26 GB.
For an 8 GB phone, create a conservative dynamic quantized model:

```powershell
& "C:\path\to\asvspoof\venv\Scripts\python.exe" tools\quantize_onnx.py `
  tools\model_artifacts\verivox_model_dynamic_fp32.onnx `
  app\src\main\assets\models\verivox_model.onnx
```

This quantizes MatMul/Gemm weights only. It is not automatically accepted as
the final model: compare its logits and labels with the FP32 model on real
bonafide and spoof WAV samples first. Quantization must be run against the
chosen fixed or dynamic source; keep the FP32 export under
`tools/model_artifacts/` as the correctness reference.

In the earlier smoke test, dynamic INT8 changed `recording_1.wav` from the
FP32/cloud real probability of about `0.2787` to about `0.3294`. That is a
useful size experiment, not an accuracy-approved replacement. The APK asset is
the dynamic FP32 graph is now the APK parity asset. The fixed INT8 graph is
preserved as `tools/model_artifacts/verivox_model_fixed_int8.onnx`; the dynamic
INT8 graph remains an optional experiment until a larger test set shows that
the quantized drift is acceptable.

To reproduce the optional INT8 asset after quantization:

```powershell
Copy-Item tools\model_artifacts\verivox_model_dynamic_int8.onnx `
  app\src\main\assets\models\verivox_model.onnx -Force
```

## Android deployment

The app expects a file named `verivox_model.onnx`. For local smoke testing it
can be copied to the app's private `filesDir`; alternatively put it under
`app/src/main/assets/models/` before building. The repository ignores `*.onnx`
on purpose, so explicitly decide whether the model should be committed or
distributed as a separate Release asset.

The bounded dynamic Android path accepts WAV/PCM, converts to 16 kHz mono,
applies the Wav2Vec2 normalization, and sends each recording up to 8 seconds
to the dynamic graph as one call without repeating a short clip. Recordings
longer than 8 seconds are sent as sequential 4-second windows and their logits
are combined on the phone. This keeps a 4.096-second recording on the same
whole-input path as the cloud API.
The fixed fallback instead repeats the final short segment to 4 seconds. Both
paths apply the same `real >= 0.50` policy as the current API. Cloud mode
remains available.

Do not claim that every 8 GB device is supported until a real ARM64 8 GB phone
has passed a full model-load and inference test. Android app heap limits, free
storage, ABI, and ONNX operator support all matter in addition to total RAM.
