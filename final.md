# Build Spec: Offline Multilingual Voice Walkie-Talkie (Android / Kotlin)

> Requirements precedence: Read this specification together with `answers.txt`, especially its final section, "10. RESOLVED ENGINEERING DECISIONS". That section resolves conflicts in both documents and takes precedence where they differ. Performance figures are acceptance targets, not evidence of achieved performance. This specification is not a claim that model exports, field tests, or native-speaker validation have already been completed.

You are building a fully offline, peer-to-peer Android app that lets people talk to each other in Indian regional languages with low latency and low word-error rate, on low-end hardware, with no internet/cellular dependency and no closed-source or commercial SDKs.

**Read this entire spec before writing code. Implement the complete specification in ONE GO. Do NOT build it phase-by-phase, do NOT stop after completing individual sections, and do NOT wait for approval or acceptance testing between stages. You must implement all required transport, protocol, audio/ML, Conversation Mode, Walkie-Talkie Mode, translation, encryption, reconnection, and validation infrastructure in a single implementation pass.**

The finished implementation should contain the complete architecture described below, with all modules wired together and both operating modes usable.

---

# 1. Hard constraints (do not violate these)

No Google Play Services / Nearby Connections API. It is closed-source, requires GMS (unavailable on many low-end/regional devices and custom ROMs), and has known reliability and security issues. Build the transport layer directly on AOSP APIs only: BluetoothAdapter / BLE GATT, WifiP2pManager (Wi-Fi Direct), WifiAwareManager (Wi-Fi Aware, opportunistic only).

No cloud/hosted STT, TTS, or translation APIs. All inference happens on-device via ONNX Runtime (Apache-2.0) / sherpa-onnx (Apache-2.0). No network calls of any kind other than device-to-device local transport.

Min SDK: API 29. Target SDK should stay compatible with API 29 behavior; if you later raise targetSdkVersion past 32, add the NEARBY_WIFI_DEVICES runtime permission alongside fine location for Wi-Fi P2P discovery.

Models are fixed, do not substitute:

STT: AI4Bharat IndicConformer (NeMo FastConformer-family), run via sherpa-onnx after a NeMo→streaming-ONNX export (int8 quantized).

TTS: AI4Bharat Indic-TTS — FastPitch (acoustic model) + HiFi-GAN V1 (vocoder), run as a two-stage pipeline directly on ONNX Runtime Mobile (onnxruntime-android), not through sherpa-onnx's TTS wrapper, since that wrapper expects single-graph model types (VITS/Matcha/Kokoro) and FastPitch+HiFi-GAN is a separate acoustic-model + vocoder pair.

Translation (beta, English↔Hindi only): AI4Bharat IndicTrans2, exported with an explicit KV-cache (past-key-values as decoder inputs/outputs) so decode cost is linear, not quadratic, in output length. Do not ship the no-KV-cache version.

## Two operating modes

### Walkie-Talkie Mode

1-to-many broadcast, push-to-talk, floor-controlled.

Only one sender is allowed to transmit at a time.

### Conversation Mode

1-to-1 **true full-duplex conversation**.

**Both connected devices MUST be able to speak and listen at the same time, simultaneously.**

There must be:

* Independent microphone capture on both devices.
* Independent streaming STT pipelines on both devices.
* Independent outgoing network streams on both devices.
* Independent incoming network/TTS/playback pipelines on both devices.
* No floor control in Conversation Mode.
* No suppression of local microphone input just because the remote peer is speaking.
* Device A speaking must NOT block Device B from speaking.
* Device B speaking must NOT block Device A from speaking.
* Incoming and outgoing audio/text pipelines must operate concurrently.
* Each direction must maintain its own stream ID, sequence numbers, buffers, and state.
* Concurrent speech from both peers must remain intelligible and must not cause the two streams to overwrite, block, or mix incorrectly.

Where supported by Android hardware, use AOSP `AcousticEchoCanceler` and `NoiseSuppressor` on the microphone input so the device does not retranscribe its own TTS playback as user speech. This must not disable simultaneous speaking.

