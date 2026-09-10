"""Text and Semantic Frame codec with 14-bit fixed-width token packing."""

import struct
from typing import List, Tuple
from itantra_protocol.packet import (
    Packet,
    PROFILE_TEXT_V1,
    PAYLOAD_TYPE_EXACT_SP,
    FLAG_FINAL_FRAGMENT,
)
from itantra.semantic.schema import SemanticFrame, ReconstructedFrame
from itantra.semantic.tokenizer import TokenizerAdapter

MAX_PACKET_PAYLOAD_SIZE = 48  # Small payload size tailored for low-bitrate serialization


def pack_uint14(token_ids: List[int]) -> bytes:
    """Pack a list of 14-bit integers (0 <= id < 16384) into packed bytes."""
    output = bytearray()
    bit_buffer = 0
    bit_count = 0

    for token_id in token_ids:
        val = token_id & 0x3FFF
        bit_buffer = (bit_buffer << 14) | val
        bit_count += 14

        while bit_count >= 8:
            shift = bit_count - 8
            byte = (bit_buffer >> shift) & 0xFF
            output.append(byte)
            bit_count -= 8
            bit_buffer &= (1 << bit_count) - 1

    if bit_count > 0:
        byte = (bit_buffer << (8 - bit_count)) & 0xFF
        output.append(byte)

    return bytes(output)


def unpack_uint14(packed_bytes: bytes, token_count: int) -> List[int]:
    """Unpack exactly token_count 14-bit integers from packed bytes."""
    tokens = []
    bit_buffer = 0
    bit_count = 0
    byte_idx = 0

    while len(tokens) < token_count:
        while bit_count < 14 and byte_idx < len(packed_bytes):
            bit_buffer = (bit_buffer << 8) | packed_bytes[byte_idx]
            bit_count += 8
            byte_idx += 1

        if bit_count >= 14:
            shift = bit_count - 14
            token = (bit_buffer >> shift) & 0x3FFF
            tokens.append(token)
            bit_count -= 14
            bit_buffer &= (1 << bit_count) - 1
        else:
            break

    return tokens


def encode_semantic_frame_to_packets(
    frame: SemanticFrame,
    session_id: int,
    generation_id: int = 1,
    language_numeric_id: int = 1,
    start_sequence: int = 1,
    max_payload_per_packet: int = MAX_PACKET_PAYLOAD_SIZE,
) -> List[Packet]:
    """
    Encode a SemanticFrame into one or more ITP/1 packets.
    Miniheader (3 bytes): utterance_id (u16) | token_count (u8)
    Followed by: packed 14-bit token IDs.
    """
    packed_tokens = pack_uint14(frame.token_ids)
    token_count = len(frame.token_ids)
    if token_count > 255:
        raise ValueError(f"Token count ({token_count}) exceeds u8 maximum (255)")

    miniheader = struct.pack(">HB", frame.utterance_id & 0xFFFF, token_count & 0xFF)
    full_payload = miniheader + packed_tokens

    # Fragment payload across packets
    fragments: List[bytes] = []
    offset = 0
    while offset < len(full_payload):
        chunk = full_payload[offset : offset + max_payload_per_packet]
        fragments.append(chunk)
        offset += len(chunk)

    if not fragments:
        fragments = [b""]

    fragment_count = len(fragments)
    start_tick = int(frame.start_ms / 20)

    packets: List[Packet] = []
    for idx, frag in enumerate(fragments):
        is_final = (idx == fragment_count - 1)
        flags = FLAG_FINAL_FRAGMENT if is_final else 0

        pkt = Packet(
            profile=PROFILE_TEXT_V1,
            flags=flags,
            session_id=session_id,
            sequence=start_sequence + idx,
            start_tick_20ms=start_tick,
            language_id=language_numeric_id,
            payload_type=PAYLOAD_TYPE_EXACT_SP,
            fragment_index=idx,
            fragment_count=fragment_count,
            generation_id=generation_id,
            payload=frag,
        )
        packets.append(pkt)

    return packets


def decode_packets_to_reconstructed_frame(
    packets: List[Packet],
    tokenizer: TokenizerAdapter,
    expected_fragment_count: int | None = None,
) -> ReconstructedFrame:
    """
    Reconstruct SemanticFrame from received, CRC-validated packets.
    If any fragment in the generation is missing, returns 'partial' or 'unrecoverable'
    without guessing or hallucinating missing tokens.
    """
    if not packets:
        return ReconstructedFrame(
            utterance_id=0,
            status="unrecoverable",
            text=None,
            missing_packet_sequences=[],
            confidence=0.0,
            warnings=["No packets received"],
        )

    # Sort packets by fragment index
    packets_by_frag = {p.fragment_index: p for p in packets}
    first_pkt = packets[0]
    total_expected = expected_fragment_count or first_pkt.fragment_count

    missing_indices = [i for i in range(total_expected) if i not in packets_by_frag]

    if missing_indices:
        missing_seqs = [first_pkt.sequence + idx for idx in missing_indices]
        return ReconstructedFrame(
            utterance_id=0,
            status="partial" if len(packets) > 0 else "unrecoverable",
            text=None,
            missing_packet_sequences=missing_seqs,
            confidence=0.0,
            warnings=[f"Missing fragments: {missing_indices} out of {total_expected}"],
        )

    # Reassemble fragments in exact order
    full_payload = bytearray()
    for i in range(total_expected):
        full_payload.extend(packets_by_frag[i].payload)

    if len(full_payload) < 3:
        return ReconstructedFrame(
            utterance_id=0,
            status="unrecoverable",
            text=None,
            missing_packet_sequences=[],
            confidence=0.0,
            warnings=["Reassembled payload too short for miniheader"],
        )

    utterance_id, token_count = struct.unpack(">HB", full_payload[:3])
    packed_tokens = bytes(full_payload[3:])

    token_ids = unpack_uint14(packed_tokens, token_count)
    reconstructed_text = tokenizer.decode(token_ids)

    return ReconstructedFrame(
        utterance_id=utterance_id,
        status="exact",
        text=reconstructed_text,
        missing_packet_sequences=[],
        confidence=1.0,
        warnings=[],
    )
