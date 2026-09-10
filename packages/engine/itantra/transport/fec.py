"""Forward Error Correction (FEC) and Link Protection for iTantra.

Implements:
1. Rolling XOR(4,3) packet parity erasure code (recovers 1 erased packet per 3 data packets).
2. Standard Convolutional Encoder (K=7, generators 171_8, 133_8) and Viterbi Decoder.
"""

from typing import List, Optional
from itantra_protocol.packet import Packet, FLAG_PARITY

# Standard NASA / IEEE 802.11 polynomials for K=7
POLY_A = 0b1111001  # 171 octal
POLY_B = 0b1011011  # 133 octal
K = 7
NUM_STATES = 1 << (K - 1)  # 64 states


# ==========================================
# 1. Rolling XOR(4,3) Parity Code
# ==========================================

def create_xor_parity_packet(
    data_packets: List[Packet],
    generation_id: int,
    parity_sequence: int,
) -> Packet:
    """
    Computes byte-wise XOR parity over up to 3 data packets in a generation.
    Returns a parity Packet with FLAG_PARITY set.
    """
    if not data_packets:
        raise ValueError("Cannot create parity over empty packet list")

    max_len = max(len(p.payload) for p in data_packets)
    parity_bytes = bytearray(max_len)

    for p in data_packets:
        padded = p.payload + b"\x00" * (max_len - len(p.payload))
        for i in range(max_len):
            parity_bytes[i] ^= padded[i]

    ref_pkt = data_packets[0]
    return Packet(
        profile=ref_pkt.profile,
        flags=ref_pkt.flags | FLAG_PARITY,
        session_id=ref_pkt.session_id,
        sequence=parity_sequence,
        start_tick_20ms=ref_pkt.start_tick_20ms,
        language_id=ref_pkt.language_id,
        payload_type=ref_pkt.payload_type,
        fragment_index=15,  # Designated parity fragment index
        fragment_count=ref_pkt.fragment_count,
        generation_id=generation_id,
        payload=bytes(parity_bytes),
    )


def recover_xor_generation(
    received_packets: List[Packet],
    expected_data_sequences: List[int],
    parity_packet: Optional[Packet],
) -> List[Packet]:
    """
    Attempts to recover missing data packets in a generation.
    If exactly 1 data packet is missing and the parity packet is available,
    it reconstructs the missing packet via XOR.
    """
    data_by_seq = {p.sequence: p for p in received_packets if not p.is_parity}
    missing_seqs = [seq for seq in expected_data_sequences if seq not in data_by_seq]

    # If no missing packets, all data packets survived
    if len(missing_seqs) == 0:
        return [data_by_seq[seq] for seq in expected_data_sequences]

    # If exactly 1 missing packet and parity is present, recover it!
    if len(missing_seqs) == 1 and parity_packet is not None:
        missing_seq = missing_seqs[0]
        max_len = len(parity_packet.payload)
        recovered_bytes = bytearray(parity_packet.payload)

        # XOR remaining survived data packets
        for seq, p in data_by_seq.items():
            padded = p.payload + b"\x00" * (max_len - len(p.payload))
            for i in range(max_len):
                recovered_bytes[i] ^= padded[i]

        ref_pkt = parity_packet
        # Determine missing fragment index
        rec_frag_idx = expected_data_sequences.index(missing_seq)

        recovered_pkt = Packet(
            profile=ref_pkt.profile,
            flags=0,
            session_id=ref_pkt.session_id,
            sequence=missing_seq,
            start_tick_20ms=ref_pkt.start_tick_20ms,
            language_id=ref_pkt.language_id,
            payload_type=ref_pkt.payload_type,
            fragment_index=rec_frag_idx,
            fragment_count=len(expected_data_sequences),
            generation_id=ref_pkt.generation_id,
            payload=bytes(recovered_bytes),
        )
        data_by_seq[missing_seq] = recovered_pkt
        return [data_by_seq[seq] for seq in expected_data_sequences]

    # More than 1 erasure or no parity: cannot recover
    return [data_by_seq[seq] for seq in expected_data_sequences if seq in data_by_seq]


# ==========================================
# 2. Convolutional Encoder & Viterbi Decoder
# ==========================================

def parity_bit(val: int) -> int:
    return bin(val).count("1") % 2


def convolutional_encode(bits: List[int]) -> List[int]:
    """
    Encode bit stream using K=7, generators (171_8, 133_8).
    Code rate 1/2.
    """
    state = 0
    coded_bits: List[int] = []

    # Add 6 flush zero bits
    padded = list(bits) + [0] * (K - 1)

    for b in padded:
        state = ((state << 1) | (b & 1)) & 0x7F
        out_a = parity_bit(state & POLY_A)
        out_b = parity_bit(state & POLY_B)
        coded_bits.append(out_a)
        coded_bits.append(out_b)

    return coded_bits


def viterbi_decode(coded_bits: List[int], original_length: int) -> List[int]:
    """
    Viterbi hard-decision decoding for K=7 rate 1/2.
    """
    if len(coded_bits) % 2 != 0:
        coded_bits = list(coded_bits) + [0]

    num_steps = len(coded_bits) // 2
    path_metrics = [float("inf")] * NUM_STATES
    path_metrics[0] = 0.0  # Start at state 0
    history = []

    for step in range(num_steps):
        r0 = coded_bits[step * 2]
        r1 = coded_bits[step * 2 + 1]

        new_metrics = [float("inf")] * NUM_STATES
        prev_states = [0] * NUM_STATES

        for state in range(NUM_STATES):
            if path_metrics[state] == float("inf"):
                continue

            for input_bit in (0, 1):
                next_state = ((state << 1) | input_bit) & (NUM_STATES - 1)
                full_state = ((state << 1) | input_bit) & 0x7F

                e0 = parity_bit(full_state & POLY_A)
                e1 = parity_bit(full_state & POLY_B)

                # Hamming distance branch metric
                bm = (r0 ^ e0) + (r1 ^ e1)
                total_m = path_metrics[state] + bm

                if total_m < new_metrics[next_state]:
                    new_metrics[next_state] = total_m
                    prev_states[next_state] = state

        path_metrics = new_metrics
        history.append(prev_states)

    # Traceback
    best_state = min(range(NUM_STATES), key=lambda s: path_metrics[s])
    decoded_reversed = []

    for step in reversed(range(num_steps)):
        prev = history[step][best_state]
        in_bit = best_state & 1
        decoded_reversed.append(in_bit)
        best_state = prev

    decoded = list(reversed(decoded_reversed))
    return decoded[:original_length]
