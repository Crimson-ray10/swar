"""Audio preprocessing and validation for iTantra."""

import io
import numpy as np
import soundfile as sf

TARGET_SAMPLE_RATE = 16000
MAX_DURATION_SECONDS = 30


class AudioValidationError(Exception):
    """Raised when audio fails format, duration, or channel checks."""
    pass


def pcm_s16le_to_float32(pcm_bytes: bytes) -> np.ndarray:
    """Convert raw 16-bit signed little-endian PCM bytes to float32 [-1.0, 1.0]."""
    if len(pcm_bytes) % 2 != 0:
        raise AudioValidationError("PCM byte length must be multiple of 2 for 16-bit samples")
    int16_data = np.frombuffer(pcm_bytes, dtype=np.int16)
    return int16_data.astype(np.float32) / 32768.0


def float32_to_pcm_s16le(audio_floats: np.ndarray) -> bytes:
    """Convert float32 [-1.0, 1.0] array to 16-bit signed little-endian PCM bytes."""
    clipped = np.clip(audio_floats, -1.0, 1.0)
    int16_data = (clipped * 32767.0).astype(np.int16)
    return int16_data.tobytes()


def resample_linear(audio: np.ndarray, orig_sr: int, target_sr: int) -> np.ndarray:
    """Resample 1D float audio array using linear interpolation."""
    if orig_sr == target_sr:
        return audio
    duration = len(audio) / orig_sr
    target_length = int(round(duration * target_sr))
    orig_indices = np.linspace(0, len(audio) - 1, num=len(audio))
    target_indices = np.linspace(0, len(audio) - 1, num=target_length)
    return np.interp(target_indices, orig_indices, audio).astype(np.float32)


def process_audio_upload(file_bytes: bytes) -> tuple[bytes, float]:
    """
    Validate, downmix, and resample arbitrary WAV/FLAC audio to 16 kHz mono PCM S16LE.
    Returns: (pcm_s16le_bytes, duration_seconds)
    """
    try:
        with io.BytesIO(file_bytes) as bio:
            data, sr = sf.read(bio, dtype="float32")
    except Exception as e:
        raise AudioValidationError(f"Could not decode audio file: {e}")

    # Downmix if multichannel
    if data.ndim > 1:
        data = np.mean(data, axis=1)

    # Resample to 16 kHz if needed
    if sr != TARGET_SAMPLE_RATE:
        data = resample_linear(data, sr, TARGET_SAMPLE_RATE)

    # Peak normalization / DC offset removal
    data = data - np.mean(data)
    peak = np.max(np.abs(data))
    if peak > 0.98:
        data = data * (0.95 / peak)

    duration_s = len(data) / TARGET_SAMPLE_RATE
    if duration_s > MAX_DURATION_SECONDS:
        raise AudioValidationError(f"Audio duration ({duration_s:.1f}s) exceeds maximum allowed {MAX_DURATION_SECONDS}s")

    pcm_bytes = float32_to_pcm_s16le(data)
    return pcm_bytes, duration_s
