"""VeriVox model-backed API for the local prototype.

The model artifacts stay in the original For-fyp project. Set
VERIVOX_PROGRAM_DIR when moving the service to another computer/server.
"""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path

from flask import Flask, jsonify, request
from werkzeug.exceptions import RequestEntityTooLarge


try:
    # This file is intentionally ignored by Git. It lets each developer keep
    # their own local model path without committing a Windows username/path.
    from local_config import PROGRAM_DIR as LOCAL_PROGRAM_DIR  # type: ignore
except ImportError:
    LOCAL_PROGRAM_DIR = ""

DEFAULT_PROGRAM_DIR = Path(
    os.environ.get(
        "VERIVOX_PROGRAM_DIR",
        LOCAL_PROGRAM_DIR or (Path(__file__).resolve().parent / "model_assets"),
    )
)
PROGRAM_DIR = DEFAULT_PROGRAM_DIR

# Import the trained model definition from the original project without
# copying the large model files into this Android workspace.
sys.path.insert(0, str(PROGRAM_DIR))

import librosa  # noqa: E402
import numpy as np  # noqa: E402
import soundfile as sf  # noqa: E402
import torch  # noqa: E402
from safetensors.torch import load_file  # noqa: E402
from transformers import Wav2Vec2FeatureExtractor  # noqa: E402

from model import HFReadyModel  # noqa: E402


app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 10 * 1024 * 1024

MODEL_VERSION = "wav2vec2-xls-r-300m-v1"
ALLOWED_AUDIO_EXTENSIONS = {".wav", ".flac", ".mp3", ".webm", ".m4a", ".ogg"}
# Decision policy is separate from the model's raw probabilities.  The
# legacy upload/record pages used 0.10, while the convert page used 0.50.
# Keep the mobile prototype conservative for now, and make the choice
# explicit so both clients can be aligned later.
REAL_THRESHOLD = 0.50
device = "cuda" if torch.cuda.is_available() else "cpu"
model = None
feature_extractor = None
model_error = None


def load_model_once() -> None:
    """Load the backbone and trained classifier exactly once at startup."""
    global model, feature_extractor, model_error

    try:
        classifier_weights = PROGRAM_DIR / "model.safetensors"
        feature_dir = PROGRAM_DIR / "wav2vec2-xls-r-300m-feature"
        backbone_dir = PROGRAM_DIR / "wav2vec2-xls-r-300m-model"

        required = [classifier_weights, feature_dir, backbone_dir]
        missing = [str(path) for path in required if not path.exists()]
        if missing:
            raise FileNotFoundError("Missing model assets: " + ", ".join(missing))

        print(f"Loading VeriVox model on {device}...")
        model = HFReadyModel(device=device).to(device)
        state_dict = load_file(str(classifier_weights), device=device)
        model.load_state_dict(state_dict, strict=True)
        model.eval()
        feature_extractor = Wav2Vec2FeatureExtractor.from_pretrained(str(feature_dir))
        print("VeriVox model loaded successfully.")
    except Exception as exc:  # keep /health available with a useful error
        model_error = f"{type(exc).__name__}: {exc}"
        print(f"Model loading failed: {model_error}")


load_model_once()


class AudioProcessingError(Exception):
    """An uploaded file could not be decoded or prepared for the model."""

    def __init__(self, detail: str, public_detail: str | None = None):
        # Keep the detailed exception for the local terminal, but never send
        # it to the phone: decoder/FFmpeg messages can contain local paths.
        super().__init__(detail)
        self.public_detail = public_detail or "音訊處理失敗，請確認檔案有效，或改用 WAV/FLAC。"


def new_request_id() -> str:
    return f"local-{uuid.uuid4().hex[:12]}"


def elapsed_ms(started: float) -> int:
    return round((time.perf_counter() - started) * 1000)


def error_response(
    error: str,
    detail: str,
    status_code: int,
    request_id: str | None = None,
    started: float | None = None,
):
    payload = {"error": error, "detail": detail[:500]}
    if request_id:
        payload["request_id"] = request_id
    if started is not None:
        payload["processing_time_ms"] = elapsed_ms(started)
    return jsonify(payload), status_code


def safe_error_detail(prefix: str, error: Exception) -> str:
    """Return a useful client message without exposing local paths/tracebacks."""
    return f"{prefix}（{type(error).__name__}）。"


@app.errorhandler(RequestEntityTooLarge)
def handle_file_too_large(_error):
    return error_response(
        "file_too_large",
        "音訊檔太大，目前上限是 10 MB。",
        413,
    )


@app.get("/health")
def health():
    return jsonify(
        {
            "status": "ok" if model is not None else "degraded",
            "service": "verivox-real",
            "model_loaded": model is not None,
            "model_version": MODEL_VERSION,
            "decision_threshold": REAL_THRESHOLD,
            "device": device,
            # The full model-loading exception can contain a local username or
            # filesystem path. Keep that detail in the server terminal only.
            "error": "模型載入失敗，請查看電腦上的 Python 視窗。" if model_error else None,
        }
    )