**Conversation Mode is not half-duplex. It must behave like a real two-way conversation where either person can interrupt or speak while the other person is speaking.**

---

# 2. Architecture overview

Mic → AEC/Noise Suppression → VAD gate → Streaming STT (sherpa-onnx) → Stability buffer → [Translation, if beta enabled and langs = en/hi] → Payload encoder → Transport Manager → (network) → Transport Manager (receiver) → Payload decoder → Jitter buffer → TTS (FastPitch → HiFi-GAN, onnxruntime) → AudioTrack playback

For Conversation Mode, the above pipeline must exist **independently in both directions at the same time**:

Device A:
Mic A → STT A → Network A→B
Network B→A → TTS A → Speaker A

Device B:
Mic B → STT B → Network B→A
Network A→B → TTS B → Speaker B

Everything downstream of "Payload encoder" and upstream of "Payload decoder" is the network stack; everything else is the audio/ML pipeline.

Build them as independent modules with a narrow interface (ByteArray messages in, ByteArray messages out) so they can be developed and tested separately.

For Conversation Mode, ensure send and receive pipelines are independently scheduled and never serialized behind each other. A long TTS operation or outgoing STT operation must not prevent incoming data from being processed.

---

# 3. Transport layer

## 3.1 Roles of each radio

Radio | Role | Why

BLE (GATT) | Always-on control plane: discovery, presence/heartbeat, handshake, capability exchange, and a low-bandwidth data fallback | Cheapest on battery, works even when Wi-Fi radios are busy/unsupported, survives Wi-Fi Direct group churn

Wi-Fi Direct (P2P) | Primary data plane once two peers are known via BLE | Highest throughput/lowest latency, universal on API 29+

Wi-Fi Aware (NAN) | Opportunistic upgrade path, only where hardware supports it | Faster pairwise data paths without Group Owner election, but unreliable hardware coverage on budget devices — treat as a bonus, never a dependency

## 3.2 Connection lifecycle

Discovery (BLE only, always running while the app is foreground/foreground-service): advertise a GATT service with a fixed UUID for this app. Scan for the same UUID. On finding a peer, exchange a small handshake payload over a GATT characteristic: device ID, display name, supported languages, and (if available) Wi-Fi P2P device address and Wi-Fi Aware availability flag.

Negotiation: use the BLE-exchanged address to call WifiP2pManager.connect() directly — do not rely on Wi-Fi Direct's own service discovery broadcast, which is slower and less reliable. Bias Group Owner election with WifiP2pConfig.groupOwnerIntent (0–15) toward the more capable/plugged-in device.

If Wi-Fi Aware is available on both peers (check PackageManager.FEATURE_WIFI_AWARE and that the adapter is attached), prefer establishing a NAN data path for 1-to-1 Conversation Mode instead of Wi-Fi Direct, since it avoids GO election entirely. Still fall back to Wi-Fi Direct if NAN setup fails or isn't supported by both devices.

Data transfer: once a Wi-Fi Direct group forms (or NAN data path opens), open a plain TCP or UDP socket over the resulting IP link and stream payloads over it. This carries the bulk of both modes' traffic.

Fallback: if Wi-Fi Direct connect() times out or fails, do not retry indefinitely — fall back to sending payloads over the still-open BLE GATT channel. Payloads are small text strings, so BLE throughput is an acceptable degraded mode, never the default.

Group Owner loss / reconnection: BLE's presence heartbeat is the thing that detects a peer/GO dropping out and triggers automatic re-negotiation of the data plane. The "who is nearby" state must live entirely in the BLE layer so it survives Wi-Fi Direct group teardown and reformation.

Walkie-Talkie broadcast: the Wi-Fi Direct Group Owner acts as the hub; broadcast sender transmits once to the GO, which forwards to all connected group members. Do not attempt a full mesh — GO-as-hub is simpler and sufficient at small group sizes.

Conversation Mode: the two peers require a **simultaneous bidirectional data path**. Both devices must be able to transmit and receive independently over the same connection without one direction blocking the other.

