"""FastAPI REST and WebSocket API service for iTantra."""

from dataclasses import asdict
import json
from pathlib import Path
from typing import Any, Dict, List, Optional
import uuid
import yaml

from fastapi import FastAPI, File, Form, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from itantra.channel.simulator import ChannelConfig
from itantra.language.registry import LanguageRegistry
from itantra.orchestration.run import EndToEndRunner, RunConfig, RunReport
from itantra.semantic.tokenizer import TokenizerAdapter
from itantra.stt.mock import MockSTTProvider
from itantra.tts.mock import MockTTSProvider

app = FastAPI(
    title="iTantra Semantic Radio API",
    version="1.0.0",
    description="Transcript-first semantic radio transceiver for Indian multilingual low-bitrate links",
)

# Enable CORS for React Vite frontend
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Global services & registries
language_registry = LanguageRegistry()
tokenizer_adapter = TokenizerAdapter()
stt_provider = MockSTTProvider()
tts_provider = MockTTSProvider()
orchestrator = EndToEndRunner(
    language_registry=language_registry,
    tokenizer=tokenizer_adapter,
    stt_provider=stt_provider,
    tts_provider=tts_provider,
)

# In-memory session & run storage
_sessions: Dict[str, Dict[str, Any]] = {}
_runs: Dict[str, RunReport] = {}


# ==========================================
# Pydantic Schemas
# ==========================================

class ChannelInput(BaseModel):
    mode: str = "bpsk_awgn"
    gross_rate_bps: int = 2000
    eb_n0_db: float = 5.0
    packet_loss_rate: float = 0.0
    bit_error_rate: Optional[float] = None
    base_latency_ms: int = 80
    jitter_ms_stddev: int = 25
    reorder_probability: float = 0.02
    seed: int = 1729
    real_time_pacing: bool = False


class SessionCreate(BaseModel):
    language: str = "hi"
    profile: str = "text_v1"
    wire_profile: str = "interactive_2"
    channel: ChannelInput = Field(default_factory=ChannelInput)
    tts_voice_id: Optional[str] = None


# ==========================================
# REST Endpoints
# ==========================================

@app.get("/api/v1/health")
def get_health():
    return {
        "status": "healthy",
        "service": "iTantra Semantic Transceiver API",
        "version": "1.0.0",
        "models_status": {
            "stt": "mock_ready" if stt_provider.is_available() else "unavailable",
            "tts": "mock_ready" if tts_provider.is_available() else "unavailable",
            "tokenizer": "ready",
        },
    }


@app.get("/api/v1/capabilities")
def get_capabilities():
    return {
        "profiles": ["text_v1", "semantic_vq_v1 (experimental)"],
        "channel_modes": ["bpsk_awgn", "rayleigh_bpsk", "abstract_packet", "abstract_bit"],
        "max_audio_duration_s": 30,
        "supported_sample_rates_hz": [16000, 24000],
        "hardware": "NVIDIA GPU / CPU fallback",
        "truth_policy": "No fabricated metrics, CRC failure yields erasure",
    }


@app.get("/api/v1/languages")
def list_languages(enabled_only: bool = False):
    langs = language_registry.list_all(enabled_only=enabled_only)
    return [asdict(l) for l in langs]


@app.get("/api/v1/wire-profiles")
def list_wire_profiles():
    with open("configs/wire_profiles.yaml", "r", encoding="utf-8") as f:
        data = yaml.safe_load(f)
    return data.get("profiles", {})


@app.post("/api/v1/sessions")
def create_session(req: SessionCreate):
    session_id = str(uuid.uuid4())
    _sessions[session_id] = req.model_dump()
    return {
        "session_id": session_id,
        "config": req.model_dump(),
        "warnings": ["TTS output is synthetic and does not preserve speaker identity."],
    }


