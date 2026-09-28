# VeriVox Mobile App

Prototype Android client and Python API for audio deepfake detection.

## Project layout

- `app/` — Android application (Jetpack Compose).
- `backend/mock_server.py` — lightweight API used to test the mobile flow.
- `backend/real_server.py` — API wrapper that calls the VeriVox inference model.

## Important: model files are kept outside Git

The trained model weights and the original ASVspoof project are large and may
contain local machine-specific files. They are intentionally **not** stored in
this repository. Keep them in a separate folder and configure the backend with
the `VERIVOX_PROGRAM_DIR` environment variable when deploying or running it.

For the current mobile prototype, the separate model package only needs this
structure (it does **not** need the complete old ASVspoof project or its Python
virtual environment):

```text
verivox-model-assets/
├── model.py
├── model.safetensors
├── wav2vec2-xls-r-300m-model/
│   ├── config.json
│   └── model.safetensors
└── wav2vec2-xls-r-300m-feature/
    ├── config.json
    └── preprocessor_config.json
```

After extracting that package, set `PROGRAM_DIR` in the ignored
`backend/local_config.py` (or set `VERIVOX_PROGRAM_DIR`) to the
`verivox-model-assets` folder.

Never commit passwords, API keys, private certificates, `.env` files, Python
virtual environments, or model weights.

## Local prototype

1. Open the project in Android Studio.
2. Start `backend/real_server.py` on the development computer, using the
   Python environment and model directory that contain the trained model.
3. For a physical phone, add this line to the ignored `local.properties` file,
   replacing the placeholder with the computer's current LAN address:

   ```text
   verivox.physicalDeviceAnalyzeUrl=http://YOUR_PC_LAN_IP:5000/v1/analyze
   ```

   The Android emulator uses the generic `http://10.0.2.2:5000` host mapping.
   Do not commit a personal IP address in `ApiConfig.kt`.

   To temporarily use a Cloudflare Quick Tunnel for either an emulator or a
   physical phone, add this instead (or in addition):

   ```text
   verivox.apiUrl=https://YOUR-TUNNEL.trycloudflare.com/v1/analyze
   ```

   The override takes precedence over the local emulator/phone defaults.
   The Android screen also accepts a new tunnel root URL at runtime and
   appends `/v1/analyze`, so rebuilding the app is not required whenever a
   Quick Tunnel URL changes.
4. Run the Android app and select an audio file.

The real API accepts WAV, FLAC, MP3, WebM, M4A and OGG. Non-WAV/FLAC inputs
are converted to temporary 16 kHz mono WAV files with FFmpeg before model
inference. The app displays both the network round-trip time and the server's
analysis processing time.

This HTTP server is for prototype/testing only. A public deployment should add
HTTPS, authentication, request limits, and proper secret management.
