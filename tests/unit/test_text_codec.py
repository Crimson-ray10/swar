import pytest
import sys
import os
import random

sys.path.insert(0, os.path.abspath("packages/protocol"))
sys.path.insert(0, os.path.abspath("packages/engine"))

from itantra.text.normalize import normalize_text, extract_critical_spans
from itantra.semantic.schema import SemanticFrame, CriticalSpan
from itantra.semantic.tokenizer import TokenizerAdapter
from itantra.semantic.text_codec import (
    pack_uint14,
    unpack_uint14,
    encode_semantic_frame_to_packets,
    decode_packets_to_reconstructed_frame,
)


def test_text_normalization():
    raw_hi = "  मैं   घर   पहुँच गया हूँ   "
    norm_hi = normalize_text(raw_hi, "hi")
    assert norm_hi == "मैं घर पहुँच गया हूँ"

    raw_ta = "நான்    நலமாக   இருக்கிறேன் "
    norm_ta = normalize_text(raw_ta, "ta")
    assert norm_ta == "நான் நலமாக இருக்கிறேன்"


def test_critical_span_extraction():
    text_hi = "गाड़ी का नंबर 1234 है और वह नहीं आया।"
    spans_hi = extract_critical_spans(text_hi, "hi")
    types = [s.span_type for s in spans_hi]
    literals = [s.literal for s in spans_hi]
    assert "digit" in types
    assert "1234" in literals
    assert "negation" in types
    assert "नहीं" in literals

    text_en = "Do not transfer $500 to +91-9876543210."
    spans_en = extract_critical_spans(text_en, "en")
    en_literals = [s.literal for s in spans_en]
    assert "not" in en_literals
    assert any("9876543210" in l for l in en_literals)


def test_uint14_bit_packing():
    # Test cases: edge values
    test_cases = [
        [],
        [0],
        [16383],
        [0, 16383, 1, 8192, 42],
        [random.randint(0, 16383) for _ in range(50)],
    ]

    for tokens in test_cases:
        packed = pack_uint14(tokens)
        unpacked = unpack_uint14(packed, len(tokens))
        assert unpacked == tokens


def test_uint14_fuzz_1000_iterations():
    random.seed(1729)
    for _ in range(1000):
        length = random.randint(1, 40)
        tokens = [random.randint(0, 16383) for _ in range(length)]
        packed = pack_uint14(tokens)
        recovered = unpack_uint14(packed, length)
        assert recovered == tokens


def test_semantic_frame_encode_decode_round_trip():
    tokenizer = TokenizerAdapter()
    text = "मैं घर पहुँच गया हूँ और सब ठीक है।"
    token_ids = tokenizer.encode(text, language="hi")

    frame = SemanticFrame(
        protocol_version=1,
        profile="text_v1",
        utterance_id=101,
        language="hi",
        normalizer_id="indic_nfc",
        token_model_id=tokenizer.model_sha256[:16],
        start_ms=0,
        duration_ms=1500,
        normalized_text=text,
        token_ids=token_ids,
        critical_spans=extract_critical_spans(text, "hi"),
    )

    packets = encode_semantic_frame_to_packets(
        frame=frame,
        session_id=0xABCD,
        generation_id=1,
        language_numeric_id=1,
        start_sequence=1,
        max_payload_per_packet=20,  # force fragmentation into multiple packets
    )
    assert len(packets) > 1

    # Decode complete packets
    reconstructed = decode_packets_to_reconstructed_frame(packets, tokenizer)
    assert reconstructed.status == "exact"
    assert reconstructed.utterance_id == 101
    assert reconstructed.text == text
    assert len(reconstructed.missing_packet_sequences) == 0


def test_dropped_packet_erasure_handling():
    tokenizer = TokenizerAdapter()
    text = "Important radio message that should not be hallucinated if lost."
    token_ids = tokenizer.encode(text, "en")

    frame = SemanticFrame(
        protocol_version=1,
        profile="text_v1",
        utterance_id=202,
        language="en",
        normalizer_id="standard_en",
        token_model_id="test",
        start_ms=0,
        duration_ms=2000,
        normalized_text=text,
        token_ids=token_ids,
    )

    packets = encode_semantic_frame_to_packets(
        frame=frame,
        session_id=0x1111,
        generation_id=1,
        language_numeric_id=3,
        start_sequence=10,
        max_payload_per_packet=15,  # multiple fragments
    )
    assert len(packets) >= 2

    # Simulate dropped second packet
    dropped_packets = [p for p in packets if p.fragment_index != 1]
    reconstructed = decode_packets_to_reconstructed_frame(
        dropped_packets, tokenizer, expected_fragment_count=len(packets)
    )

    # Must NOT hallucinate or return partial text as exact
    assert reconstructed.status in ("partial", "unrecoverable")
    assert reconstructed.text is None
    assert len(reconstructed.missing_packet_sequences) > 0