@app.post("/api/v1/transcriptions")
async def transcribe_audio(
    audio: UploadFile = File(...),
    language: str = Form("hi"),
    decoder_mode: str = Form("ctc"),
):
    content = await audio.read()
    res = stt_provider.transcribe(content, language=language, decoder_mode=decoder_mode)
    return {
        "raw_text": res.raw_text,
        "normalized_text": res.normalized_text,
        "language": res.language,
        "asr_confidence": res.asr_confidence,
        "processing_ms": res.processing_ms,
        "model_id": res.model_id,
    }


@app.post("/api/v1/runs")
async def execute_run(
    audio: UploadFile = File(...),
    language: str = Form("hi"),
    profile: str = Form("text_v1"),
    wire_profile: str = Form("interactive_2"),
    channel_mode: str = Form("bpsk_awgn"),
    gross_rate_bps: int = Form(2000),
    eb_n0_db: float = Form(5.0),
    packet_loss_rate: float = Form(0.0),
    base_latency_ms: int = Form(80),
    jitter_ms_stddev: int = Form(25),
    seed: int = Form(1729),
    use_fec: bool = Form(True),
):
    content = await audio.read()
    ch_cfg = ChannelConfig(
        mode=channel_mode,
        gross_rate_bps=gross_rate_bps,
        eb_n0_db=eb_n0_db,
        packet_loss_rate=packet_loss_rate,
        base_latency_ms=base_latency_ms,
        jitter_ms_stddev=jitter_ms_stddev,
        seed=seed,
    )
    run_cfg = RunConfig(
        language=language,
        profile=profile,
        wire_profile=wire_profile,
        channel=ch_cfg,
        use_fec=use_fec,
    )

    report = orchestrator.run(content, run_cfg)
    _runs[report.run_id] = report
    return asdict(report)


@app.get("/api/v1/runs/{run_id}")
def get_run_report(run_id: str):
    report = _runs.get(run_id)
    if not report:
        raise HTTPException(status_code=404, detail="Run not found")
    return asdict(report)


# ==========================================
# WebSocket Streaming Endpoint
# ==========================================

@app.websocket("/api/v1/stream/{session_id}")
async def websocket_stream(websocket: WebSocket, session_id: str):
    await websocket.accept()
    session = _sessions.get(session_id, {})
    lang = session.get("language", "hi")
    audio_accumulator = bytearray()

    try:
        # Await start control frame
        init_msg = await websocket.receive_text()
        init_data = json.loads(init_msg)

        await websocket.send_json({"event": "ready", "session_id": session_id})

        while True:
            message = await websocket.receive()
            if "bytes" in message and message["bytes"]:
                # Binary PCM audio chunk (16 kHz mono)
                chunk = message["bytes"]
                audio_accumulator.extend(chunk)

                # Stream intermediate VAD event
                await websocket.send_json({
                    "event": "vad",
                    "session_id": session_id,
                    "is_speech": True,
                    "buffered_ms": int((len(audio_accumulator) / 2 / 16000) * 1000),
                })
            elif "text" in message and message["text"]:
                ctrl = json.loads(message["text"])
                action = ctrl.get("action")

                if action == "finalize":
                    # Run end-to-end transceiver on accumulated audio
                    ch_cfg = ChannelConfig(
                        mode=session.get("channel", {}).get("mode", "bpsk_awgn"),
                        gross_rate_bps=session.get("channel", {}).get("gross_rate_bps", 2000),
                        eb_n0_db=session.get("channel", {}).get("eb_n0_db", 5.0),
                        packet_loss_rate=session.get("channel", {}).get("packet_loss_rate", 0.0),
                        seed=session.get("channel", {}).get("seed", 1729),
                    )
                    run_cfg = RunConfig(
                        language=lang,
                        channel=ch_cfg,
                        use_fec=True,
                    )
                    report = orchestrator.run(bytes(audio_accumulator), run_cfg)

                    await websocket.send_json({
                        "event": "complete",
                        "report": asdict(report),
                    })
                    audio_accumulator.clear()

                elif action == "cancel":
                    audio_accumulator.clear()
                    await websocket.send_json({"event": "cancelled", "session_id": session_id})
                    break

    except WebSocketDisconnect:
        pass
