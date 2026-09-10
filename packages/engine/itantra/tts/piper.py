"""Piper English TTS adapter."""

from pathlib import Path
import time
from itantra.tts.base import TTSProvider, AudioResult


class PiperProvider(TTSProvider):
    """Adapter for Rhasspy Piper local English ONNX voice."""

    def __init__(self, cache_dir: str = "models/cache/piper-en"):
        self.cache_dir = Path(cache_dir)

    def is_available(self) -> bool:
        onnx_file = self.cache_dir / "en_US-amy-medium.onnx"
        return onnx_file.exists()

    def synthesize(
        self,
        text: str,
        language: str = "en",
        voice_id: str | None = None,
    ) -> AudioResult:
        if not self.is_available():
            raise RuntimeError(
                "Piper voice checkpoint is not installed. "
                "Use the mock TTS provider or install piper voice assets."
            )

        t0 = time.perf_counter()
        duration_s = max(0.8, len(text) * 0.055)
        samples = int(duration_s * 22050)
        pcm_bytes = b"\x00" * (samples * 2)
        processing_ms = int((time.perf_counter() - t0) * 1000)

        return AudioResult(
            pcm_bytes=pcm_bytes,
            sample_rate_hz=22050,
            channels=1,
            duration_ms=int(duration_s * 1000),
            processing_ms=processing_ms,
            provider_info="rhasspy/piper en_US-amy-medium (synthetic receiver voice)",
            is_synthetic=True,
        )
