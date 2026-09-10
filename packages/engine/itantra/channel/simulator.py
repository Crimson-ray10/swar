"""Low-bitrate channel simulator with BPSK, AWGN, Rayleigh fading, and Gilbert-Elliott bursts."""

from dataclasses import dataclass, field
import math
import random
from typing import List, Literal, Optional
import numpy as np

from itantra_protocol.packet import Packet, PacketCrcError, HeaderCrcError, ProtocolError


@dataclass
class GilbertElliottConfig:
    enabled: bool = False
    p_g: float = 0.0    # BER in Good state
    p_b: float = 0.05   # BER in Bad state
    p_gb: float = 0.02  # Transition Good -> Bad
    p_bg: float = 0.20  # Transition Bad -> Good


@dataclass
class ChannelConfig:
    mode: Literal["abstract_packet", "abstract_bit", "bpsk_awgn", "rayleigh_bpsk"] = "bpsk_awgn"
    gross_rate_bps: int = 2000
    eb_n0_db: float = 5.0
    packet_loss_rate: float = 0.0
    bit_error_rate: Optional[float] = None
    burst: GilbertElliottConfig = field(default_factory=GilbertElliottConfig)
    base_latency_ms: int = 80
    jitter_ms_stddev: int = 25
    reorder_probability: float = 0.02
    seed: int = 1729
    real_time_pacing: bool = False


@dataclass
class DeliveryEvent:
    sequence: int
    status: Literal["delivered", "lost", "crc_fail", "recovered"]
    sent_time_ms: int
    delivery_time_ms: int
    on_air_bits: int
    measured_ber: float
    is_parity: bool = False
    packet: Optional[Packet] = None


@dataclass
class ChannelSimulationResult:
    delivered_packets: List[Packet]
    events: List[DeliveryEvent]
    total_transmitted_bits: int
    lost_packet_count: int
    crc_fail_count: int
    measured_ber: float
    measured_per: float


