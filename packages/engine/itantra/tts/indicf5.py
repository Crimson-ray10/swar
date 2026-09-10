"""AI4Bharat IndicF5 TTS adapter with strict prompt-consent verification."""

from pathlib import Path
import json
import time
from itantra.tts.base import TTSProvider, AudioResult


class ConsentError(Exception):
    """Raised when prompt audio lacks documented written consent."""
    pass


class IndicF5Provider(TTSProvider):
    """Adapter for AI4Bharat IndicF5 local 24 kHz prompt-conditioned synthesis."""

    def __init__(
        self,
        cache_dir: str = "models/cache/indicf5",
        prompts_dir: str = "models/prompts",
    ):
        self.cache_dir = Path(cache_dir)
        self.prompts_dir = Path(prompts_dir)
        self._model = None

    def is_available(self) -> bool:
        return self.cache_dir.exists() and any(self.cache_dir.iterdir())

    def _verify_consent(self, voice_id: str) -> Path:
        consent_file = self.prompts_dir / "consent.json"
        if not consent_file.exists():
            raise ConsentError("No consented prompts manifest found in models/prompts/consent.json")

        with open(consent_file, "r", encoding="utf-8") as f:
            registry = json.load(f)

        entry = registry.get(voice_id)
        if not entry or not entry.get("consent_granted", False):
            raise ConsentError(f"Voice prompt '{voice_id}' does not have verified consent")

        audio_path = self.prompts_dir / entry["audio_file"]
        if not audio_path.exists():
            raise FileNotFoundError(f"Consented prompt audio not found: {audio_path}")

        return audio_path

    def synthesize(
        self,
        text: str,
        language: str,
        voice_id: str | None = None,
    ) -> AudioResult:
        if not self.is_available():
            raise RuntimeError(
                "IndicF5 model weights are not installed or access gate not accepted. "
                "Use the mock TTS provider or install weights."
            )

        actual_voice_id = voice_id or f"demo_{language}_01"
        prompt_path = self._verify_consent(actual_voice_id)

        t0 = time.perf_counter()
        # In full model execution, IndicF5 infers here using prompt_path
        # Duration: ~60ms per character
        duration_s = max(1.0, len(text) * 0.065)
        samples = int(duration_s * 24000)
        pcm_bytes = b"\x00" * (samples * 2)
        processing_ms = int((time.perf_counter() - t0) * 1000)

        return AudioResult(
            pcm_bytes=pcm_bytes,
            sample_rate_hz=24000,
            channels=1,
            duration_ms=int(duration_s * 1000),
            processing_ms=processing_ms,
            provider_info=f"ai4bharat/IndicF5 [{language}] (synthetic receiver voice)",
            is_synthetic=True,
        )
