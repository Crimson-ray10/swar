# Voice Bridge — Offline Multi-Transport Voice Communication

**Offline voice-communication system transmitting text over local Wi-Fi Direct, Wi-Fi LAN, and Bluetooth Classic RFCOMM.**

---

## 1. Overview & Architecture

Voice Bridge converts speech to text locally, transmits compact framed text messages over the available local transport, and converts that text back to speech locally on the receiving device.

> **Critical rule:** The transport can change; the voice pipeline never depends on the transport.

```text
                         VOICE BRIDGE SYSTEM
 ┌─────────────────────────────────────────────────────────┐
 │                      Voice Engine                       │
 │                                                         │
 │  Mic → Audio Buffer → VAD → STT → Sentence → Text       │
 │                                              │          │
 │                                              ▼          │
 │                                       Transport Layer   │
 │                                              │          │
 │                                 ┌────────────┴────────┐ │
 │                              Wi-Fi Direct  Wi-Fi LAN  Bluetooth
 │                                (TCP P2P)     (TCP)    (RFCOMM)  │
 │                                 └────────────┬────────┘ │
 │                                              ▼          │
 │                                       Reliability Layer │
 │                                     (ACK / Retry / Dedupe)
 │                                              │          │
 │                                              ▼          │
 │                                        Priority Queue   │
 │                                    ┌─────────┴────────┐ │
 │                               NORMAL_QUEUE       ALERT_QUEUE
 │                                    └─────────┬────────┘ │
 │                                              ▼          │
 │                                          TTS Engine     │
 │                                              │          │
 │                                          AudioPlayer    │
 │                                              │          │
 │                                           Speaker       │
 └─────────────────────────────────────────────────────────┘
```

---

## 2. Core Subsystems

### A. Voice Engine (`backend/src/voice/`)
- **AudioCapture**: Ring buffer chunking 16 kHz 16-bit mono PCM into 20ms frames.
- **VAD**: Energy & zero-crossing voice activity detection with adaptive noise floor.
- **STTEngine**: Multilingual offline speech recognition supporting 11 languages (Hindi, Tamil, Telugu, Marathi, Bengali, Kannada, Gujarati, Malayalam, Punjabi, Urdu, English).
- **SentenceAssembler**: Assembles words into sentences with pause detection (continuous mode) and PTT finalization (push-to-talk mode).
- **TTSEngine**: Offline acoustic speech synthesis generating 16 kHz PCM and standard WAV audio.
- **PriorityQueue**: Strict priority queue where `ALERT` messages preempt normal playback.

### B. Message Protocol & Reliability (`backend/src/protocol/` & `backend/src/reliability/`)
- **VoiceMessage Protocol**: Version, messageId, senderId, language, type (`NORMAL` | `ALERT`), sequence, timestamp, and text.
- **FrameCodec**: 4-byte big-endian framing (`[Length: 4 bytes] + [Payload: N bytes]`) preventing stream boundary corruption.
- **Handshake Protocol**: `HELLO` ➔ `HELLO_ACK` ➔ `DEVICE_INFO` ➔ `READY`.
- **MessageStore**: Local persistence of finalized messages before transmission.
- **ReliabilityLayer**: Sequence tracking, ACK acknowledgement, exponential retry backoff, deduplication filter.

### C. Connectivity Supervisor & Transports (`backend/src/transports/` & `backend/src/supervisor/`)
- **Transport Priority**: **Wi-Fi Direct** ➔ **Wi-Fi LAN** ➔ **Bluetooth Classic RFCOMM**.
- **Heartbeat & Failover**: 1-second ping/pong monitoring; automatic failure detection and transparent transport switching with pending message flush.
- **NSD / mDNS**: Discovery advertising `_voicebridge._tcp` on port 8988.

---

## 3. Project Structure

```text
├── backend/                   # Voice Bridge standalone Node.js/TypeScript backend
│   ├── src/
│   │   ├── discovery/         # Network Service Discovery (_voicebridge._tcp)
│   │   ├── protocol/          # 4-byte FrameCodec & Handshake protocol
│   │   ├── reliability/       # MessageStore, ACK tracking & retry logic
│   │   ├── supervisor/        # ConnectivitySupervisor & automatic fallback
│   │   ├── transports/        # VoiceTransport (Wi-Fi LAN, Wi-Fi Direct, Bluetooth)
│   │   ├── voice/             # AudioCapture, VAD, STT, TTSEngine, PriorityQueue
│   │   ├── server.ts          # Express REST & WebSocket streaming server
│   │   ├── index.ts           # Service entrypoint
│   │   └── types.ts           # Core protocol types
│   └── tests/                 # Unit & integration test suite
├── apps/
│   └── web/                   # Web presentation dashboard (React 19 + Vite)
├── package.json               # Root workspace scripts
└── voice-bridge-implementation-plan.md  # Specification document
```

---

## 4. Operating Commands

### Run Unit Tests
```powershell
npm test
```

### Start Development Server
```powershell
# Terminal 1 (Voice Bridge Backend):
npm run dev:backend

# Terminal 2 (Web Dashboard):
npm run dev:web
```
Or start backend directly:
```powershell
npm run dev
```

### Build for Production
```powershell
npm run build
```
