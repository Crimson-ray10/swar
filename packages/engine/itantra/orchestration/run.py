"""End-to-end pipeline orchestrator and honest telemetry computer for iTantra."""

import base64
from dataclasses import asdict, dataclass, field
import hashlib
import json
import time
from typing import Any, Dict, List, Optional
import uuid

from itantra.audio.preprocess import process_audio_upload
from itantra.channel.simulator import ChannelConfig, ChannelSimulator, DeliveryEvent
from itantra.language.registry import LanguageRegistry
from itantra.semantic.schema import SemanticFrame, ReconstructedFrame
from itantra.semantic.text_codec import (
    decode_packets_to_reconstructed_frame,
    encode_semantic_frame_to_packets,
)
from itantra.semantic.tokenizer import TokenizerAdapter
from itantra.stt.base import STTProvider
from itantra.stt.mock import MockSTTProvider
from itantra.text.normalize import extract_critical_spans, normalize_text
from itantra.transport.fec import create_xor_parity_packet, recover_xor_generation
from itantra.tts.base import TTSProvider
from itantra.tts.mock import MockTTSProvider


@dataclass
class RunConfig:
    language: str = "hi"
    profile: str = "text_v1"
    wire_profile: str = "interactive_2"
    channel: ChannelConfig = field(default_factory=ChannelConfig)
    use_fec: bool = True
    session_id: int = 0x1729
    tts_voice_id: Optional[str] = None


@dataclass
class RunReport:
    run_id: str
    status: str
    input_info: Dict[str, Any]
    transcript: Dict[str, Any]
    transport: Dict[str, Any]
    receiver: Dict[str, Any]
    latency_ms: Dict[str, int]
    audio_output_base64: Optional[str]
    delivery_events: List[Dict[str, Any]]
    reproducibility: Dict[str, Any]
    warnings: List[str] = field(default_factory=list)


