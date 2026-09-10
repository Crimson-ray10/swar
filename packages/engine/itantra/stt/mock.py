"""Mock STT Provider for reproducible offline tests and demonstrations."""

import time
from itantra.stt.base import STTProvider, TranscriptResult, TranscriptSegment

MOCK_TRANSCRIPTS = {
    "hi": "मैं घर पहुँच गया हूँ और सब ठीक है।",
    "ta": "நான் நலமாக இருக்கிறேன், நன்றி.",
    "en": "I have arrived at the station safely.",
    "bn": "আমি ভালো আছি।",
    "te": "నేను క్షేమంగా ఉన్నాను.",
    "mr": "मी सुरक्षित पोहोचलो आहे.",
    "gu": "હું ઘરે પહોંચી ગયો છું.",
    "kn": "ನಾನು ಸುರಕ್ಷಿತವಾಗಿದ್ದೇನೆ.",
    "ml": "ഞാൻ സുഖമായിരിക്കുന്നു.",
    "pa": "ਮੈਂ ਘਰ ਪਹੁੰਚ ਗਿਆ ਹਾਂ।",
}


class MockSTTProvider(STTProvider):
    """Provides fast deterministic transcripts without requiring multi-gigabyte models."""

    def __init__(self, simulated_latency_ms: int = 50):
        self.simulated_latency_ms = simulated_latency_ms

    def is_available(self) -> bool:
        return True

    def transcribe(
        self,
        audio_pcm: bytes,
        language: str,
        decoder_mode: str = "ctc",
    ) -> TranscriptResult:
        t0 = time.perf_counter()
        if self.simulated_latency_ms > 0:
            time.sleep(self.simulated_latency_ms / 1000.0)

        duration_ms = int((len(audio_pcm) / 2 / 16000) * 1000)
        text = MOCK_TRANSCRIPTS.get(language, MOCK_TRANSCRIPTS["hi"])

        processing_ms = int((time.perf_counter() - t0) * 1000)

        segments = [
            TranscriptSegment(
                start_ms=0,
                end_ms=duration_ms,
                text=text,
                confidence=0.94,
            )
        ]

        return TranscriptResult(
            raw_text=text,
            normalized_text=text,
            language=language,
            asr_confidence=0.94,
            segments=segments,
            model_id="itantra/mock-stt",
            model_revision="v1",
            processing_ms=processing_ms,
        )
