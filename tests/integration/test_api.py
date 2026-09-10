import io
import pytest
import sys
import os
import numpy as np
import soundfile as sf
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.abspath("packages/protocol"))
sys.path.insert(0, os.path.abspath("packages/engine"))
sys.path.insert(0, os.path.abspath("services/api"))

from itantra_api.main import app

client = TestClient(app)


def generate_test_wav_bytes(duration_s: float = 1.0, sr: int = 16000) -> bytes:
    """Generate in-memory WAV file with 440 Hz tone."""
    t = np.linspace(0, duration_s, int(duration_s * sr), endpoint=False)
    sine = (0.5 * np.sin(2 * np.pi * 440 * t)).astype(np.float32)
    bio = io.BytesIO()
    sf.write(bio, sine, sr, format="WAV", subtype="PCM_16")
    bio.seek(0)
    return bio.read()


def test_health_endpoint():
    res = client.get("/api/v1/health")
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "healthy"
    assert "models_status" in data


def test_languages_endpoint():
    res = client.get("/api/v1/languages")
    assert res.status_code == 200
    langs = res.json()
    assert len(langs) >= 3
    ids = [l["id"] for l in langs]
    assert "hi" in ids
    assert "ta" in ids
    assert "en" in ids


def test_wire_profiles_endpoint():
    res = client.get("/api/v1/wire-profiles")
    assert res.status_code == 200
    profiles = res.json()
    assert "interactive_2" in profiles
    assert "ultra_0p5" in profiles


def test_create_session():
    payload = {
        "language": "hi",
        "profile": "text_v1",
        "wire_profile": "interactive_2",
        "channel": {
            "mode": "bpsk_awgn",
            "gross_rate_bps": 2000,
            "eb_n0_db": 6.0,
            "packet_loss_rate": 0.0,
            "seed": 1729,
        },
    }
    res = client.post("/api/v1/sessions", json=payload)
    assert res.status_code == 200
    data = res.json()
    assert "session_id" in data
    assert len(data["session_id"]) > 0


def test_execute_run_end_to_end():
    wav_bytes = generate_test_wav_bytes(duration_s=1.2)
    files = {"audio": ("test_speech.wav", wav_bytes, "audio/wav")}
    data = {
        "language": "hi",
        "profile": "text_v1",
        "wire_profile": "interactive_2",
        "gross_rate_bps": 2000,
        "eb_n0_db": 10.0,
        "packet_loss_rate": 0.0,
        "seed": 42,
        "use_fec": True,
    }

    res = client.post("/api/v1/runs", files=files, data=data)
    assert res.status_code == 200
    report = res.json()

    assert report["status"] == "complete"
    assert report["transcript"]["language"] == "hi"
    assert len(report["transcript"]["normalized"]) > 0
    assert report["transport"]["wire_bits"] > 0
    assert report["transport"]["actual_wire_bps"] > 0
    assert report["transport"]["compression_ratio_vs_pcm"] > 50.0  # Huge compression vs 256 kbps PCM
    assert report["receiver"]["status"] == "exact"
    assert report["receiver"]["tts_status"] == "complete"
    assert report["audio_output_base64"] is not None
    assert len(report["delivery_events"]) > 0
