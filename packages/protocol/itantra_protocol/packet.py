"""ITP/1 Packet definition, serialization, and parsing."""

from dataclasses import dataclass
import struct
from itantra_protocol.crc import crc8, crc16_ccitt

MAGIC_ITP = 0x0A
VERSION_ITP1 = 1
HEADER_SIZE = 14
CRC16_SIZE = 2

# Profile constants
PROFILE_TEXT_V1 = 0
PROFILE_SEMANTIC_VQ_V1 = 1

# Payload types
PAYLOAD_TYPE_EXACT_SP = 0x02
PAYLOAD_TYPE_VQ = 0x03
PAYLOAD_TYPE_ANCHORS = 0x04

# Flags
FLAG_PARITY = 0x01
FLAG_FINAL_FRAGMENT = 0x02


class ProtocolError(Exception):
    """Raised when packet validation or parsing fails."""
    pass


class HeaderCrcError(ProtocolError):
    """Raised when header CRC-8 check fails."""
    pass


class PacketCrcError(ProtocolError):
    """Raised when packet CRC-16 check fails."""
    pass


@dataclass
class Packet:
    profile: int
    flags: int
    session_id: int
    sequence: int
    start_tick_20ms: int
    language_id: int
    payload_type: int
    fragment_index: int
    fragment_count: int
    generation_id: int
    payload: bytes
    version: int = VERSION_ITP1

    @property
    def is_parity(self) -> bool:
        return bool(self.flags & FLAG_PARITY)

    @property
    def is_final_fragment(self) -> bool:
        return bool(self.flags & FLAG_FINAL_FRAGMENT)

    def serialize(self, pad_to_length: int | None = None) -> bytes:
        """Serializes Packet to raw bytes with header CRC8 and packet CRC16."""
        payload_bytes = self.payload
        payload_length = len(payload_bytes)

        if pad_to_length is not None:
            if len(payload_bytes) < pad_to_length:
                payload_bytes = payload_bytes + b"\x00" * (pad_to_length - len(payload_bytes))

        # Byte 0: magic (4 bits) | version (2 bits) | profile (2 bits)
        byte0 = ((MAGIC_ITP & 0x0F) << 4) | ((self.version & 0x03) << 2) | (self.profile & 0x03)

        # Byte 10: fragment nibble (high 4: index, low 4: count - 1)
        byte10 = ((self.fragment_index & 0x0F) << 4) | ((self.fragment_count - 1) & 0x0F)

        # Build bytes 0..12 for header CRC8
        hdr_prefix = struct.pack(
            ">BBHHHBBBBB",
            byte0,
            self.flags & 0xFF,
            self.session_id & 0xFFFF,
            self.sequence & 0xFFFF,
            self.start_tick_20ms & 0xFFFF,
            self.language_id & 0xFF,
            self.payload_type & 0xFF,
            byte10,
            payload_length & 0xFF,
            self.generation_id & 0xFF,
        )
        hcrc = crc8(hdr_prefix)
        header = hdr_prefix + struct.pack(">B", hcrc)

        data_without_crc = header + payload_bytes
        pcrc = crc16_ccitt(data_without_crc)
        return data_without_crc + struct.pack(">H", pcrc)

    @classmethod
    def parse(cls, raw: bytes) -> "Packet":
        """Parses a raw byte sequence into a validated Packet."""
        if len(raw) < HEADER_SIZE + CRC16_SIZE:
            raise ProtocolError(f"Packet too short ({len(raw)} bytes; minimum {HEADER_SIZE + CRC16_SIZE})")

        # Validate packet CRC-16
        expected_pcrc = crc16_ccitt(raw[:-CRC16_SIZE])
        actual_pcrc = struct.unpack(">H", raw[-CRC16_SIZE:])[0]
        if expected_pcrc != actual_pcrc:
            raise PacketCrcError(f"Packet CRC16 mismatch: expected 0x{expected_pcrc:04X}, got 0x{actual_pcrc:04X}")

        # Validate header CRC-8
        expected_hcrc = crc8(raw[:13])
        actual_hcrc = raw[13]
        if expected_hcrc != actual_hcrc:
            raise HeaderCrcError(f"Header CRC8 mismatch: expected 0x{expected_hcrc:02X}, got 0x{actual_hcrc:02X}")

        # Unpack header
        byte0, flags, session_id, sequence, start_tick_20ms, language_id, payload_type, byte10, payload_length, generation_id = struct.unpack(
            ">BBHHHBBBBB", raw[:13]
        )

        magic = (byte0 >> 4) & 0x0F
        version = (byte0 >> 2) & 0x03
        profile = byte0 & 0x03

        if magic != MAGIC_ITP:
            raise ProtocolError(f"Invalid magic: expected 0x{MAGIC_ITP:X}, got 0x{magic:X}")
        if version != VERSION_ITP1:
            raise ProtocolError(f"Unsupported protocol version: {version}")

        fragment_index = (byte10 >> 4) & 0x0F
        fragment_count = (byte10 & 0x0F) + 1

        payload = raw[HEADER_SIZE:-CRC16_SIZE][:payload_length]

        return cls(
            version=version,
            profile=profile,
            flags=flags,
            session_id=session_id,
            sequence=sequence,
            start_tick_20ms=start_tick_20ms,
            language_id=language_id,
            payload_type=payload_type,
            fragment_index=fragment_index,
            fragment_count=fragment_count,
            generation_id=generation_id,
            payload=payload,
        )
