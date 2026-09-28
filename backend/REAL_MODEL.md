# Running the real local model

The original model artifacts stay in the `For-fyp` project. The existing
virtual environment already contains the required Python packages.

Stop the mock server first. Copy `local_config.example.py` to the ignored
`local_config.py` and set `PROGRAM_DIR` to the folder containing the trained
model. Then run from the Android project root using the Python environment that
has the model dependencies:

```powershell
py backend\real_server.py
```

The first startup loads roughly 2.5 GB of model weights and can take a while.
Check readiness at:

```text
http://127.0.0.1:5000/health
```

The Android client uses the same `/v1/analyze` endpoint. The response should
show `model_version: wav2vec2-xls-r-300m-v1` instead of `mock-v0`.

`POST /v1/analyze` accepts one multipart field named `file`. It accepts WAV,
FLAC, MP3, WebM, M4A and OGG. MP3, WebM, M4A and OGG are converted with
FFmpeg to temporary 16 kHz mono WAV before inference. Temporary files are
deleted after the request.

The successful response includes:

```json
{
  "status": "completed",
  "label": "genuine",
  "prob_real": 0.82,
  "prob_fake": 0.18,
  "processing_time_ms": 1432,
  "inference_time_ms": 1198,
  "request_id": "local-..."
}
```

`processing_time_ms` is measured by the computer from the API request arriving
until the analysis response is prepared. It includes temporary-file handling,
format conversion, resampling and model inference. `inference_time_ms` is the
model call itself. The Android app separately measures the network round trip
from sending the file until the response is received.

Errors return JSON with `error`, `detail`, `request_id` and, when available,
`processing_time_ms`. The server limits an upload to 10 MB.

The API returns the model probabilities and the `decision_threshold` used for
the displayed label. It is currently `0.50`. The old upload/record web pages
used `0.10`, so change `REAL_THRESHOLD` in `backend/real_server.py` only if
the mobile prototype must reproduce that legacy UI policy.