## 3.3 Required permissions (manifest)

BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT (API 31+)

BLUETOOTH, BLUETOOTH_ADMIN (legacy, API < 31)

ACCESS_FINE_LOCATION (required for BLE + Wi-Fi P2P scanning pre-33)

ACCESS_WIFI_STATE, CHANGE_WIFI_STATE

NEARBY_WIFI_DEVICES (only if targetSdkVersion raised past 32 later)

Request all runtime permissions before starting discovery; if any are denied, degrade gracefully to whichever radios remain usable and tell the user which mode is unavailable.

## 3.4 Transport module structure to implement

TransportManager — public API:
startDiscovery()
stopDiscovery()
sendToPeer(peerId, bytes)
broadcast(bytes)
onMessageReceived callback
onPeerListChanged callback
connectionState: StateFlow<ConnectionState>.

BleController — advertising, scanning, GATT server/client, handshake exchange, presence heartbeat, degraded-mode byte transfer.

WifiDirectController — WifiP2pManager wrapper: peer connect, GO intent biasing, socket setup once group forms, teardown/reconnect handling.

WifiAwareController — optional NAN data-path wrapper, feature-detected and used only when available; must fail silently and fall back to WifiDirectController otherwise.

TransportManager internally decides, per outgoing message, which controller to use based on current ConnectionState — this decision logic should be unit-testable independent of real radios (inject fake controllers in tests).

For Conversation Mode, transport handling must support simultaneous A→B and B→A traffic without serializing or dropping one direction because the other direction is active.

---

# 4. Payload format and protocol

Use a compact binary format (not verbose JSON) since every extra byte matters on the BLE fallback path.

Suggested schema per message:

[1B] message type
(0=handshake, 1=stt_partial, 2=stt_final, 3=ack, 4=floor_request, 5=floor_grant, 6=floor_release)

[16B] sender device id

[4B] stream id (per-utterance)

[4B] sequence number

[1B] language code

[1B] flags (isFinal, isTranslated, ...)

[2B] payload length

[N B] payload bytes (UTF-8 text, or ciphertext if encryption enabled)

Reliability: only stt_final messages require ACK + retransmit (bounded retries, then give up — a missed final segment is worse than a missed partial). Partials are best-effort/unacknowledged; dropping one is fine since a final always follows.

Floor control (Walkie-Talkie mode only): before transmitting, sender broadcasts floor_request; if no other sender currently holds the floor, GO/hub responds floor_grant; sender broadcasts floor_release when done or on silence timeout. Other clients show a "channel busy" indicator while floor is held by someone else, and must suppress local mic capture while another peer holds the floor.

**Conversation Mode MUST NOT use floor control.** Both peers may transmit simultaneously and independently.

Encryption: since you are not using Nearby Connections, you lose its built-in encryption — implement your own. Use a simple authenticated handshake (X25519 key exchange during the BLE handshake step) followed by AEAD encryption (e.g. ChaCha20-Poly1305, available via a small open-source library such as libsodium bindings or Tink) for every payload body. Do not ship this feature unencrypted "for now" — build it as part of the complete implementation.

---

# 5. Audio → text pipeline

VAD gating: run a small Silero VAD ONNX model (~1–2 MB) continuously on mic input. Only feed audio into the STT engine when speech is detected; this is what keeps idle CPU/battery usage low. Do not run the STT encoder on silence.

Before VAD/STT, use Android AEC where available so remote/local TTS playback does not become microphone input.

Streaming STT: feed VAD-gated 16 kHz mono PCM into the sherpa-onnx OnlineRecognizer built from the IndicConformer streaming export. Do not use modelConfig.vits.* fields for this — that namespace is for TTS. Configure the transducer/CTC sub-config fields sherpa-onnx's NeMo model type actually expects; check the model folder's own README for the exact config shape before wiring it up, since field names vary by export.

Partial-result stability buffer — do not skip this. Streaming ASR hypotheses revise themselves as more audio arrives.

Track each token's hypothesis across N consecutive decode steps (e.g. N=3).

