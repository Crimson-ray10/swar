"""Deterministic Mock TTS Provider for reproducible tests and offline demonstration."""

import time
import numpy as np
from itantra.tts.base import TTSProvider, AudioResult

SAMPLE_RATE = 24000


class MockTTSProvider(TTSProvider):
    """Synthesizes real harmonic waveform audio without needing deep neural weights."""

    def __init__(self, sample_rate_hz: int = SAMPLE_RATE):
        self.sample_rate_hz = sample_rate_hz

    def is_available(self) -> bool:
        return True

    def synthesize(
        self,
        text: str,
        language: str,
        voice_id: str | None = None,
    ) -> AudioResult:
        t0 = time.perf_counter()

        # Generate audio duration proportional to text length (~60ms per character, min 0.8s, max 8.0s)
        duration_s = max(0.8, min(8.0, len(text) * 0.06))
        total_samples = int(duration_s * self.sample_rate_hz)

        # Generate smooth multi-tone harmonic carrier resembling speech formant resonance
        t = np.linspace(0, duration_s, total_samples, endpoint=False)
        base_f = 160.0  # 160 Hz fundamental pitch
        audio = (
            0.5 * np.sin(2 * np.pi * base_f * t)
            + 0.25 * np.sin(2 * np.pi * (base_f * 2) * t)
            + 0.15 * np.sin(2 * np.pi * (base_f * 3) * t)
            + 0.10 * np.sin(2 * np.pi * (base_f * 5) * t)
        )

        # Apply smooth speech envelope (attack, sustain, decay)
        envelope = np.ones_like(audio)
        fade_len = int(0.05 * self.sample_rate_hz)  # 50ms fade
        if len(audio) > 2 * fade_len:
            envelope[:fade_len] = np.linspace(0, 1, fade_len)
            envelope[-fade_len:] = np.linspace(1, 0, fade_len)

        audio = (audio * envelope * 0.4).astype(np.float32)
        int16_audio = (audio * 32767.0).astype(np.int16)
        pcm_bytes = int16_audio.tobytes()

        processing_ms = int((time.perf_counter() - t0) * 1000)

        return AudioResult(
            pcm_bytes=pcm_bytes,
            sample_rate_hz=self.sample_rate_hz,
            channels=1,
            duration_ms=int(duration_s * 1000),
            processing_ms=processing_ms,
            provider_info="itantra/mock-tts (synthetic receiver voice)",
            is_synthetic=True,
        )
