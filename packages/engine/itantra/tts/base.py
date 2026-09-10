"""TTS Provider base contract and audio result schemas."""

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Optional


@dataclass
class AudioResult:
    pcm_bytes: bytes
    sample_rate_hz: int
    channels: int
    duration_ms: int
    processing_ms: int
    provider_info: str
    is_synthetic: bool = True  # Always True per specification


class TTSProvider(ABC):
    """Abstract interface for text-to-speech providers."""

    @abstractmethod
    def synthesize(
        self,
        text: str,
        language: str,
        voice_id: Optional[str] = None,
    ) -> AudioResult:
        """Synthesize normalized text into PCM audio."""
        pass

    @abstractmethod
    def is_available(self) -> bool:
        """Return True if model and voice assets are locally installed."""
        pass