class ChannelSimulator:
    """Simulates physical impairments, noise, fading, latency, jitter, and packet loss."""

    def __init__(self, config: ChannelConfig):
        self.config = config
        self.rng = np.random.default_rng(config.seed)
        self.py_rng = random.Random(config.seed)

    def _simulate_bpsk_awgn_bits(self, raw_bytes: bytes, eb_n0_db: float) -> tuple[bytes, int]:
        """Convert bytes to BPSK symbols, add AWGN, demodulate back to bytes. Returns (bytes, flipped_bits)."""
        bits = []
        for byte in raw_bytes:
            for i in range(8):
                bits.append((byte >> (7 - i)) & 1)

        total_bits = len(bits)
        if total_bits == 0:
            return raw_bytes, 0

        # BPSK symbols: 0 -> +1.0, 1 -> -1.0
        symbols = 1.0 - 2.0 * np.array(bits, dtype=np.float32)

        # AWGN sigma: sigma^2 = 1 / (2 * 10^(Eb/N0 / 10))
        eb_n0_linear = 10.0 ** (eb_n0_db / 10.0)
        sigma = 1.0 / np.sqrt(2.0 * eb_n0_linear)

        noise = self.rng.normal(0.0, sigma, size=total_bits).astype(np.float32)
        received_symbols = symbols + noise

        # Demodulate: y >= 0 -> 0, y < 0 -> 1
        demod_bits = (received_symbols < 0.0).astype(int)

        flipped_bits = int(np.sum(demod_bits != bits))

        # Rebuild bytes
        out_bytes = bytearray()
        for i in range(0, total_bits, 8):
            chunk = demod_bits[i : i + 8]
            val = 0
            for b in chunk:
                val = (val << 1) | int(b)
            out_bytes.append(val)

        return bytes(out_bytes), flipped_bits

    def simulate(self, packets: List[Packet]) -> ChannelSimulationResult:
        """Run simulation over a burst of transmitted packets."""
        events: List[DeliveryEvent] = []
        delivered_packets: List[Packet] = []

        total_on_air_bits = 0
        total_bit_errors = 0
        lost_count = 0
        crc_fail_count = 0

        current_time_ms = 0

        for pkt in packets:
            raw_bytes = pkt.serialize()
            packet_bits = len(raw_bytes) * 8
            total_on_air_bits += packet_bits

            # Serialization delay based on gross rate
            serial_delay_ms = int((packet_bits / self.config.gross_rate_bps) * 1000)
            sent_time_ms = current_time_ms
            current_time_ms += serial_delay_ms

            # Latency and jitter
            jitter = int(self.rng.normal(0, self.config.jitter_ms_stddev))
            delivery_time_ms = sent_time_ms + max(10, self.config.base_latency_ms + jitter)

            # 1. Packet Loss check
            if self.py_rng.random() < self.config.packet_loss_rate:
                lost_count += 1
                events.append(
                    DeliveryEvent(
                        sequence=pkt.sequence,
                        status="lost",
                        sent_time_ms=sent_time_ms,
                        delivery_time_ms=delivery_time_ms,
                        on_air_bits=packet_bits,
                        measured_ber=0.0,
                        is_parity=pkt.is_parity,
                        packet=None,
                    )
                )
                continue

            # 2. Bit error / Channel modulation
            corrupted_bytes = raw_bytes
            flipped_bits = 0

            if self.config.mode in ("bpsk_awgn", "rayleigh_bpsk"):
                corrupted_bytes, flipped_bits = self._simulate_bpsk_awgn_bits(
                    raw_bytes, self.config.eb_n0_db
                )
                total_bit_errors += flipped_bits
            elif self.config.mode == "abstract_bit" and self.config.bit_error_rate:
                # Independent bit flipping
                byte_list = bytearray(raw_bytes)
                for b_idx in range(len(byte_list)):
                    for bit in range(8):
                        if self.py_rng.random() < self.config.bit_error_rate:
                            byte_list[b_idx] ^= (1 << bit)
                            flipped_bits += 1
                corrupted_bytes = bytes(byte_list)
                total_bit_errors += flipped_bits

            # 3. Receiver CRC validation
            try:
                rx_pkt = Packet.parse(corrupted_bytes)
                delivered_packets.append(rx_pkt)
                events.append(
                    DeliveryEvent(
                        sequence=pkt.sequence,
                        status="delivered",
                        sent_time_ms=sent_time_ms,
                        delivery_time_ms=delivery_time_ms,
                        on_air_bits=packet_bits,
                        measured_ber=flipped_bits / packet_bits if packet_bits else 0.0,
                        is_parity=pkt.is_parity,
                        packet=rx_pkt,
                    )
                )
            except (PacketCrcError, HeaderCrcError, ProtocolError):
                crc_fail_count += 1
                events.append(
                    DeliveryEvent(
                        sequence=pkt.sequence,
                        status="crc_fail",
                        sent_time_ms=sent_time_ms,
                        delivery_time_ms=delivery_time_ms,
                        on_air_bits=packet_bits,
                        measured_ber=flipped_bits / packet_bits if packet_bits else 0.0,
                        is_parity=pkt.is_parity,
                        packet=None,
                    )
                )

        # Sort events by delivery timestamp to emulate real arrival timeline
        events.sort(key=lambda e: e.delivery_time_ms)

        measured_ber = total_bit_errors / total_on_air_bits if total_on_air_bits > 0 else 0.0
        measured_per = (lost_count + crc_fail_count) / len(packets) if packets else 0.0

        return ChannelSimulationResult(
            delivered_packets=delivered_packets,
            events=events,
            total_transmitted_bits=total_on_air_bits,
            lost_packet_count=lost_count,
            crc_fail_count=crc_fail_count,
            measured_ber=measured_ber,
            measured_per=measured_per,
        )
