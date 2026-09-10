import pytest
import sys
import os
import random

sys.path.insert(0, os.path.abspath("packages/protocol"))
sys.path.insert(0, os.path.abspath("packages/engine"))

from itantra_protocol.packet import Packet, PROFILE_TEXT_V1, PAYLOAD_TYPE_EXACT_SP
from itantra.transport.fec import (
    create_xor_parity_packet,
    recover_xor_generation,
    convolutional_encode,
    viterbi_decode,
)
from itantra.channel.simulator import ChannelSimulator, ChannelConfig


def test_xor_parity_erasure_recovery():
    # Create 3 data packets
    data_pkts = [
        Packet(
            profile=PROFILE_TEXT_V1,
            flags=0,
            session_id=1,
            sequence=10,
            start_tick_20ms=0,
            language_id=1,
            payload_type=PAYLOAD_TYPE_EXACT_SP,
            fragment_index=0,
            fragment_count=3,
            generation_id=5,
            payload=b"Fragment Alpha-01",
        ),
        Packet(
            profile=PROFILE_TEXT_V1,
            flags=0,
            session_id=1,
            sequence=11,
            start_tick_20ms=0,
            language_id=1,
            payload_type=PAYLOAD_TYPE_EXACT_SP,
            fragment_index=1,
            fragment_count=3,
            generation_id=5,
            payload=b"Fragment Beta-0222",
        ),
        Packet(
            profile=PROFILE_TEXT_V1,
            flags=0,
            session_id=1,
            sequence=12,
            start_tick_20ms=0,
            language_id=1,
            payload_type=PAYLOAD_TYPE_EXACT_SP,
            fragment_index=2,
            fragment_count=3,
            generation_id=5,
            payload=b"Fragment Gamma-03",
        ),
    ]

    # Generate parity packet
    parity_pkt = create_xor_parity_packet(data_pkts, generation_id=5, parity_sequence=13)
    assert parity_pkt.is_parity is True

    # Drop packet #2 (sequence 11)
    survived = [data_pkts[0], data_pkts[2]]
    expected_seqs = [10, 11, 12]

    # Recover
    recovered = recover_xor_generation(survived, expected_seqs, parity_pkt)
    assert len(recovered) == 3
    recovered_seq11 = next(p for p in recovered if p.sequence == 11)
    assert recovered_seq11.payload == b"Fragment Beta-0222"


def test_convolutional_viterbi_codec():
    bits = [1, 0, 1, 1, 0, 0, 1, 0, 1, 1, 1, 0, 0, 1]
    coded = convolutional_encode(bits)
    assert len(coded) == 2 * (len(bits) + 6)  # rate 1/2 + 6 flush bits

    # Clean channel decode
    decoded = viterbi_decode(coded, len(bits))
    assert decoded == bits

    # Add 1 bit error
    noisy_coded = list(coded)
    noisy_coded[5] ^= 1
    decoded_noisy = viterbi_decode(noisy_coded, len(bits))
    assert decoded_noisy == bits


def test_channel_simulator_clean():
    cfg = ChannelConfig(
        mode="bpsk_awgn",
        gross_rate_bps=2000,
        eb_n0_db=15.0,  # High SNR -> near-zero BER
        packet_loss_rate=0.0,
        seed=42,
    )
    sim = ChannelSimulator(cfg)

    pkts = [
        Packet(
            profile=PROFILE_TEXT_V1,
            flags=0,
            session_id=10,
            sequence=i,
            start_tick_20ms=0,
            language_id=1,
            payload_type=PAYLOAD_TYPE_EXACT_SP,
            fragment_index=i,
            fragment_count=4,
            generation_id=1,
            payload=f"Payload {i}".encode("utf-8"),
        )
        for i in range(4)
    ]

    res = sim.simulate(pkts)
    assert len(res.delivered_packets) == 4
    assert res.lost_packet_count == 0
    assert res.crc_fail_count == 0
    assert res.measured_ber == 0.0
    assert res.total_transmitted_bits > 0


def test_channel_simulator_low_snr_corruption():
    cfg = ChannelConfig(
        mode="bpsk_awgn",
        gross_rate_bps=2000,
        eb_n0_db=-3.0,  # Severe noise -> many bit errors
        packet_loss_rate=0.0,
        seed=100,
    )
    sim = ChannelSimulator(cfg)

    pkts = [
        Packet(
            profile=PROFILE_TEXT_V1,
            flags=0,
            session_id=10,
            sequence=i,
            start_tick_20ms=0,
            language_id=1,
            payload_type=PAYLOAD_TYPE_EXACT_SP,
            fragment_index=i,
            fragment_count=5,
            generation_id=1,
            payload=b"Longer packet payload that will get bit errors in bad SNR",
        )
        for i in range(5)
    ]

    res = sim.simulate(pkts)
    # At -3 dB SNR, CRC must detect corrupt packets and reject them
    assert res.crc_fail_count > 0 or res.measured_ber > 0.0
