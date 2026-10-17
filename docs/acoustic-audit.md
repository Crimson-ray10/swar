# Current architecture — inspected before acoustic implementation

Frontend: Android Java 17, minSdk 31/targetSdk 35/compileSdk 36; one Activity hosts the bundled `mobile/app/src/main/assets/index.html`. Its tabs, radar connection requests, PTT, conversation toggle, messages, dashboard, language and model controls communicate through a JavaScript bridge. No native frontend framework migration is needed. The React/Vite website is a separate application. Existing user modifications to website HTML/CSS are outside this task.

Audio: The system SpeechRecognizer owns capture; TextToSpeech owns playback. No AudioRecord/AudioTrack modem or shared PCM pipeline exists. Capture/playback are foreground-only; no background worker or foreground service is declared.

STT: `createOnDeviceSpeechRecognizer`, platform language support/download queries, Hindi/English/experimental Hinglish, partial and final transcripts. No bundled AI4Bharat inference. Backend STTEngine consumes client hints and reports synthetic confidence; it is not a local ASR model.

TTS: Android system voices, despite an incorrect AI4Bharat capability label. A missing local voice currently falls through to `setLanguage`, which can select an unsuitable voice. Backend TTS attempts an HTTP service and falls back to harmonics; the Python Indic-TTS server also generates harmonics, not model speech. Neither is suitable as an acoustic-mode dependency.

Networking/current hotspot dependency: LocalTransport immediately starts TCP port 8988, Android NSD and multicast discovery; manual IPv4 connection and user approval; heartbeat/reconnect; no backend needed for Android LAN phone-to-phone. Both phones must share a network. Website uses Express HTTP/WebSocket; Node Wi-Fi Direct/Bluetooth classes are socket prototypes, not handset radios.

Protocol/semantic layer: Existing mobile ITP/1 is lossless UTF-8 with optional DEFLATE, UUID session, sequence and language, CRC32 followed by extended Hamming(8,4) SECDED. Preserve this byte format. It is not trained semantic tokenization. FrameIO adds bounded TCP framing. ACK and bounded dedup exist, but mobile application retransmission does not. Website logical messages differ from ITP and need an explicit adapter.

VAD/conversation: Android recognizer callbacks and silence hints; PTT release finalizes recognition. Continuous conversation restarts capture after results; TTS cancels capture and delays restart. No modem-tone exclusion exists. Backend has an energy VAD independent of Android.

Models/dependencies: `AI4Bharat-IndicConformerASR` contains README/LICENSE, not weights or an Android runtime. `bundle/models`, packages/services/tests placeholder directories contain no installed speech weights. Mobile depends on Android APIs, not ML libraries. Current mobile codec supports hi/en/hinglish only; website lists additional Indic languages, which is not proof of handset support.

Permissions/privacy: INTERNET, network/Wi-Fi/multicast state, RECORD_AUDIO; speech/TTS service queries. WebView blocks external resources. Transcripts are held in UI memory; localStorage holds preferences. No permanent raw speech storage. MainActivity pauses speech in background but existing LAN transport continues until destroy.

Tests: pure-Java ITP CRC/FEC/Unicode tests, TCP loopback tests, debug handset instrumentation designed for TCP, Node protocol/voice tests. No acoustic tests, calibrated recordings or ground-truth multilingual corpus.

Reuse: mobile frontend and bridge event shape; ITP/1, lossless compression, CRC/Hamming, local recognizer, offline system voices, legacy LAN code for explicitly selected development use, website transport as a separate network mode.

Replace/add: default transport construction; audio modem and bounded streaming receiver; versioned acoustic control envelope; acoustic discovery/session negotiation and retries; shared microphone gating; truthful capability/model readiness and metrics; synthetic channel tests and research export.

Risks: phone DSP and routing, clock drift/reverberation, collision/deaf intervals in half-duplex, recognition-service support for injected PCM (Android 13+), missing offline models, low acoustic goodput, audible data bursts, unauthenticated audible packets. CRC is not encryption/authentication. Hardware range/throughput and model accuracy must be measured; no result is inferred from a successful build.