class EndToEndRunner:
    """Executes the complete transcript-first semantic radio chain."""

    def __init__(
        self,
        language_registry: Optional[LanguageRegistry] = None,
        tokenizer: Optional[TokenizerAdapter] = None,
        stt_provider: Optional[STTProvider] = None,
        tts_provider: Optional[TTSProvider] = None,
    ):
        self.languages = language_registry or LanguageRegistry()
        self.tokenizer = tokenizer or TokenizerAdapter()
        self.stt = stt_provider or MockSTTProvider(simulated_latency_ms=20)
        self.tts = tts_provider or MockTTSProvider()

    def run(self, raw_audio_bytes: bytes, config: RunConfig) -> RunReport:
        t_start = time.perf_counter()
        run_id = str(uuid.uuid4())
        warnings: List[str] = []

        # 1. Capture / Preprocess
        t0 = time.perf_counter()
        pcm_16k, duration_s = process_audio_upload(raw_audio_bytes)
        duration_ms = int(duration_s * 1000)
        dur_s_safe = max(0.2, duration_s)
        t_capture = int((time.perf_counter() - t0) * 1000)

        # 2. ASR (Speech-to-Text)
        t0 = time.perf_counter()
        stt_res = self.stt.transcribe(pcm_16k, language=config.language)
        t_asr = int((time.perf_counter() - t0) * 1000)

        # 3. Normalization & Critical Literal Extraction
        t0 = time.perf_counter()
        normalized_text = normalize_text(stt_res.raw_text, config.language)
        critical_spans = extract_critical_spans(normalized_text, config.language)
        token_ids = self.tokenizer.encode(normalized_text, config.language)

        frame = SemanticFrame(
            protocol_version=1,
            profile="text_v1",
            utterance_id=1,
            language=config.language,
            normalizer_id="indic_nfc",
            token_model_id=self.tokenizer.model_sha256[:16],
            start_ms=0,
            duration_ms=duration_ms,
            normalized_text=normalized_text,
            token_ids=token_ids,
            critical_spans=critical_spans,
            asr_confidence=stt_res.asr_confidence,
        )

        # 4. Source Coding / Packetization
        lang_num = self.languages.numeric_id_for(config.language)
        data_packets = encode_semantic_frame_to_packets(
            frame=frame,
            session_id=config.session_id,
            generation_id=1,
            language_numeric_id=lang_num,
            start_sequence=1,
            max_payload_per_packet=32,
        )

        # Optional FEC Parity Packet
        packets_to_send = list(data_packets)
        parity_pkt = None
        if config.use_fec and len(data_packets) >= 1:
            parity_pkt = create_xor_parity_packet(
                data_packets=data_packets[:3],  # XOR over up to 3 packets
                generation_id=1,
                parity_sequence=len(data_packets) + 1,
            )
            packets_to_send.append(parity_pkt)

        t_encode = int((time.perf_counter() - t0) * 1000)

        # 5. Low-Bitrate Channel Simulation
        t0 = time.perf_counter()
        sim = ChannelSimulator(config.channel)
        sim_res = sim.simulate(packets_to_send)
        t_channel = int((time.perf_counter() - t0) * 1000)

        # 6. Receiver FEC Recovery & Jitter Playout
        t0 = time.perf_counter()
        rx_data_packets = [p for p in sim_res.delivered_packets if not p.is_parity]
        rx_parity_packet = next((p for p in sim_res.delivered_packets if p.is_parity), None)

        expected_seqs = [p.sequence for p in data_packets]
        recovered_packets = rx_data_packets
        recovered_fec_count = 0

        if config.use_fec and rx_parity_packet is not None:
            recovered_packets = recover_xor_generation(
                received_packets=rx_data_packets,
                expected_data_sequences=expected_seqs,
                parity_packet=rx_parity_packet,
            )
            recovered_fec_count = len(recovered_packets) - len(rx_data_packets)

        # 7. Source Decoding
        reconstructed_frame = decode_packets_to_reconstructed_frame(
            packets=recovered_packets,
            tokenizer=self.tokenizer,
            expected_fragment_count=len(data_packets),
        )
        t_decode = int((time.perf_counter() - t0) * 1000)

        # 8. Receiver TTS Synthesis
        t0 = time.perf_counter()
        audio_output_b64 = None
        tts_status = "skipped"
        if reconstructed_frame.status == "exact" and reconstructed_frame.text:
            tts_res = self.tts.synthesize(
                text=reconstructed_frame.text,
                language=config.language,
                voice_id=config.tts_voice_id,
            )
            audio_output_b64 = base64.b64encode(tts_res.pcm_bytes).decode("ascii")
            tts_status = "complete"
        elif reconstructed_frame.status in ("partial", "unrecoverable"):
            tts_status = "erasure"
            warnings.append("Receiver frame suffered packet loss exceeding FEC capability; synthesis withheld.")
        t_tts = int((time.perf_counter() - t0) * 1000)

        t_total = int((time.perf_counter() - t_start) * 1000)

        # Honest Rate Accounting Equations
        # R_wire = transmitted_on_air_bits / duration_s
        on_air_bits = sim_res.total_transmitted_bits
        source_bits = len(token_ids) * 14
        good_bits = source_bits if reconstructed_frame.status == "exact" else 0

        actual_wire_bps = round(on_air_bits / dur_s_safe, 1)
        compression_ratio = round(256000.0 / actual_wire_bps, 1) if actual_wire_bps > 0 else 0.0

        delivery_event_dicts = [
            {
                "sequence": e.sequence,
                "status": e.status,
                "sent_time_ms": e.sent_time_ms,
                "delivery_time_ms": e.delivery_time_ms,
                "on_air_bits": e.on_air_bits,
                "measured_ber": e.measured_ber,
                "is_parity": e.is_parity,
            }
            for e in sim_res.events
        ]

        config_str = json.dumps(asdict(config.channel), sort_keys=True)
        config_hash = hashlib.sha256(config_str.encode("utf-8")).hexdigest()[:16]

        return RunReport(
            run_id=run_id,
            status="complete" if reconstructed_frame.status == "exact" else reconstructed_frame.status,
            input_info={
                "duration_ms": duration_ms,
                "sample_rate_hz": 16000,
                "channels": 1,
            },
            transcript={
                "raw": stt_res.raw_text,
                "normalized": normalized_text,
                "language": config.language,
                "asr_confidence": stt_res.asr_confidence,
                "critical_spans": [asdict(s) for s in critical_spans],
            },
            transport={
                "gross_rate_bps": config.channel.gross_rate_bps,
                "wire_bits": on_air_bits,
                "source_bits": source_bits,
                "good_bits": good_bits,
                "actual_wire_bps": actual_wire_bps,
                "compression_ratio_vs_pcm": compression_ratio,
                "packet_count": len(packets_to_send),
                "lost_packets": sim_res.lost_packet_count,
                "crc_fail_count": sim_res.crc_fail_count,
                "recovered_packets": recovered_fec_count,
                "measured_ber": round(sim_res.measured_ber, 5),
                "measured_per": round(sim_res.measured_per, 3),
            },
            receiver={
                "status": reconstructed_frame.status,
                "text": reconstructed_frame.text,
                "tts_status": tts_status,
                "missing_packet_sequences": reconstructed_frame.missing_packet_sequences,
                "voice_label": "synthetic receiver voice",
            },
            latency_ms={
                "capture": t_capture,
                "asr": t_asr,
                "encode": t_encode,
                "channel": t_channel,
                "decode": t_decode,
                "tts": t_tts,
                "end_to_end": t_total,
            },
            audio_output_base64=audio_output_b64,
            delivery_events=delivery_event_dicts,
            reproducibility={
                "seed": config.channel.seed,
                "config_hash": config_hash,
                "tokenizer_sha256": self.tokenizer.model_sha256[:16],
            },
            warnings=warnings,
        )
