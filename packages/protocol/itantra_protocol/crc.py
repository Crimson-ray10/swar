"""CRC calculations for ITP/1 packets.

Header CRC-8 (polynomial 0x07, init 0x00)
Packet CRC-16/CCITT (polynomial 0x1021, init 0xFFFF)
"""

def crc8(data: bytes) -> int:
    """Compute CRC-8 over bytes using polynomial 0x07."""
    crc = 0x00
    for byte in data:
        crc ^= byte
        for _ in range(8):
            if crc & 0x80:
                crc = ((crc << 1) ^ 0x07) & 0xFF
            else:
                crc = (crc << 1) & 0xFF
    return crc


def crc16_ccitt(data: bytes) -> int:
    """Compute CRC-16/CCITT over bytes (poly 0x1021, init 0xFFFF)."""
    crc = 0xFFFF
    for byte in data:
        crc ^= (byte << 8)
        for _ in range(8):
            if crc & 0x8000:
                crc = ((crc << 1) ^ 0x1021) & 0xFFFF
            else:
                crc = (crc << 1) & 0xFFFF
    return crc
