import pytest
import sys
import os

# Add protocol directory to sys.path
sys.path.insert(0, os.path.abspath("packages/protocol"))

from itantra_protocol.packet import (
    Packet,
    ProtocolError,
    HeaderCrcError,
    PacketCrcError,
    PROFILE_TEXT_V1,
    PAYLOAD_TYPE_EXACT_SP,
    FLAG_FINAL_FRAGMENT,
)


def test_packet_round_trip():
    payload = b"\x01\x02\x03\x04Hello Indian Speech Radio!\x99"
    pkt = Packet(
        profile=PROFILE_TEXT_V1,
        flags=FLAG_FINAL_FRAGMENT,
        session_id=0x1234,
        sequence=42,
        start_tick_20ms=100,
        language_id=1,  # hi
        payload_type=PAYLOAD_TYPE_EXACT_SP,
        fragment_index=0,
        fragment_count=1,
        generation_id=7,
        payload=payload,
    )
    raw = pkt.serialize()
    assert len(raw) == 14 + len(payload) + 2

    recovered = Packet.parse(raw)
    assert recovered.profile == pkt.profile
    assert recovered.flags == pkt.flags
    assert recovered.session_id == pkt.session_id
    assert recovered.sequence == pkt.sequence
    assert recovered.start_tick_20ms == pkt.start_tick_20ms
    assert recovered.language_id == pkt.language_id
    assert recovered.payload_type == pkt.payload_type
    assert recovered.fragment_index == 0
    assert recovered.fragment_count == 1
    assert recovered.generation_id == 7
    assert recovered.payload == payload
    assert recovered.is_final_fragment is True
    assert recovered.is_parity is False


def test_packet_crc_corruption_detection():
    pkt = Packet(
        profile=PROFILE_TEXT_V1,
        flags=0,
        session_id=100,
        sequence=1,
        start_tick_20ms=0,
        language_id=2,  # ta
        payload_type=PAYLOAD_TYPE_EXACT_SP,
        fragment_index=0,
        fragment_count=1,
        generation_id=1,
        payload=b"Important Text",
    )
    raw = bytearray(pkt.serialize())

    # Corrupt one payload byte
    raw[15] ^= 0x01

    with pytest.raises(PacketCrcError):
        Packet.parse(bytes(raw))


def test_header_crc_corruption_detection():
    pkt = Packet(
        profile=PROFILE_TEXT_V1,
        flags=0,
        session_id=100,
        sequence=1,
        start_tick_20ms=0,
        language_id=3,  # en
        payload_type=PAYLOAD_TYPE_EXACT_SP,
        fragment_index=0,
        fragment_count=1,
        generation_id=1,
        payload=b"Some payload",
    )
    raw = bytearray(pkt.serialize())

    # Corrupt sequence byte in header (byte 4) without updating header CRC
    raw[4] ^= 0xFF

    # Header CRC mismatch should trigger HeaderCrcError (or PacketCrcError if packet crc checked first)
    with pytest.raises((HeaderCrcError, PacketCrcError)):
        Packet.parse(bytes(raw))


def test_short_packet_rejection():
    with pytest.raises(ProtocolError):
        Packet.parse(b"\x0A\x01\x02")
