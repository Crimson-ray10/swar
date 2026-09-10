"""Generate sample WAV fixtures for testing and evaluation."""

import io
from pathlib import Path
import numpy as np
import soundfile as sf

FIXTURES_DIR = Path("tests/fixtures")
FIXTURES_DIR.mkdir(parents=True, exist_ok=True)


def create_tone_wav(filepath: Path, duration_s: float = 1.5, f0: float = 220.0, sr: int = 16000):
    t = np.linspace(0, duration_s, int(duration_s * sr), endpoint=False)
    # Formant-like harmonic sound
    signal = (
        0.5 * np.sin(2 * np.pi * f0 * t)
        + 0.3 * np.sin(2 * np.pi * (f0 * 2) * t)
        + 0.15 * np.sin(2 * np.pi * (f0 * 3) * t)
    )
    # Envelope
    env = np.ones_like(signal)
    fade = int(0.05 * sr)
    env[:fade] = np.linspace(0, 1, fade)
    env[-fade:] = np.linspace(1, 0, fade)
    signal = (signal * env * 0.4).astype(np.float32)

    sf.write(str(filepath), signal, sr, format="WAV", subtype="PCM_16")
    print(f"Generated fixture: {filepath} ({duration_s}s, {sr} Hz)")


if __name__ == "__main__":
    create_tone_wav(FIXTURES_DIR / "hi_short.wav", duration_s=1.5, f0=220.0)
    create_tone_wav(FIXTURES_DIR / "ta_short.wav", duration_s=1.8, f0=240.0)
    create_tone_wav(FIXTURES_DIR / "en_short.wav", duration_s=2.0, f0=180.0)