@app.post("/v1/analyze")
def analyze():
    started = time.perf_counter()
    request_id = new_request_id()
    if model is None or feature_extractor is None:
        return error_response(
            "model_not_loaded",
            model_error or "模型尚未載入，請查看 Python 視窗。",
            503,
            request_id,
            started,
        )

    uploaded = request.files.get("file")
    if uploaded is None or not uploaded.filename:
        return error_response(
            "missing_file",
            "請在 multipart 欄位 file 提供音訊檔。",
            400,
            request_id,
            started,
        )

    suffix = Path(uploaded.filename).suffix.lower()
    if suffix not in ALLOWED_AUDIO_EXTENSIONS:
        allowed = ", ".join(sorted(ALLOWED_AUDIO_EXTENSIONS))
        return error_response(
            "unsupported_audio_format",
            f"不支援此副檔名。目前支援：{allowed}。",
            415,
            request_id,
            started,
        )

    temporary_path = None
    normalized_path = None
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as temporary:
            uploaded.save(temporary)
            temporary_path = temporary.name

        # Reuse the old VeriVox/ASVspoof web app's FFmpeg idea for formats
        # that soundfile cannot decode directly. WAV and FLAC keep the existing
        # soundfile + librosa path; MP3/M4A/WebM/OGG become temporary WAV files.
        read_path = temporary_path
        if suffix not in {".wav", ".flac"}:
            normalized_path = f"{temporary_path}.normalized.wav"
            command = [
                "ffmpeg",
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-i",
                temporary_path,
                "-vn",
                "-ar",
                "16000",
                "-ac",
                "1",
                "-c:a",
                "pcm_s16le",
                normalized_path,
            ]
            try:
                converted = subprocess.run(
                    command,
                    capture_output=True,
                    text=True,
                    timeout=90,
                    check=False,
                )
            except FileNotFoundError as exc:
                raise AudioProcessingError(
                    str(exc),
                    "找不到 FFmpeg。請先安裝 FFmpeg，或先使用 WAV/FLAC 檔案。",
                ) from exc
            if converted.returncode != 0 or not os.path.exists(normalized_path):
                detail = (converted.stderr or "FFmpeg 沒有產生 WAV 檔").strip()
                raise AudioProcessingError(
                    detail,
                    "音訊轉換失敗，請確認檔案可播放，或改用 WAV/FLAC。",
                )
            read_path = normalized_path

        # Keep this equivalent in spirit to the desktop app: average channels
        # explicitly and resample only when the source is not already 16 kHz.
        try:
            waveform, sample_rate = sf.read(read_path)
        except Exception as exc:
            raise AudioProcessingError(
                str(exc),
                "音訊讀取失敗，檔案可能損壞或沒有有效音軌。",
            ) from exc
        if waveform.ndim > 1:
            waveform = waveform.mean(axis=1)
        if sample_rate != 16000:
            waveform = librosa.resample(
                waveform,
                orig_sr=sample_rate,
                target_sr=16000,
            )
            sample_rate = 16000
        waveform = np.asarray(waveform, dtype=np.float32)
        inputs = feature_extractor(
            waveform,
            sampling_rate=sample_rate,
            return_tensors="pt",
            padding=True,
        )
        input_values = inputs.input_values.to(device)

        inference_started = time.perf_counter()
        with torch.inference_mode():
            logits = model(input_values=input_values)
        inference_time_ms = round((time.perf_counter() - inference_started) * 1000, 2)

        probabilities = torch.softmax(logits, dim=-1).detach().cpu().numpy()[0]
        prob_fake = float(probabilities[0])
        prob_real = float(probabilities[1])
        label = "genuine" if prob_real >= REAL_THRESHOLD else "fake"

        return jsonify(
            {
                "request_id": request_id,
                "status": "completed",
                "label": label,
                "prob_real": prob_real,
                "prob_fake": prob_fake,
                "model_version": MODEL_VERSION,
                "decision_threshold": REAL_THRESHOLD,
                "audio_sample_rate": int(sample_rate),
                "audio_num_samples": int(waveform.shape[0]),
                "audio_duration_seconds": round(float(waveform.shape[0] / sample_rate), 3),
                "processing_time_ms": elapsed_ms(started),
                "inference_time_ms": inference_time_ms,
            }
        )
    except subprocess.TimeoutExpired:
        print(f"Audio conversion timed out: request_id={request_id}")
        return error_response(
            "audio_conversion_timeout",
            "音訊格式轉換逾時，請使用較短的音訊或先轉成 WAV。",
            422,
            request_id,
            started,
        )
    except AudioProcessingError as exc:
        print(f"Audio processing failed: request_id={request_id} detail={exc}")
        return error_response(
            "audio_processing_failed",
            exc.public_detail,
            422,
            request_id,
            started,
        )
    except Exception as exc:
        print(f"Inference failed: request_id={request_id} {type(exc).__name__}: {exc}")
        return error_response(
            "inference_failed",
            safe_error_detail("模型分析失敗", exc),
            500,
            request_id,
            started,
        )
    finally:
        for path in (temporary_path, normalized_path):
            if path:
                try:
                    os.remove(path)
                except OSError:
                    pass


if __name__ == "__main__":
    print(f"Using model program directory: {PROGRAM_DIR}")
    app.run(host="0.0.0.0", port=5000, threaded=False)
