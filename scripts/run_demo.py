"""Scripted 2-3 minute demo execution command for iTantra."""

import argparse
from pathlib import Path
import sys
import os

# Reconfigure Windows console to handle Hindi, Tamil, and other Unicode scripts cleanly
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

sys.path.insert(0, os.path.abspath("packages/protocol"))
sys.path.insert(0, os.path.abspath("packages/engine"))

from itantra.orchestration.run import EndToEndRunner, RunConfig
from itantra.channel.simulator import ChannelConfig


def main():
    parser = argparse.ArgumentParser(description="iTantra Low-Bitrate Semantic Radio Transceiver Demo")
    parser.add_argument("--config", default="configs/app.local.yaml", help="Path to local app config")
    parser.add_argument("--fixture", default="tests/fixtures/hi_short.wav", help="Path to input audio fixture")
    parser.add_argument("--profile", default="interactive_2", help="Wire profile name")
    parser.add_argument("--language", default="hi", help="Language code (hi, ta, en)")
    parser.add_argument("--snr", type=float, default=6.0, help="AWGN Eb/N0 in dB")
    parser.add_argument("--loss", type=float, default=0.05, help="Packet loss rate (0.0 - 0.5)")
    args = parser.parse_args()

    fixture_path = Path(args.fixture)
    if not fixture_path.exists():
        print(f"Error: Fixture {fixture_path} not found.")
        sys.exit(1)

    print("==========================================================================")
    print("           iTantra Semantic Radio Transceiver Live Demonstrator           ")
    print("==========================================================================")
    print(f"Input Audio Fixture : {fixture_path}")
    print(f"Target Language     : {args.language}")
    print(f"Wire Profile        : {args.profile} (Gross budget 2000 bps)")
    print(f"Simulated Channel   : BPSK AWGN SNR = {args.snr} dB, Packet Loss = {args.loss * 100}%")
    print("--------------------------------------------------------------------------")

    audio_bytes = fixture_path.read_bytes()
    runner = EndToEndRunner()

    cfg = RunConfig(
        language=args.language,
        profile="text_v1",
        wire_profile=args.profile,
        channel=ChannelConfig(
            mode="bpsk_awgn",
            gross_rate_bps=2000,
            eb_n0_db=args.snr,
            packet_loss_rate=args.loss,
            seed=1729,
        ),
        use_fec=True,
    )

    report = runner.run(audio_bytes, cfg)

    print("\n[TRANSMITTER STAGE]")
    print(f"  Captured Audio Duration : {report.input_info['duration_ms']} ms (16 kHz mono)")
    print(f"  ASR Raw Transcript      : {report.transcript['raw']}")
    print(f"  Normalized Semantic Text: {report.transcript['normalized']}")
    print(f"  Critical Entities       : {len(report.transcript['critical_spans'])} spans identified")
    for s in report.transcript['critical_spans']:
        print(f"    • {s['span_type']}: '{s['literal']}' (chars {s['start_char']}..{s['end_char']})")

    print("\n[ON-AIR CHANNEL & TRANSPORT STAGE]")
    print(f"  Total Packets Emitted   : {report.transport['packet_count']} ITP/1 packets (incl. XOR parity)")
    print(f"  Total On-Air Wire Bits  : {report.transport['wire_bits']} bits (headers, CRC, FEC)")
    print(f"  Actual Measured Wire Rate: {report.transport['actual_wire_bps']} bps")
    print(f"  Compression vs 256k PCM : {report.transport['compression_ratio_vs_pcm']}×")
    print(f"  Channel Impairments     : Measured BER = {report.transport['measured_ber']*100:.2f}%, PER = {report.transport['measured_per']*100:.1f}%")
    print(f"  FEC Packet Recoveries   : {report.transport['recovered_packets']} packets recovered")

    print("\n[RECEIVER SINK STAGE]")
    print(f"  Reconstruction Status   : {report.receiver['status'].upper()}")
    print(f"  Recovered Text          : {report.receiver['text']}")
    print(f"  Receiver Voice Output   : {report.receiver['voice_label']}")
    print(f"  TTS Synthesis Status    : {report.receiver['tts_status']}")

    print("\n[END-TO-END LATENCY WATERFALL]")
    for stage, ms in report.latency_ms.items():
        print(f"  {stage:<14}: {ms} ms")

    print("==========================================================================")
    print("Demo Execution Complete: Reproducible Run ID", report.run_id)
    print("==========================================================================")


if __name__ == "__main__":
    main()
