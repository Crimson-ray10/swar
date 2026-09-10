"""Voice Activity Detection (VAD) and utterance segmentation."""

from dataclasses import dataclass
import numpy as np
from itantra.audio.preprocess import pcm_s16le_to_float32

SAMPLE_RATE = 16000
FRAME_MS = 20
FRAME_SAMPLES = int(SAMPLE_RATE * (FRAME_MS / 1000))  # 320 samples
FRAME_BYTES = FRAME_SAMPLES * 2                        # 640 bytes

SILENCE_FINALIZATION_MS = 500
MAX_UTTERANCE_MS = 3000
ENERGY_THRESHOLD = 0.015


@dataclass
class VadEvent:
    is_speech: bool
    is_final: bool
    utterance_pcm: bytes | None
    duration_ms: int


class VadSegmenter:
    """Buffers streaming audio chunks, evaluates speech energy, and detects utterance boundaries."""

    def __init__(
        self,
        energy_threshold: float = ENERGY_THRESHOLD,
        silence_timeout_ms: int = SILENCE_FINALIZATION_MS,
        max_utterance_ms: int = MAX_UTTERANCE_MS,
    ):
        self.energy_threshold = energy_threshold
        self.silence_timeout_ms = silence_timeout_ms
        self.max_utterance_ms = max_utterance_ms

        self._in_speech = False
        self._speech_buffer = bytearray()
        self._silence_ms = 0
        self._current_utterance_ms = 0

    def push_chunk(self, chunk_pcm: bytes) -> list[VadEvent]:
        """Process incoming PCM chunk (any size) in 20ms frames."""
        events = []
        offset = 0

        while offset + FRAME_BYTES <= len(chunk_pcm):
            frame = chunk_pcm[offset : offset + FRAME_BYTES]
            offset += FRAME_BYTES

            event = self._process_frame(frame)
            if event:
                events.append(event)

        return events

    def _process_frame(self, frame_bytes: bytes) -> VadEvent | None:
        samples = pcm_s16le_to_float32(frame_bytes)
        rms = float(np.sqrt(np.mean(samples**2)))
        frame_is_speech = rms > self.energy_threshold

        if frame_is_speech:
            self._in_speech = True
            self._silence_ms = 0
            self._speech_buffer.extend(frame_bytes)
            self._current_utterance_ms += FRAME_MS

            # Guard: max utterance duration reached
            if self._current_utterance_ms >= self.max_utterance_ms:
                return self._finalize_current()

            return VadEvent(
                is_speech=True,
                is_final=False,
                utterance_pcm=None,
                duration_ms=self._current_utterance_ms,
            )
        else:
            if self._in_speech:
                self._speech_buffer.extend(frame_bytes)
                self._silence_ms += FRAME_MS
                self._current_utterance_ms += FRAME_MS

                if self._silence_ms >= self.silence_timeout_ms:
                    return self._finalize_current()

            return VadEvent(
                is_speech=False,
                is_final=False,
                utterance_pcm=None,
                duration_ms=self._current_utterance_ms,
            )

    def finalize(self) -> VadEvent | None:
        """Force finalize current buffer (e.g., when recording stops)."""
        if self._speech_buffer:
            return self._finalize_current()
        return None

    def _finalize_current(self) -> VadEvent:
        pcm = bytes(self._speech_buffer)
        duration = self._current_utterance_ms
        self._in_speech = False
        self._speech_buffer.clear()
        self._silence_ms = 0
        self._current_utterance_ms = 0

        return VadEvent(
            is_speech=False,
            is_final=True,
            utterance_pcm=pcm,
            duration_ms=duration,
        )
