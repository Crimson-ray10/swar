import pytest
import sys
import os
import numpy as np

sys.path.insert(0, os.path.abspath("packages/protocol"))
sys.path.insert(0, os.path.abspath("packages/engine"))

from itantra.audio.preprocess import (
    pcm_s16le_to_float32,
    float32_to_pcm_s16le,
    resample_linear,
    AudioValidationError,
)
from itantra.audio.vad import VadSegmenter, FRAME_BYTES
from itantra.stt.mock import MockSTTProvider
from itantra.tts.mock import MockTTSProvider
from itantra.tts.indicf5 import IndicF5Provider, ConsentError
from itantra.language.registry import LanguageRegistry


def test_audio_pcm_conversions():
    original = np.array([-1.0, -0.5, 0.0, 0.5, 1.0], dtype=np.float32)
    pcm = float32_to_pcm_s16le(original)
    assert len(pcm) == 10
    recovered = pcm_s16le_to_float32(pcm)
    assert np.allclose(original, recovered, atol=1e-4)


def test_audio_resampling():
    sr_orig = 8000
    sr_target = 16000
    t = np.linspace(0, 1.0, sr_orig, endpoint=False)
    sine = np.sin(2 * np.pi * 440 * t).astype(np.float32)

    resampled = resample_linear(sine, sr_orig, sr_target)
    assert len(resampled) == 16000


def test_vad_segmentation():
    vad = VadSegmenter(energy_threshold=0.02, silence_timeout_ms=100)

    # 1. Feed 100ms of speech (5 frames of 20ms)
    t = np.linspace(0, 0.02, 320, endpoint=False)
    speech_frame = float32_to_pcm_s16le(0.5 * np.sin(2 * np.pi * 440 * t).astype(np.float32))

    for _ in range(5):
        events = vad.push_chunk(speech_frame)
        assert len(events) == 1
        assert events[0].is_speech is True
        assert events[0].is_final is False

    # 2. Feed silence (120ms, 6 frames) to trigger finalization (>100ms timeout)
    silence_frame = b"\x00" * FRAME_BYTES
    finalized = False
    for _ in range(6):
        events = vad.push_chunk(silence_frame)
        for ev in events:
            if ev.is_final:
                finalized = True
                assert len(ev.utterance_pcm) > 0
                assert ev.duration_ms >= 100
                break
        if finalized:
            break

    assert finalized is True


def test_mock_stt_transcription():
    stt = MockSTTProvider(simulated_latency_ms=0)
    audio = b"\x00" * (16000 * 2)  # 1 second of audio

    res_hi = stt.transcribe(audio, language="hi")
    assert res_hi.language == "hi"
    assert "घर" in res_hi.normalized_text
    assert res_hi.asr_confidence >= 0.9

    res_ta = stt.transcribe(audio, language="ta")
    assert res_ta.language == "ta"
    assert "நலமாக" in res_ta.normalized_text or "நான்" in res_ta.normalized_text

    res_en = stt.transcribe(audio, language="en")
    assert res_en.language == "en"
    assert "safely" in res_en.normalized_text


def test_mock_tts_synthesis():
    tts = MockTTSProvider()
    res = tts.synthesize("मैं घर पहुँच गया हूँ", language="hi")
    assert res.is_synthetic is True
    assert res.sample_rate_hz == 24000
    assert res.channels == 1
    assert len(res.pcm_bytes) > 0
    assert res.duration_ms > 500


def test_indicf5_consent_protection():
    indicf5 = IndicF5Provider(prompts_dir="nonexistent_prompts_dir")
    with pytest.raises(Exception):
        indicf5.synthesize("test", language="hi")


def test_language_registry():
    reg = LanguageRegistry()
    hi = reg.get("hi")
    assert hi is not None
    assert hi.name == "Hindi"
    assert hi.mvp is True

    ta = reg.get("ta")
    assert ta is not None
    assert ta.name == "Tamil"
    assert ta.mvp is True

    assert reg.numeric_id_for("hi") == 1
    assert reg.lang_id_from_numeric(1) == "hi"