Only mark a token "stable" and eligible for transmission once it hasn't changed for N steps, or once a VAD-detected micro-pause confirms an utterance boundary.

This trades roughly 100–200ms of latency for eliminating audible "correction" glitches on the receiving end, which matters more for your legibility/flow metric than shaving those milliseconds.

Finalization: on end-of-utterance (VAD silence timeout or explicit push-to-talk release), flush the recognizer, emit a stt_final message, and reset the stream for the next utterance.

For Conversation Mode, each side's STT recognizer must run independently and concurrently. Speaking on one device must not prevent the other device from continuously recognizing speech.

---

# 6. Translation (beta feature, English ↔ Hindi only)

Gate this entirely behind a feature flag / language-pair check: only run translation when the conversation's declared source and target languages are exactly en and hi (either direction). All other language pairs skip translation and speak the STT output directly in the receiver's TTS.

Use IndicTrans2 exported with KV cache (decoder past-key-values passed as explicit ONNX inputs/outputs across decode steps) so latency scales linearly with output length, not quadratically. Do not reuse a no-cache export even if it's easier to find.

Run AI4Bharat's IndicNLP normalization on the source text before translation and on the translated text before TTS (numerals, punctuation, script-specific quirks, common English loanwords). Skipping this measurably hurts both translation quality and TTS legibility.

Because this is beta and adds a full extra model in the pipeline, keep it as a separate, independently disable-able stage — the app must work correctly with translation entirely turned off (which is the default, non-beta path).

For Conversation Mode, translation must work independently in both directions. Device A→B and Device B→A may each have different source/target languages within the supported language configuration.

---

# 7. Text → audio pipeline (receiver side)

Jitter buffer: incoming stt_final (and, if you choose to speak partials live, stable partial) messages go into a small per-stream queue before TTS, so network timing variance doesn't cause playback stutter. Keep this buffer small (target: low hundreds of ms) — the point is smoothing, not adding perceptible delay.

**Conversation Mode must maintain separate jitter/TTS queues for each direction/stream.** Incoming speech from one peer must not block the processing of another active stream.

