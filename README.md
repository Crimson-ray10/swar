# iTantra — Indian Multilingual Semantic Radio Transceiver

**Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for Low-Bitrate Links (0.5 – 2.0 kbps)**

---

## 1. Overview & Architecture

iTantra changes the objective of extreme low-bitrate voice communication from **waveform preservation** to **linguistic/semantic recovery**. Under link budgets of $0.5$ to $2.0\text{ kbps}$ gross rate where conventional audio codecs fail or produce severely degraded speech, iTantra preserves the message:

```
[Speaker Audio (16 kHz Mono)]
        │
        ▼
[AudioWorklet / VAD]
        │
        ▼
[ASR Router] ────────► IndicConformer 600M (Indic) / faster-whisper (English)
        │
        ▼
[Normalizer & Spans] ─► NFC Unicode, Critical Literals (Digits, Negations, Dates)
        │
        ▼
[Source Codec] ──────► 14-bit packed SentencePiece Unigram Token IDs (V=16,384)
        │
        ▼
[ITP/1 Protocol] ────► 14-byte Header + CRC-16 + Conv(K=7, R=2/3) + XOR(4,3) Parity
        │
        ▼
[Channel Sim] ───────► BPSK AWGN / Rayleigh Fading / Gilbert-Elliott / Loss / Jitter
        │
        ▼
[Rx Demod & FEC] ────► CRC-16 check (Corruption -> Erasure) + Playout Buffer + XOR Recovery
        │
        ▼
[Rx Frame & TTS] ────► IndicF5 (Indic, 24 kHz) / Piper (English)
        │
        ▼
[Synthetic Speech Output]
```

### Truth Boundaries
1. **Semantic, Not Acoustic Waveform**: The receiver synthesizes a fresh synthetic voice from the recovered transcript. It is explicitly labeled **Synthetic Receiver Voice**.
2. **Deterministic Wire Format (`text_v1`)**: Packed 14-bit SentencePiece Unigram tokens protected by CRC-16 and XOR parity.
3. **Truth in Telemetry**: On-air bitrate $R_{\text{wire}}$ strictly accounts for preambles, headers, payload, CRC, and FEC parity.
4. **No Hallucinated Words**: Any packet loss exceeding FEC capacity triggers an explicit erasure/partial warning. Corrupted frames are never fed to a language model to guess missing words.

---

## 2. Monorepo Structure

* `packages/protocol`: ITP/1 binary packet definition, bitwise CRC-8 and CRC-16/CCITT checksums. Zero ML dependencies.
* `packages/engine`: Core DSP audio preprocessing, VAD, language registry, text normalizer, SentencePiece 14-bit packing, XOR(4,3) parity, Viterbi decoder, channel simulator, STT/TTS adapters.
* `services/api`: FastAPI application serving REST endpoints and streaming WebSocket `/api/v1/stream/{session_id}`.
* `apps/web`: 3-column engineering dashboard built with React 19, TypeScript, and Vite.
* `evaluation/`: Automated factorial benchmark matrix runner and reports.
* `scripts/`: Diagnostic doctor script, bootstrap script, fixture generator, demo runner, and offline packager.
* `tests/`: Protocol, unit, and integration test suites.

---

## 3. Quickstart & Operating Commands (Windows PowerShell)

### Step 1: System Diagnostic Check
Run the doctor script to verify Python, Node.js, GPU, configs, and libraries:
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\doctor.ps1
```

### Step 2: Run Automated Tests
Run the comprehensive test suite (protocol, unit, and API integration):
```powershell
.\.venv\Scripts\python.exe -m pytest -v
```

### Step 3: Run Scripted Live Demo
Run an end-to-end transmission of a Hindi speech fixture through a simulated low-bitrate BPSK AWGN channel:
```powershell
.\.venv\Scripts\python.exe .\scripts\run_demo.py --fixture .\tests\fixtures\hi_short.wav --language hi --profile interactive_2
```

### Step 4: Run Factorial Evaluation Matrix
Benchmark all rates (0.5k, 1k, 2k, 4k bps) across languages and channel impairments:
```powershell
.\.venv\Scripts\python.exe .\evaluation\scripts\run_matrix.py
```

### Step 5: Launch Backend and Frontend

**Terminal 1 (Backend API):**
```powershell
.\.venv\Scripts\python.exe -m uvicorn itantra_api.main:app --host 127.0.0.1 --port 8000 --reload
```

**Terminal 2 (Frontend Instrument UI):**
```powershell
npm --prefix .\apps\web run dev
```
Open your browser at `http://127.0.0.1:5173`.

---

## 4. Evaluation Matrix Highlights

| Language | Gross Rate | Channel Condition | Wire Rate | Compression vs 256k PCM | Recovery Status |
|---|---|---|---|---|---|
| **Hindi** | 2000 bps | Clean AWGN | 1504 bps | **170.2×** | Complete (Exact) |
| **Hindi** | 2000 bps | BPSK AWGN (5 dB) | 1504 bps | **170.2×** | Partial / Erasure |
| **Tamil** | 2000 bps | Clean AWGN | 1204 bps | **212.6×** | Complete (Exact) |
| **English** | 2000 bps | Clean AWGN | 656 bps | **390.2×** | Complete (Exact) |

---

## 5. Offline Demo Readiness

Package an offline bundle containing all configurations, audio fixtures, and the compiled frontend build:
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\create_demo_bundle.ps1
```
The resulting `bundle/` directory can run standalone without active internet connectivity.
