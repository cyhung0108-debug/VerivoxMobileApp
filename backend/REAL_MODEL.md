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

The Android client uses the same `/v1/analyze` endpoint, so no Android code
change is needed for this switch. The response should show
`model_version: wav2vec2-xls-r-300m-v1` instead of `mock-v0`.

The API returns the model probabilities and the `decision_threshold` used for
the displayed label. It is currently `0.50`. The old upload/record web pages
used `0.10`, so change `REAL_THRESHOLD` in `backend/real_server.py` only if
the mobile prototype must reproduce that legacy UI policy.
