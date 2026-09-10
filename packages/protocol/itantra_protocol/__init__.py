"""ITP/1 protocol package for iTantra."""

from itantra_protocol.packet import (
    Packet,
    ProtocolError,
    HeaderCrcError,
    PacketCrcError,
    MAGIC_ITP,
    VERSION_ITP1,
    HEADER_SIZE,
    CRC16_SIZE,
    PROFILE_TEXT_V1,
    PROFILE_SEMANTIC_VQ_V1,
    PAYLOAD_TYPE_EXACT_SP,
    PAYLOAD_TYPE_VQ,
    PAYLOAD_TYPE_ANCHORS,
    FLAG_PARITY,
    FLAG_FINAL_FRAGMENT,
)
from itantra_protocol.crc import crc8, crc16_ccitt

__all__ = [
    "Packet",
    "ProtocolError",
    "HeaderCrcError",
    "PacketCrcError",
    "crc8",
    "crc16_ccitt",
    "MAGIC_ITP",
    "VERSION_ITP1",
    "HEADER_SIZE",
    "CRC16_SIZE",
    "PROFILE_TEXT_V1",
    "PROFILE_SEMANTIC_VQ_V1",
    "PAYLOAD_TYPE_EXACT_SP",
    "PAYLOAD_TYPE_VQ",
    "PAYLOAD_TYPE_ANCHORS",
    "FLAG_PARITY",
    "FLAG_FINAL_FRAGMENT",
]