TTS pipeline (custom, not sherpa-onnx's TTS API):

Load two ONNX Runtime Mobile sessions: FastPitch (text/phonemes → mel spectrogram) and HiFi-GAN (mel spectrogram → waveform).

Run them sequentially per incoming text chunk: melSpec = fastpitchSession.run(tokens), then waveform = hifiganSession.run(melSpec).

Quantize both to int8 for CPU speed, but see the validation requirement below — do not assume int8 quality is acceptable without measuring it.

Playback: stream the resulting PCM into an AudioTrack in streaming mode, same as described in the original architecture — one persistent AudioTrack per active conversation, fed incrementally as TTS chunks complete.

Floor-aware playback (Walkie-Talkie mode): if two senders somehow both have audio queued, queue them sequentially rather than mixing — mixed overlapping TTS speech is unintelligible.

**Conversation Mode must not apply Walkie-Talkie floor restrictions. Remote speech should be processed whenever it arrives, even while local speech is being captured or transmitted.**

---

# 8. Validation you must build in, not add later

These are not optional polish — they directly determine whether the app meets its accuracy and quality targets.

Quantization delta check: before shipping any int8 model (STT or TTS), run a benchmark comparing int8 vs fp32 output — WER delta for STT, a quick human intelligibility pass for TTS — on your own test utterances, not just the model author's canned samples. Build this as a repeatable script/harness, not a one-off manual check.

Per-language TTS quality check: for each language you ship, have native speakers rate naturalness and do a blind transcription-back intelligibility check. A single sample .wav per language is not sufficient evidence of quality.

Odia (and any other language) coverage check: if a language is missing from whatever STT export you start from, first check whether it's missing from the underlying AI4Bharat IndicConformer checkpoint itself versus just missing from a particular third-party ONNX conversion. If it's a conversion gap, the fix is running the same NeMo→ONNX export yourself for that language, not dropping it from scope.

End-to-end latency budget, measured, not assumed: instrument timestamps at mic-input, STT-stable-partial, message-sent, message-received, TTS-audio-start. Target budget: STT stability ~250–300ms, transport hop <50ms on Wi-Fi Direct, TTS start <300ms (RTF < 0.3), total mouth-to-ear 600–800ms. Log these per session so regressions are visible.

Idle CPU/battery check: confirm the VAD gate actually keeps the STT engine idle (near-zero CPU) when no one is speaking, on your actual reference low-end device, not a dev machine.

Field connection-reliability matrix: test all three transport tiers (Wi-Fi Direct primary, Wi-Fi Aware where available, BLE fallback) across at least: two devices in the same room, two devices at max reasonable range, one device with Wi-Fi Aware hardware and one without, and a mid-conversation Group Owner disconnect/reconnect.

**Conversation Mode concurrency validation: test both peers speaking at the same time. Verify that simultaneous speech does not block either microphone pipeline, transport direction, STT pipeline, or TTS playback.**

---

# 9. SINGLE-PASS IMPLEMENTATION REQUIREMENT

**Do not implement the project using the old Phase 0 → Phase 8 workflow.**

Instead:

1. Read and understand the entire specification.
2. Create all required modules, classes, interfaces, model integrations, permissions, transport controllers, protocol handling, encryption, audio pipeline, Conversation Mode, Walkie-Talkie Mode, translation, reconnection handling, and validation infrastructure.
3. Wire the complete system together.
4. Resolve compile errors, dependency issues, lifecycle problems, concurrency problems, and integration issues within the same implementation pass.
5. Run the relevant tests/build checks after implementation.
6. Fix discovered issues immediately rather than stopping and waiting for a later phase.
7. Finish with the application in a coherent, runnable state.

**Do not respond with "Phase 0 complete", "Phase 1 next", or ask for permission to continue.**

**Do not intentionally leave later systems as TODOs because an earlier phase has not been validated.**

**Do not create placeholder implementations for core functionality when the actual implementation is specified above.**

You may internally implement components in whatever technical order is necessary, but from the user's perspective this must be delivered as **one complete implementation pass**.

---

# 10. Explicit mistakes to avoid

Do not add `com.google.android.gms:play-services-nearby` or any Nearby Connections code — it is closed-source and GMS-dependent, which disqualifies it under this project's constraints.

Do not configure the sherpa-onnx OnlineRecognizer using TTS (vits) config fields.

Do not send every raw STT partial straight to TTS without the stability buffer — this produces audible self-correcting speech on the receiver.

Do not assume int8 quantized models match fp32 quality without measuring it yourself.

Do not build translation with a no-KV-cache decoder export.

Do not skip encryption for the prototype — it must be part of the implementation.

Do not let a single Wi-Fi Direct Group Owner disconnect kill the whole session — BLE presence tracking must survive it and trigger reconnection.

**Do not implement Conversation Mode as push-to-talk, turn-taking, or half-duplex. It must remain true simultaneous full-duplex.**

**Do not use Walkie-Talkie floor-control rules inside Conversation Mode.**

**Do not serialize the two Conversation Mode directions into a single shared speech pipeline.**

**Do not allow outgoing speech from one peer to block incoming speech from the other peer.**

# Final implementation goal

The completed app must provide:

* Fully offline peer-to-peer communication.
* BLE discovery and presence.
* Wi-Fi Direct primary transport.
* Wi-Fi Aware opportunistic transport.
* BLE degraded fallback.
* Encrypted payloads.
* On-device multilingual STT.
* Stable streaming partials.
* On-device TTS.
* Optional English↔Hindi translation.
* Automatic reconnection.
* 1-to-many Walkie-Talkie Mode with floor control.
* **1-to-1 Conversation Mode with true simultaneous full-duplex speech.**
* Concurrent speech processing in both directions.
* Measured latency and reliability instrumentation.
* No dependency on Google Play Services or cloud APIs.

**Implement all of the above in one complete pass. Do not stop at intermediate phases.**
