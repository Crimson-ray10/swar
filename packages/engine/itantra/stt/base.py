"""STT Provider base contract and data schemas."""

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import List, Optional


@dataclass
class TranscriptSegment:
    start_ms: int
    end_ms: int
    text: str
    confidence: Optional[float] = None


@dataclass
class TranscriptResult:
    raw_text: str
    normalized_text: str
    language: str
    asr_confidence: Optional[float]
    segments: List[TranscriptSegment]
    model_id: str
    model_revision: str
    processing_ms: int


class STTProvider(ABC):
    """Abstract interface for speech-to-text providers."""

    @abstractmethod
    def transcribe(
        self,
        audio_pcm: bytes,
        language: str,
        decoder_mode: str = "ctc",
    ) -> TranscriptResult:
        """Transcribe 16 kHz mono S16LE PCM bytes."""
        pass

    @abstractmethod
    def is_available(self) -> bool:
        """Return True if model checkpoints and dependencies are ready."""
        pass
