"""Evaluation matrix runner for iTantra semantic radio transceiver."""

import argparse
from pathlib import Path
import csv
import sys
import os

# Add paths
sys.path.insert(0, os.path.abspath("packages/protocol"))
sys.path.insert(0, os.path.abspath("packages/engine"))

from itantra.orchestration.run import EndToEndRunner, RunConfig
from itantra.channel.simulator import ChannelConfig

REPORTS_DIR = Path("evaluation/reports")
REPORTS_DIR.mkdir(parents=True, exist_ok=True)


def run_matrix(output_csv: str = "evaluation/reports/benchmark_matrix.csv"):
    runner = EndToEndRunner()

    fixtures = {
        "hi": Path("tests/fixtures/hi_short.wav"),
        "ta": Path("tests/fixtures/ta_short.wav"),
        "en": Path("tests/fixtures/en_short.wav"),
    }

    rates = [500, 1000, 2000, 4000]
    channel_conditions = [
        {"name": "Clean AWGN", "eb_n0": 15.0, "loss": 0.0},
        {"name": "BPSK AWGN (5 dB)", "eb_n0": 5.0, "loss": 0.0},
        {"name": "AWGN + 5% Loss", "eb_n0": 5.0, "loss": 0.05},
        {"name": "AWGN + 10% Loss", "eb_n0": 5.0, "loss": 0.10},
    ]

    results = []

    print("=========================================================================================")
    print("                      iTantra Transceiver Evaluation Matrix                             ")
    print("=========================================================================================")
    print(f"{'Lang':<5} | {'Rate (bps)':<10} | {'Channel':<18} | {'Wire bps':<9} | {'Comp Ratio':<10} | {'BER (%)':<7} | {'PER (%)':<7} | {'Status':<8}")
    print("-----------------------------------------------------------------------------------------")

    for lang, fix_path in fixtures.items():
        if not fix_path.exists():
            continue
        audio_bytes = fix_path.read_bytes()

        for rate in rates:
            for cond in channel_conditions:
                cfg = RunConfig(
                    language=lang,
                    channel=ChannelConfig(
                        mode="bpsk_awgn",
                        gross_rate_bps=rate,
                        eb_n0_db=cond["eb_n0"],
                        packet_loss_rate=cond["loss"],
                        seed=1729,
                    ),
                    use_fec=True,
                )

                rep = runner.run(audio_bytes, cfg)

                wire_bps = rep.transport["actual_wire_bps"]
                comp = rep.transport["compression_ratio_vs_pcm"]
                ber = rep.transport["measured_ber"] * 100
                per = rep.transport["measured_per"] * 100
                status = rep.status

                print(f"{lang:<5} | {rate:<10} | {cond['name']:<18} | {wire_bps:<9} | {comp:<9.1f}× | {ber:<7.2f} | {per:<7.1f} | {status:<8}")

                results.append({
                    "language": lang,
                    "gross_rate_bps": rate,
                    "channel_condition": cond["name"],
                    "actual_wire_bps": wire_bps,
                    "compression_ratio_vs_pcm": comp,
                    "measured_ber": ber,
                    "measured_per": per,
                    "status": status,
                    "recovered_text": rep.receiver["text"],
                    "e2e_latency_ms": rep.latency_ms["end_to_end"],
                })

    # Save to CSV
    out_path = Path(output_csv)
    with open(out_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(results[0].keys()))
        writer.writeheader()
        writer.writerows(results)

    print("=========================================================================================")
    print(f"Report saved to: {out_path}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="evaluation/reports/benchmark_matrix.csv")
    args = parser.parse_args()
    run_matrix(args.output)
