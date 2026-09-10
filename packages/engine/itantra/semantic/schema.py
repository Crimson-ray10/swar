"""Semantic frame and reconstructed frame data schemas for iTantra."""

from dataclasses import dataclass, field
from typing import List, Literal, Optional


@dataclass(frozen=True)
class CriticalSpan:
    span_type: str  # "digit", "phone", "amount", "negation", "date_time", "literal"
    start_char: int
    end_char: int
    literal: str


@dataclass(frozen=True)
class SemanticFrame:
    protocol_version: int
    profile: Literal["text_v1", "semantic_vq_v1"]
    utterance_id: int  # uint16 unique within session
    language: str      # e.g., "hi", "ta", "en"
    normalizer_id: str
    token_model_id: str
    start_ms: int
    duration_ms: int
    normalized_text: str
    token_ids: List[int]
    critical_spans: List[CriticalSpan] = field(default_factory=list)
    asr_confidence: Optional[float] = None


@dataclass(frozen=True)
class ReconstructedFrame:
    utterance_id: int
    status: Literal["exact", "semantic_hypothesis", "partial", "unrecoverable"]
    text: Optional[str]
    missing_packet_sequences: List[int]
    confidence: Optional[float]
    warnings: List[str] = field(default_factory=list)
