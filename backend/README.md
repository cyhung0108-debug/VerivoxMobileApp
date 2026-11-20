# Mobile API mock

The app team can use `mock_server.py` to check uploads and result display without model weights. The response contains fixed test values and `model_version: mock-v0`.

```powershell
python backend/mock_server.py
```

The Android emulator reaches the host at `http://10.0.2.2:5000/v1/analyze`. For genuine inference, use the [VerivoxModel backend](https://github.com/cyhung0108-debug/VerivoxModel#桌面和雲端分析).
