# VeriVox local API

This folder contains the prototype API used by the Android app. Use
`mock_server.py` only for a connection test; use `real_server.py` for the
actual VeriVox model.

From the project root, run:

```powershell
py backend\mock_server.py
```

Keep that terminal open. The mock endpoint returns a fixed result and does not
load the model.

## Real model

The model-backed service is documented in [REAL_MODEL.md](REAL_MODEL.md).
