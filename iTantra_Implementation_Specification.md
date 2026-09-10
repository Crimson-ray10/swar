# iTantra — Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for Low-Bitrate Links

## Status, scope, and non-negotiable truth boundaries

This is the implementation source of truth for iTantra. It assumes a **local-first Windows workstation**, a simulated radio/channel rather than physical RF hardware, and a demonstrator whose primary objective is to preserve the *message* of speech under a severe link budget. It is not a plan to reproduce a speaker's waveform at 0.5 kbps.

The first build must be a working **transcript-first semantic radio**:

~~~text
speech -> ASR -> normalized text/subword packet stream -> FEC + simulated channel
       -> exact text when packets survive -> TTS -> new intelligible speech
~~~

It is semantic because the payload is a linguistic representation rather than acoustic samples. The MVP must not be described as a learned neural physical-layer modem. A learned semantic VQ encoder/decoder is an advanced, separately measured profile. A continuous DeepJSCC-style modem is research-only until it has a modem clock, DAC/ADC, and experimental evidence.

### Decisions frozen for the first implementation

| Decision | Required implementation choice | Reason |
|---|---|---|
| Core objective | Preserve transcript/message; synthesize a fresh receiver voice | This is realistic at 0.5–2 kbps without training a large speech codec. |
| MVP wire representation | Exact normalized multilingual SentencePiece token IDs, packed as fixed-width values, packetized, CRC-protected, and FEC protected | Deterministic, testable, inspectable, and recoverable after successful delivery. |
| Default operating point | 2 kbps gross radio budget; 1 kbps and 0.5 kbps are stress demonstrations | At 0.5 kbps headers, FEC, and queueing dominate. |
| Indian ASR | ai4bharat/indic-conformer-600m-multilingual, selected-language CTC by default | Official 22-language, MIT-licensed model with local inference interface.[^1] |
| English ASR / auto-LID fallback | faster-whisper with multilingual small in INT8 CPU or INT8-float16 GPU mode | English is not one of IndicConformer's advertised 22 languages; faster-whisper is a local CTranslate2 implementation.[^7] |
| Indic TTS | ai4bharat/IndicF5 for Bengali, Gujarati, Hindi, Kannada, Malayalam, Marathi, Punjabi, Tamil, and Telugu | Its official card advertises all nine requested non-English target languages and local prompt-based inference.[^2] |
| English TTS | rhasspy/piper-voices en_US-amy-medium | A compact local ONNX fallback. It is generic English, **not** an asserted Indian-English accent.[^16] |
| One-model experimental TTS alternative | ai4bharat/indic-parler-tts | It officially lists English and 20 Indic languages, but labels Punjabi unofficial; do not claim it as contractual Punjabi support.[^3] |
| No paid API | No cloud ASR, TTS, LLM, database, or radio service in the core path | Reproducible, privacy-preserving demo. |
| Persistence | No raw audio or transcript persistence by default; opt-in local experiment reports only | A database is not necessary for a single-machine demo and voice data is sensitive. |

**Model-access caveat.** The official AI4Bharat Hugging Face cards currently ask a person to accept access/contact conditions. A setup script may automate download only *after* that human acceptance and Hugging Face login. It must never bypass a gate. Once downloaded, local inference can work offline.

### What the system does and does not claim

* A successfully decoded text_v1 message is byte-for-byte the receiver's normalized transcript. It may differ from the speaker's words because ASR can be wrong; that is measured separately.
* Receiver speech is TTS. It does not preserve the original waveform, identity, emotion, room acoustics, or exact timing. Label it **synthetic receiver voice**.
* At an unrecoverable FEC block, the receiver exposes an erasure and uncertainty rather than silently inventing words. An advanced semantic decoder may offer a separately marked repair hypothesis.
* The channel is a reproducible simulation, not proof of performance over a real radio. Record its seed and every impairment.
* No benchmark number in this document is a measured iTantra result. Values labelled “target” are engineering budgets to test, not promised performance.

## 1. Practical engineering concept

### 1.1 Problem iTantra solves

Raw mono 16 kHz, 16-bit PCM needs 16,000 samples/s × 16 bits/sample = 256 kbps, before framing or radio protection. Conventional voice codecs retain enough acoustic information to recreate speaker audio. That is right when voice identity, tone, music, background sound, and natural turn-taking matter. It is wasteful when a receiver only needs the spoken message and the link has hundreds or a few thousand useful bits per second.

iTantra changes the target from **waveform fidelity** to **linguistic/semantic usability**. The transmitter recognizes speech locally, sends a compact protected language representation, and the receiver locally speaks recovered text. This follows the research premise of speech-recognition semantic communication—send task-relevant text rather than every source bit—but starts with a transparent digital implementation rather than an unvalidated end-to-end neural link.[^12]

The term **neural transceiver** has three precise meanings here:

1. text_v1 MVP: neural ASR and TTS surround a conventional digital semantic source/channel transceiver. This is a neural-assisted semantic radio system, not a learned modem.
2. semantic_vq_v1 advanced: a trainable neural semantic encoder makes vector-quantized indices and a neural decoder reconstructs semantic text. The emitted bits remain conventional protected packets.
3. deepjscc_v1 research: an encoder maps semantic features to continuous complex channel symbols and a receiver maps noisy symbols to text. This is a learned source-channel transceiver, but its natural unit is a channel use, not a byte or a kbps claim.

### 1.2 The six representations—never conflate them

| Name | Canonical object | Example / shape | What survives |
|---|---|---|---|
| Original speech | Captured waveform | PCM16LE mono 16 kHz; model tensor float32[1, T] | Voice, content, prosody, noise, environment. |
| STT text | ASR hypothesis | Unicode UTF-8 string plus segment/word confidence | Linguistic hypothesis; can contain ASR errors. |
| Semantic representation | Structured normalized utterance | SemanticFrame(language, normalized_text, tokens, critical_spans, timings, confidence) | Message text and selected safety-critical literals. |
| Encoded/transmitted representation | Source-coded payload fragments | Packed token IDs or VQ IDs in ITP/1 packets | Only header, FEC, and payload bits cross the channel. |
| Reconstructed text/speech | Receiver decoder output | Exact normalized text in text_v1; hypothesis in semantic_vq_v1 | Recovered message plus missing/uncertain state. |
| TTS output | Fresh synthesized waveform | PCM/WAV, typically 24 kHz from IndicF5 | A new voice rendering, not recovered audio. |

### 1.3 Why this differs from audio-file compression

Audio compression such as Opus, AMR, EnCodec, or DAC sends a representation intended to reconstruct audio. EnCodec's published causal 24 kHz model has source modes from 1.5 through 24 kbps; that is impressive but still an audio-codec path before headers and FEC.[^9] DAC publishes 8 and 16 kbps modes and is a useful neural audio baseline, not iTantra's default solution.[^10]

In contrast, text_v1 sends no acoustic-codec indices. A receiver that recovers “मैं घर पहुँच गया हूँ” generates a new TTS waveform. Its audio can sound unlike the original and still succeed semantically. Therefore compare wire rate and metrics appropriate to the goal—WER/CER, critical-entity accuracy, human intelligibility—not merely audio PSNR.

### 1.4 Behavior under impairment

**Transmitter:** capture and clean speech; wait for VAD end-of-utterance; transcribe; normalize; create semantic frame; tokenize and pack IDs; protect with CRC/FEC; apply configured channel simulation; emit trace events.

**Receiver:** validate CRC; reorder within bounded jitter; recover erasures with FEC; reject unrecoverable frames; source-decode; restore normalized text or explicitly uncertain semantic hypothesis; synthesize; emit actual transport/timing metrics.

| Impairment | Required text_v1 behavior | Never do |
|---|---|---|
| Packet loss | Recover up to FEC capacity; otherwise mark frame missing | Guess hidden text and report exact recovery. |
| Bit error | CRC turns corrupt packet into erasure; FEC may recover it | Feed corrupt packed IDs to source decoder. |
| AWGN/fading | Convert to symbol/bit errors in BPSK modes, then use same parser | Make the SNR slider cosmetic. |
| Low rate | Queue payload, expose back-pressure and delay | Claim a fixed real-time latency if a frame cannot fit. |
| Latency/jitter/reordering | Simulate scheduled delivery timestamps and jitter buffer | Re-sort final traces silently and hide reordering. |
| ASR uncertainty | Show original hypothesis and confidence | Present TTS output as speaker ground truth. |

## 2. Final end-to-end architecture

### 2.1 Recommended architecture

~~~text
Browser microphone / uploaded WAV
  -> PCM conversion (mono, 16 kHz) -> VAD -> utterance assembler
  -> selected-language ASR (+ optional LID only in auto mode)
  -> Unicode/text normalization + critical-span extraction
  -> semantic frame
  -> [MVP: shared SentencePiece -> packed token IDs]
     [Advanced: neural VQ semantic encoder -> packed VQ IDs + literal anchors]
  -> ITP/1 packetizer -> CRC -> FEC -> digital serializer/modulator
  -> reproducible low-rate channel simulator
  -> demodulator -> CRC -> reorder/jitter buffer -> FEC recovery
  -> source decoder / neural semantic decoder -> reconstructed text
  -> language-aware TTS provider -> PCM/WAV -> browser playback
~~~

Every component receives and returns typed Pydantic/dataclass objects. Never pass loose JSON dictionaries, arbitrary tensors, or files between core stages.

| Component | Purpose / input / output | Recommended implementation and format | Latency target / compute | Failure handling and alternative |
|---|---|---|---|---|
| Capture | Microphone or upload to AudioChunk | Web Audio AudioWorklet; PCM S16LE 16 kHz mono | 20–320 ms chunks; browser CPU | Permission/device error is shown; WAV upload fallback. |
| Preprocess | Validate, downmix, resample, trim DC/clip | torchaudio/soundfile; float32[1,T], peak ≤ 0.98 | Target <50 ms per 3 s CPU | Reject malformed/oversize media. |
| VAD | Find speech boundaries | Silero VAD at 16 kHz with hysteresis/max-utterance guard | Target 10–30 ms per second CPU | Energy VAD fallback; press-to-talk can finalize. |
| LID/router | Choose language provider | User choice authoritative; auto uses faster-whisper score + threshold | Once per 1–3 s prefix | If score <0.75, ask user to select; do not silently route. |
| ASR | Audio to text | IndicConformer CTC; faster-whisper English | GPU target <1× RTF; CPU functional but no unmeasured real-time claim | Preserve empty/low-confidence result as an error state. |
| Normalizer | Wire-stable text | NFC Unicode; language punctuation/number policy | Target <10 ms CPU | UTF-8 literal escape for unsupported characters; no wire transliteration. |
| Semantic frame | Bind text/language/timing/literals | Versioned SemanticFrame schema | Target <2 ms CPU | Reject invalid version/language before packetization. |
| Source/neural encoder | Frame to bits | MVP fixed-width SentencePiece IDs; advanced SemanticVQTransmitter | MVP <20 ms CPU; VQ target <50 ms GPU | Fall back to text_v1; never fabricate VQ values. |
| Packet/FEC | Loss-safe finite blocks | ITP/1 binary; CRC-16/CCITT; convolutional code + optional parity | Target <5 ms CPU/block | Invalid CRC becomes erasure; recovery state logged. |
| Channel | Apply impairments | Deterministic NumPy/PyTorch simulator, seed per run | Faster than real time unless demo pacing enabled | Validate config/ranges; no hidden defaults. |
| Receiver | Recover text payload | ITP parser + source decoder; VQ decoder if requested | MVP <20 ms CPU/frame | Stop at frame boundary on decode failure. |
| TTS | Speak recovered text | IndicF5 by language + consented prompt; Piper English | Target first audio <1.5 s GPU, <5 s CPU; always measure | Explicit tts_unavailable; recovered text remains usable. |
| Telemetry | Actual rate/error/timing | RunReport JSON, JSONL trace; opt-in SQLite experiment DB | Async write | Render null, not placeholders, until calculated. |

### 2.2 Mandatory data contracts

~~~python
@dataclass(frozen=True)
class AudioChunk:
    session_id: UUID
    seq: int
    capture_time_ms: int
    sample_rate_hz: Literal[16000]
    channels: Literal[1]
    pcm_s16le: bytes

@dataclass(frozen=True)
class SemanticFrame:
    protocol_version: int
    profile: Literal["text_v1", "semantic_vq_v1"]
    utterance_id: int              # uint16, unique inside session
    language: str                  # application language code: hi, en, bn, ta...
    normalizer_id: str
    token_model_id: str            # pinned SHA/revision identifier
    start_ms: int
    duration_ms: int
    normalized_text: str           # not transmitted verbatim in VQ mode
    token_ids: list[int]
    critical_spans: list[CriticalSpan]
    asr_confidence: float | None

@dataclass(frozen=True)
class ReconstructedFrame:
    utterance_id: int
    status: Literal["exact", "semantic_hypothesis", "partial", "unrecoverable"]
    text: str | None
    missing_packet_sequences: list[int]
    confidence: float | None
    warnings: list[str]
~~~

normalized_text stays only in memory for a normal run. The packet payload never sends Python pickles, serialized model objects, or arbitrary user-supplied paths.

### 2.3 Model/provider choice

**ASR.** Use ai4bharat/indic-conformer-600m-multilingual as the primary Indic provider. Its official model card describes a 600M multilingual Conformer hybrid CTC+RNNT model, 16 kHz input, a language code, and both decoding modes.[^1] Configure CTC first because it is simpler to batch and benchmark. Route a user-selected target language directly; do not run 22 ASR models to “detect” a known UI choice.

For English and optional auto-LID use faster-whisper with a pinned multilingual small CTranslate2 model. Original Whisper includes multilingual recognition and spoken-language identification, and faster-whisper documents CPU INT8/GPU reduced-precision modes.[^7][^8] That does not assert it outperforms IndicConformer on every Indic language.

**TTS.** Use ai4bharat/IndicF5 for the nine non-English required routes. It needs text, a reference audio prompt, and the prompt transcript; ship no celebrity or unconsented voice assets. The initial demo must include administrator-provided written-consent reference clips or expose no clone-like option. The official card lists 24 kHz output and an MIT licence but is access-gated.[^2]

Use Piper en_US-amy-medium only as compact generic English. If the team wants one experimental multilingual TTS model, test Indic Parler-TTS behind a feature flag. Its official card lists English plus target Indic languages except that Punjabi is explicitly unofficial, so it is not a contractual Punjabi route.[^3]

**Do not train ASR or TTS in the first prototype.** iTantra's core research question is link-efficient semantic communication. Integrating and benchmarking frozen ASR/TTS first makes later changes attributable. Train only the tokenizer/semantic codec initially.



### 2.4 Candidate model decision matrix

| Area | Candidate | Decision | Implementation reason |
|---|---|---|---|
| Indic ASR | AI4Bharat IndicConformer 600M multilingual | **Selected** | Official card documents all 22 scheduled languages, local 16 kHz interface, CTC/RNNT, and MIT licensing.[^1] |
| English / auto-language ASR | OpenAI Whisper via faster-whisper | **Selected fallback** | Local multilingual recognition/LID with documented efficient CPU/GPU implementation.[^7][^8] |
| IndicWhisper or other Whisper fine-tunes | Evaluate only behind a manifest entry | **Not a default** | Do not assume a current unofficial/third-party checkpoint has the required language coverage, licence, streaming behavior, or Windows compatibility. Pin and benchmark a card before adding it. |
| AI4Bharat monolingual IndicConformer | Optional later per-language baseline | **Not MVP** | May be useful for accuracy experiments, but 22 separate assets complicate a first local demo. |
| IndicF5 | **Selected Indic TTS** | **Selected** | Officially supports every required non-English target route, but demands consented prompt audio.[^2] |
| Indic Parler-TTS | One-model TTS experiment | **Optional** | Broad advertised coverage, but access gated and Punjabi marked unofficial.[^3] |
| AI4Bharat Indic-TTS FastPitch/HiFi-GAN checkpoints | Legacy per-language fallback experiment | **Optional** | May be useful when a small per-language checkpoint is verified locally; do not make current support/quality claims without pinning its exact release. |
| Coqui-compatible / generic TTS | Adapter only | **Not default** | Language/voice availability changes by checkpoint; registry must verify it, not infer support from engine name. |
| Exact SentencePiece text tokens | **Selected semantic source** | **MVP** | Lowest complexity and exact conditional text recovery. |
| EnCodec | Audio baseline | **Baseline** | Published 1.5–24 kbps source modes, not a 0.5–1 kbps complete radio solution.[^9] |
| DAC | Audio baseline | **Baseline** | Published neural audio codec modes at higher source rates.[^10] |
| SoundStream | Literature/audio baseline | **Not default** | Important neural audio codec precedent, but not a ready local Indian-language semantic-radio integration.[^20] |
| SpeechTokenizer / semantic speech tokens | Research comparison | **Not default** | Base semantic token stream is about 50 Hz × 10 bits before framing/FEC and no verified Indic production decoder is assumed.[^17] |
| DeepSC-S/DeepSC-SR/DeepSC-ST | Research inspiration | **Not MVP** | Validates the research direction but does not replace a testable local protocol.[^12][^14][^15] |
| Continuous DeepJSCC | Research-only learned modem | **Not default** | Its channel-use representation needs an explicit modem/wire mapping before a bitrate claim.[^13] |


## 3. Exactly what crosses the low-bitrate link

### 3.1 Options compared

| Path | Payload | Practical rate range | Receiver output | Strengths | Main limitation | Decision |
|---|---|---:|---|---|---|---|
| A. Text-token semantic link | Normalized subword IDs, language/version, literals | Content-dependent 0.5–2 kbps gross is viable for phrases | Exact normalized text if complete | Real now, inspectable, language agnostic wire layer | Loses speaker/prosody; ASR-dependent | **MVP default** |
| B. Neural audio codec | EnCodec/DAC RVQ audio codes | EnCodec source starts 1.5 kbps before FEC/header | Reconstructed audio | Preserves acoustic traits better | Less useful below 1–2 kbps; Windows caveat | Baseline only |
| C. Neural semantic VQ | Discrete VQ IDs + literal anchor table | Nominal 16–128 bps source codes; measure true wire rate | Semantic hypothesis + TTS | Learns importance tradeoffs | Cannot promise verbatim transcript | Advanced |
| D. Continuous DeepJSCC | Complex symbols | Channel uses/s, not intrinsic kbps | Text/speech hypothesis | Can degrade gracefully under matching channel | Research; no packet interoperability | Research only |

### 3.2 MVP payload

The ITP/1 link carries **no WAV, base64 JSON, embedding, or LLM prompt**. Its source payload contains:

1. profile, normalizer, and tokenizer identifiers;
2. language ID;
3. utterance timing and bounded token count;
4. fixed-width packed SentencePiece token IDs;
5. a typed UTF-8 literal escape only when a tokenizer byte-fallback cannot represent a character;
6. only in advanced semantic mode, an anchor table for numbers, dates, URLs, phone numbers, and user-marked critical phrases.

Train one shared **SentencePiece Unigram model with vocabulary 16,384 and byte fallback** on legal, versioned text across the target languages plus English. Prepend a language control token such as <lang:hi>. Persist the model SHA-256 and normalization version in HELLO/session metadata.

Use fixed 14-bit token IDs in the MVP, not range/rANS entropy coding. A fixed-width lane is less compression-efficient but sharply limits damage from a corrupted payload and makes a CRC-protected recovery test unambiguous. An entropy-coded text_v2 mode may be added only after fuzz testing proves a corrupted frame cannot desynchronize a later frame.

The MVP must not call fixed token IDs a learned semantic latent. They are exact normalized text coding. That is a valuable property: tests can prove an input SemanticFrame round-trips exactly after a successful packet/FEC recovery.

### 3.3 ITP/1 packet protocol

All multibyte integers are unsigned big-endian. A packet is header, payload, CRC. FEC protects the complete header+payload+CRC bytes; blocks use fixed data-packet length and zero-pad the final fragment after its declared payload length.

| Byte(s) | Field | Description |
|---:|---|---|
| 0 | magic_version_profile | Magic high 4 bits 0xA; version 2 bits; profile 2 bits. |
| 1 | flags | data/parity, final fragment, retransmission-reserved, encrypted-reserved. |
| 2–3 | session_id | Random nonzero uint16; scope is one browser session. |
| 4–5 | sequence | Monotonic uint16; wrap has session epoch. |
| 6–7 | start_tick_20ms | First source-audio timestamp in 20 ms ticks. |
| 8 | language_id | Registry ID, not a UTF-8 string. |
| 9 | payload_type | 0x02 exact SP IDs is default; 0x03 VQ experimental; 0x04 literal anchors. |
| 10 | fragment | high nibble index, low nibble count minus one; max 16 fragments. |
| 11 | payload_length | Useful bytes in this fragment, excluding zero padding. |
| 12 | generation_id | FEC generation/block ID modulo 256. |
| 13 | header_crc8 | CRC-8 over bytes 0–12. |
| 14… | payload | Profile-sized payload bytes; first fragment has miniheader. |
| end-2…end-1 | packet_crc16 | CRC-16/CCITT over header and payload. |

For payload type 0x02:

~~~text
utterance_id:u16 | token_count:u8 | token_ids: token_count x uint14 | optional byte-fallback literal table
~~~

A session HELLO contains the first 128 bits of tokenizer SHA-256 and the complete manifest can be fetched locally. A receiver rejects a tokenizer mismatch—never guesses token meaning.

### 3.4 Physical/link protection

The default simulated physical chain is:

~~~text
ITP packet -> CRC-16 -> additive scrambler -> convolutional encoder
K=7, generators (171,133)_oct -> optional puncture from 1/2 to 2/3
-> block interleaver -> BPSK (or QPSK in a later switch) -> channel
-> soft LLR -> Viterbi -> CRC -> erasure/FEC handling
~~~

Use a simple rolling XOR(4,3) parity group in MVP for one packet erasure at 25% parity overhead. Add Reed–Solomon/RaptorQ only in the advanced transport profile after golden-vector tests. This layered choice is deliberately simpler than claiming production FEC. In every profile, a failed CRC is an erasure; corrupt IDs never reach text or TTS.

### 3.5 Honest rate equations and feasibility

For raw PCM: R_pcm = f_s × bits_per_sample × channels. For 16 kHz/16-bit/mono, R_pcm = 256,000 bps.

For any run of input duration D:

~~~text
R_wire      = transmitted_on_air_bits / D
R_good      = recovered_useful_payload_bits / D
R_source    = source_payload_bits / D
compression_ratio_vs_PCM = 256000 / R_wire
T_serial    = transmitted_on_air_bits / R_phy
P_packet_fail_IID = 1 - (1 - BER)^N
~~~

On-air transmitted bits include preamble, headers, payload, CRC, FEC/parity, and retransmissions. Codec/source rate is not link rate.

At 0.5 kbps, use a 2-second superframe budget of 1,000 transmitted bits. With code rate 2/3, only 667 pre-code source bits exist. After an approximately 128-bit packet header/CRC and a 24-bit text miniheader, 515 bits remain: floor(515/14) = 36 IDs before preamble/margin; cap it at 32. At code rate 1/2 the practical cap is about 22 IDs. This is usable for short messages/turns, but serialization alone is about two seconds. It is not transparent telephone latency.

| Gross rate | 1,000 on-air bit serialization time | Intended policy |
|---:|---:|---|
| 0.5 kbps | 2.000 s | Store-and-forward short phrase; strict token/back-pressure cap. |
| 1 kbps | 1.000 s | Extreme-link demo; phrase-level interaction. |
| 2 kbps | 0.500 s | **Default MVP demo**; 1–2 s phrase segments. |
| 4 kbps | 0.250 s | Robust interactive test lane. |
| 8 kbps | 0.125 s | Audio-codec baseline lane. |
| 16 kbps | 0.0625 s | Fair Opus/EnCodec baseline lane, not the headline. |

A report must show configured gross rate, R_wire, R_source, R_good, FEC overhead, packet overhead, queue time, and output delay separately. Do not show only a selector label such as “1 kbps.”

## 4. Channel simulator

### 4.1 Modes

ChannelConfig.mode has three distinct modes:

1. abstract_packet: scheduled packet loss, reordering, latency, and jitter; no bit errors. Use for UI/debug tests.
2. abstract_bit: serialize ITP bytes, flip independent or burst bits, reassemble, then CRC/FEC. Use for protocol tests.
3. bpsk_awgn or rayleigh_bpsk: serialize bytes to bits, BPSK-modulate, add channel noise/fading, hard/soft-decision demodulate, then use the same packet parser. Use for a defensible simulated-radio demo.

Never expose an SNR slider that merely maps to a hidden packet-loss rate. abstract_packet explicitly uses packet_loss_rate. Physical modes use eb_n0_db and report measured BER.

### 4.2 Mathematical model

For bit b in {0,1}, normalized BPSK symbol x = 1 - 2b. In AWGN:

~~~text
y = x + n,     n ~ N(0, sigma^2)
sigma^2 = 1 / (2 * 10^(Eb/N0_dB / 10))     # normalized Eb = 1
b_hat = 0 if y >= 0 else 1
BER_ideal = Q(sqrt(2 Eb/N0))                # coherent uncoded BPSK reference
~~~

For flat Rayleigh block fading per packet, y = h x + n, h ~ CN(0,1). The first simulator assumes perfect receiver CSI and uses y/h; label this assumption. A Rician option uses h = sqrt(K/(K+1)) h_LOS + sqrt(1/(K+1)) h_NLOS.

Use a Gilbert–Elliott state machine for burst errors: state G has BER p_g, B has BER p_b, transition P(G→B)=p_gb and P(B→G)=p_bg. A link trace records state transitions only in debug/export mode.

Useful upper-bound and queue equations:

~~~text
SNR_dB = 10 log10(P_signal / P_noise)
C = B log2(1 + SNR)                         # Shannon upper bound, not achieved rate
one_way_latency = capture + ASR + encode + queue + tx + propagation + jitter + decode + TTS
~~~

### 4.3 Required configuration

~~~json
{
  "mode": "bpsk_awgn",
  "gross_rate_bps": 2000,
  "eb_n0_db": 5.0,
  "packet_loss_rate": 0.0,
  "bit_error_rate": null,
  "burst": {"enabled": false, "p_g": 0.0, "p_b": 0.02, "p_gb": 0.01, "p_bg": 0.20},
  "fading": {"enabled": false, "type": "rayleigh", "block_symbols": 752},
  "base_latency_ms": 80,
  "jitter_ms_stddev": 25,
  "reorder_probability": 0.02,
  "seed": 1729,
  "real_time_pacing": true
}
~~~

Validation: rate > 0; probabilities in [0,1]; eb_n0_db only in physical modes; bit_error_rate only in abstract_bit; packet loss can be layered after modulation to model MAC drops; seed is mandatory for an evaluable run; UI shows every active impairment.

### 4.4 Receiver recovery rules

1. Validate magic/version/header CRC/session/length/packet CRC. Invalid packets become erasures only when their block membership is trustworthy; otherwise discard and record invalid_header.
2. The reorder buffer holds packets until min(playout deadline, first-packet time + max jitter). Then it sends symbols to FEC recovery.
3. The MVP XOR(4,3) recovers one erasure in its group. Later RS(K,M) may recover at most M erasures. No soft-decision FEC claim is made.
4. If recovery fails, mark every affected semantic frame partial or unrecoverable. Do not concatenate a token stream across a missing frame.
5. text_v1 text is emitted only after the complete frame validates. Prior complete frames may synthesize while later frames travel.



## 5. Advanced neural transceiver design

### 5.1 Recommended advanced architecture: hybrid neural semantic VQ

The practical next step is a **hybrid digital neural transceiver**. It learns which semantic information to retain, but produces finite discrete indices that fit the existing protected packet protocol. It avoids calling a continuous latent “bits per second.”

#### Neural transmitter (semantic_vq_v1)

Input is canonical text token IDs x: int64[B,L], L ≤ 64 tokens for a 2-second semantic segment; language ID lang: int64[B]; critical-token mask critical: bool[B,L]. The model is trained on text, not raw waveform, in its first version.

~~~text
token embedding (V=16,384, D=256)
+ language embedding + position embedding
  -> 4 TransformerEncoder blocks (d_model=256, heads=8, d_ff=1024, dropout=0.10)
  -> learned-query attention pooling, M=8 queries: [B,L,256] -> [B,8,256]
  -> linear projection: z_e [B,8,128]
  -> residual/product VQ: Q=2 codebooks, K=256, D=128
  -> code IDs c: uint8[B,8,2], 8 bits per code ID
  -> optional lossless literal-anchor lane
  -> ITP/1 payload type 0x03
~~~

The raw VQ code budget is M × Q × log2(K) = 8 × 2 × 8 = 128 bits per 2-second segment, or 64 source bps before packet control/FEC. This is a design budget, **not an achieved semantic quality result**.

Train and ship the following four profiles, with a rate token sent to both encoder and decoder:

| Profile | M | Q | Raw code rate for 2 s | Intended study |
|---|---:|---:|---:|---|
| vq_16 | 4 | 1 | 16 bps | Research stress point only. |
| vq_32 | 8 | 1 | 32 bps | Low semantic capacity. |
| vq_64 | 8 | 2 | 64 bps | Default advanced experiment. |
| vq_128 | 16 | 2 | 128 bps | Higher-fidelity semantic experiment. |

Use rate dropout: in a batch randomly select an allowed M/Q prefix, mask the rest, and train the same decoder. The model must be conditioned on profile and channel quality; no separate untracked checkpoints per rate.

#### Neural receiver

~~~text
ITP/1 payload -> validate/recover -> unpack VQ IDs + literal anchors
  -> VQ lookup z_q [B,8,128]
  -> linear projection [B,8,256]
  -> prepend language and channel embeddings [B,2,256]
  -> 4 TransformerEncoder blocks (d_model=256, heads=8, d_ff=1024)
  -> 4-layer causal cross-attention TransformerDecoder
  -> logits [B,L_out≤64,V=16,384]
  -> constrained SentencePiece decode -> restore exact literal anchors
~~~

The decoder emits a semantic hypothesis, not a guaranteed verbatim transcript. It must show the “semantic reconstruction” badge, confidence, and critical-anchor verification state. If an anchor cannot be restored or confidence is below a configured threshold, it must abstain with “repeat required,” not speak a plausible invented command.

#### Critical literal policy

Before neural compression, deterministic extraction marks phone numbers, dates/times, amounts, decimal values, URLs, email addresses, negations, abbreviations/acronyms, and user-selected phrases. Serialize each as type, source-token span, UTF-8 literal in payload type 0x04. Protect this lane by lower code rate and duplicate it across two FEC generations when it fits the gross-rate budget. Do not rely on a semantic embedding to preserve a digit or a negation.

At 0.5 kbps, a 2-second vq_64 frame has 128 code bits. A 128-bit literal side lane and roughly 152 bits of headers/miniheader total 408 source bits; at 2/3 code rate this is approximately 612 coded bits before preamble/parity. Therefore it does **not** automatically fit a 500-bps profile. The scheduler must either extend the segment, reduce literal payload, use vq_32, or declare back-pressure. Rate accounting is enforced by code, not an architectural promise.

### 5.2 Training losses

Let t be target token IDs, z_e encoder vectors, z_q quantized vectors, E a frozen multilingual text embedding model used only as an auxiliary evaluator/loss, and R_hat the entropy-estimated code rate. Let m be a packet-erasure/burst mask sampled by the profile/channel generator.

~~~text
L_CE      = - sum_i log p_theta(t_i | t_<i, corrupt(z_q,m), lang)
L_anchor  = weighted exact cross entropy over digits, names, negation, IDs, and literals
L_VQ      = ||sg[z_e] - z_q||_2^2 + beta ||z_e - sg[z_q]||_2^2, beta=0.25
L_rate    = max(0, R_hat - R_target)^2
L_sem     = 1 - cosine(E(source_text), E(decoded_text))
L_conf    = calibration loss for predicted decode-success/confidence
L_channel = L_CE evaluated under independently sampled loss/burst masks

L_total = 1.0 L_CE + 2.0 L_anchor + 1.0 L_VQ
        + lambda_R L_rate + lambda_S L_sem + 0.5 L_channel + 0.1 L_conf
~~~

Start lambda_R at 0.002 and sweep it. Save the resulting rate-versus-exact-token-versus-semantic-score frontier. Do not choose a weight based on a visually attractive demo.

L_sem cannot replace exact word/entity measurements: embedding models can consider sentences semantically close while losing the difference between “do” and “do not,” or changing a number. Train/validate its appropriateness per language before retaining it. A frozen IndicBERT-like embedding model is optional; its model/revision must be recorded.

For a future raw-audio neural codec/JSCC experiment, use a separate objective:

~~~text
L_audio = lambda_wav * ||s - s_hat||_1
        + lambda_stft * sum_r || abs(STFT_r(s)) - abs(STFT_r(s_hat)) ||_1
        + lambda_asr * CE(ASR(s_hat), reference_transcript)
        + lambda_rate * R_hat + lambda_channel * L_channel
~~~

SI-SDR, STOI, PESQ, and MOS are metrics, not the default text-semantic training loss. PESQ is non-differentiable and has deployment/licensing considerations; never present it as a semantic-route loss.

### 5.3 Research-only continuous DeepJSCC profile

Start only after semantic_vq_v1 has a written baseline. Reuse the VQ text encoder through query pooling, then map to channel symbols:

~~~text
semantic memory [B,8,256]
  -> MLP [B,8,256] -> flatten/project [B,2n]
  -> n=24 complex samples -> [B,24,2] real/imaginary
  -> x = sqrt(nP) z / (||z||_2 + epsilon)
  -> AWGN/block-fading layer -> receiver MLP [B,48] -> [B,8,256]
  -> same Transformer text decoder
~~~

Train with SNR sampled from a declared range, channel-state embedding, and a channel-use budget. The output is n complex **channel uses**. Report channel uses per source segment, uses/s, occupied bandwidth, power, and SNR. Do not label it kbps until the project specifies symbol rate, I/Q quantization, framing, synchronization, FEC, and RF interface. For example, 24 complex samples packed as 6-bit I plus 6-bit Q consume 288 bits per 2-second segment before control headers/FEC; that serialization calculation is not a property of the neural model itself.

DeepJSCC's original work maps sources to complex channel symbols, not to interoperable byte packets.[^13] DeepSC-SR, DeepSC-S, and DeepSC-ST are useful speech-semantic research precedents, but not drop-in robust Indian-language radio systems.[^12][^14][^15]

### 5.4 Why the architecture uses these building blocks

| Choice | Use now? | Why | Rejected alternative / trigger to revisit |
|---|---|---|---|
| Transformer text encoder/decoder | Advanced | Handles variable token sequences and shared multilingual vocabulary; exact shapes are modest enough for one GPU | GRU/LSTM can be tested as small baseline; use only if measured latency/memory wins. |
| VQ/RVQ | Advanced | Produces bounded discrete symbols suitable for a digital link and rate control | VAE continuous latent cannot directly yield auditable bit counts. |
| CNN | No for initial text VQ | Convolution offers little advantage for 64-token multilingual semantics here | Use in raw-audio codec research where local waveform structure matters. |
| MLP channel mapper | Research | Minimal mapping from semantic memory to complex symbols | Add channel-conditioned Transformer only after MLP baseline. |
| DeepJSCC | Research | Legitimate neural source-channel hypothesis | Do not implement ahead of text_v1/VQ results. |
| EnCodec/SpeechTokenizer/DAC | Baselines | Compare acoustic-codec route to semantic route | Not MVP payload: EnCodec source starts at 1.5 kbps and SpeechTokenizer's base 50 Hz×10-bit stream is 500 bps before transport/FEC.[^9][^10][^17] |

## 6. Dataset, model, and training strategy

### 6.1 Dataset policy

Before downloading any corpus, produce data/licenses.csv with source URL, version/revision, license/terms, language, intended use, checksum, number of clips, hours, and a reviewer field. Do not assume “available online” means suitable for all deployment purposes.

| Dataset | Use | Languages / evidence | Preparation and restrictions |
|---|---|---|---|
| IndicVoices / IndicConformer ecosystem | ASR validation/fine-tuning candidate | AI4Bharat publishes an IN-22 ASR model and related data/model resources.[^1] | Verify the actual dataset terms/revision before training; no hidden redistribution. |
| SPRING-INX | Held-out ASR/domain diversity candidate | Published corpus describes roughly 2,000 hours over Assamese, Bengali, Gujarati, Hindi, Kannada, Malayalam, Marathi, Odia, Punjabi, and Tamil.[^5] | Not a complete source for Telugu; use speaker-disjoint source splits. |
| Mozilla Common Voice | Accent/domain-diverse ASR and LID supplement | Public multilingual voice dataset; inspect locales/hours at the chosen frozen release.[^6] | Downloaded locale availability varies; record release and language config. |
| IndicVoices-R | TTS training/research candidate | Published as 1,704 hours from 10,496 speakers across 22 Indian languages.[^4] | Use only with verified licence and speaker/data terms; do not use test speakers for prompt voices. |
| FLEURS / other legal open speech corpus | Independent evaluation supplement | Use only after exact licence and language availability are recorded | Never mix an evaluation set into ASR/TTS tuning. |
| Legal Indic/English text corpora | SentencePiece and neural semantic model | Train tokenizer on only documented licensed data | Deduplicate; do not use scraped private conversations. |

### 6.2 Languages and language policy

The delivery target is Hindi (hi), English (en), Bengali (bn), Tamil (ta), Telugu (te), Marathi (mr), Gujarati (gu), Kannada (kn), Malayalam (ml), and Punjabi (pa).

| Concern | Required implementation rule |
|---|---|
| LID | Manual selection is primary. Auto mode uses first-prefix audio only and needs confidence ≥0.75; otherwise prompt user. |
| Scripts | Preserve native Unicode scripts on wire; normalize to NFC. Do not convert all scripts to Devanagari as an internal shortcut. |
| Hindi/Marathi ambiguity | Both use Devanagari. Text script alone cannot distinguish them; use selected spoken language/ASR route. |
| Code switching | Store language spans when ASR provides them or use script/Latin-run segmentation. Use explicit <lang:xx> controls per span. |
| Hinglish | Keep original Latin/Devanagari token spelling after NFC. For TTS, segment Indian-language and English spans; crossfade 20 ms. Label this as a fallback, not seamless single-voice code-switch synthesis. |
| Transliteration | Optional UI display only. Never send a lossy transliteration as the canonical payload. |
| Punctuation/numbers | Normalizer has per-language policy/version; preserve raw ASR text alongside canonical form. Critical numeric/literal lane overrides neural paraphrase. |
| Dialect/accent | Stratify evaluation by documented region/accent if data allows; report “not evaluated” rather than generalizing. |
| Scale-out | Add a registry entry (ASR provider, TTS provider, language ID, normalizer, tokenizer tests, fixtures) and pass the same conformance suite. |

### 6.3 Preprocessing and deterministic splits

ASR ingestion: downmix to mono, resample to 16 kHz, retain original file hash, discard only media failing explicit duration/SNR/metadata rules, and log every exclusion. TTS research ingestion: preserve the model's required sample rate (IndicF5 output is documented as 24 kHz) and do not resample prompt audio silently.[^2]

Split at the **speaker** level, not clip level. Default target: 80% train, 10% validation, 10% test by speaker per language. If source-provided test partitions exist, preserve them as external test sets. Write immutable JSONL manifests with audio hash, speaker ID, language, source dataset, and split seed. A speaker must never appear across train and test in a TTS/speaker-sensitive experiment.

Use 2-second semantic segments for VQ training where possible; clip at sentence/ASR punctuation boundaries, retain an end-of-sentence token, pad to 64 tokens, and reject/truncate with an explicit counter when length exceeds 64. Do not silently truncate critical spans.

### 6.4 Staged training plan

| Stage | What is trained | Inputs / augmentation | Acceptance gate |
|---|---|---|---|
| 0. Data manifest | Nothing | License audit, SHA manifests, speaker splits | Reproducibility file passes schema and no split leaks. |
| 1. Foundation integration | Nothing | Frozen ASR/TTS adapters and fixtures | Each MVP language can transcribe/synthesize a licensed smoke fixture locally. |
| 2. Text wire codec | SentencePiece + fixed-ID packer | Canonical training text; malformed Unicode/token fuzz cases | 10,000 randomized frame round trips byte-exact; mismatch rejected. |
| 3. Channel/transport | Nothing learned | Golden bits, BPSK AWGN, loss/burst/reorder fuzz | CRC/FEC behavior matches known vectors and no corrupted frame reaches TTS. |
| 4. Semantic VQ pretraining | VQ encoder/decoder | Clean multilingual text, profile-rate dropout | Held-out token/anchor metrics exceed predefined baseline at each profile. |
| 5. Channel-robust VQ | Same | Packet erasure/burst masks, channel/profile conditioning | Loss/decode metrics published across configured impairments. |
| 6. End-to-end integration | No joint ASR/TTS tuning | Frozen speech fixtures through all services | Actual wire rate/latency report persists for each run. |
| 7. Optional end-to-end research | Carefully selected modules | Audio/noise/channel with frozen baselines | Ablation shows benefit against text and audio baselines; otherwise stop. |

Initially freeze ASR and TTS. Train the tokenizer and VQ model only. Fine-tune ASR/TTS only after an error analysis says their errors, rather than link losses, are the material bottleneck. Full ASR/TTS training is a separate project with much larger data/hardware needs.

### 6.5 Augmentation

For ASR robustness study only, use declared augmentation probabilities: gain ±6 dB; background noise at SNR 5–25 dB from licensed noise; room impulse response with probability 0.3; speed 0.9–1.1 with transcript retained. Do not apply an augmentation to test data except a separately labelled “noisy test” suite.

The channel simulator is not an audio augmentation. Apply it after source coding. This preserves attribution: ASR robustness versus transport robustness.



## 7. Software architecture and repository contract

### 7.1 Monorepo layout

~~~text
itantra/
├── README.md
├── LICENSE
├── .env.example
├── pyproject.toml
├── requirements-lock.txt
├── package.json
├── docker-compose.yml
├── models/
│   ├── manifest.yaml
│   ├── checksums/
│   └── prompts/                    # consented, non-user, gitignored audio assets
├── configs/
│   ├── app.local.yaml
│   ├── languages.yaml
│   ├── wire_profiles.yaml
│   ├── channel/
│   ├── train/
│   └── eval/
├── apps/
│   └── web/
│       ├── src/
│       ├── public/
│       └── package.json
├── services/
│   └── api/
│       ├── itantra_api/
│       └── tests/
├── packages/
│   ├── engine/
│   │   └── itantra/
│   │       ├── audio/
│   │       ├── stt/
│   │       ├── language/
│   │       ├── text/
│   │       ├── semantic/
│   │       ├── transport/
│   │       ├── channel/
│   │       ├── tts/
│   │       ├── orchestration/
│   │       └── metrics/
│   └── protocol/
│       ├── spec/
│       ├── golden_vectors/
│       └── itantra_protocol/
├── training/
│   ├── data/
│   ├── models/
│   ├── callbacks/
│   └── scripts/
├── evaluation/
│   ├── suites/
│   ├── baselines/
│   ├── reports/
│   └── scripts/
├── data/
│   ├── manifests/
│   ├── raw/                        # gitignored
│   ├── processed/                  # gitignored
│   └── splits/
├── runs/                           # gitignored except explicit export
├── scripts/
│   ├── bootstrap.ps1
│   ├── download_models.ps1
│   ├── doctor.ps1
│   └── create_demo_bundle.ps1
├── tests/
│   ├── unit/
│   ├── integration/
│   ├── protocol/
│   ├── e2e/
│   └── fixtures/
├── docs/
│   ├── architecture.md
│   ├── protocol.md
│   ├── model-cards.md
│   └── privacy.md
└── docker/
    ├── api.Dockerfile
    ├── web.Dockerfile
    └── compose/
~~~

Model weights, dataset audio, captured user voice, Python virtual environments, node_modules, and live run output do not belong in Git.

### 7.2 Important files and exact responsibilities

| Path | Responsibility / important units | Inputs, outputs, dependencies |
|---|---|---|
| README.md | Installation, honest scope, hardware profiles, quick demo, troubleshooting | Points to specification; never claims unmeasured quality. |
| .env.example | Paths/options only: ITANTRA_MODEL_DIR, CORS origin, upload limit, retain-runs flag | No access token, model-gate token, or private prompt path. |
| pyproject.toml | Python metadata, tools, Python requirement, dependency groups | FastAPI, Pydantic, PyTorch/torchaudio, NumPy, SentencePiece, soundfile, PyYAML. |
| models/manifest.yaml | Pinned repo/revision, checksum, licence/access acknowledgement, provider class, languages, audio I/O | ModelManager refuses unpinned/unavailable model. |
| configs/languages.yaml | Language ID, scripts, ASR/TTS provider key, normalizer, test fixture | LanguageRegistry produces validated LanguageSpec. |
| configs/wire_profiles.yaml | Tokenizer hash, payload cap, FEC rate, interleaver, frame duration, queue cap | TransportProfile frozen in a session. |
| packages/engine/itantra/audio/preprocess.py | validate_audio(), resample_mono_16k(), pcm_to_tensor() | bytes/file -> AudioTensor; torchaudio/soundfile. |
| packages/engine/itantra/audio/vad.py | VadSegmenter.push(), finalize() | AudioChunk -> boundaries; Silero adapter, energy fallback. |
| packages/engine/itantra/stt/base.py | STTProvider.transcribe() abstract contract | AudioTensor + LanguageSpec -> TranscriptResult. |
| packages/engine/itantra/stt/indic_conformer.py | IndicConformerProvider, lazy loading, CTC/RNNT option | Uses only trusted pinned AI4Bharat model. |
| packages/engine/itantra/stt/whisper.py | FasterWhisperProvider and LID router | English/audio prefix -> TranscriptResult + LID score. |
| packages/engine/itantra/text/normalize.py | normalize_text(), extract_critical_spans(), TokenizerAdapter | raw transcript -> NormalizedText. |
| packages/engine/itantra/semantic/schema.py | SemanticFrame, CriticalSpan, ReconstructedFrame | Versioned schema; JSON only at API edge. |
| packages/engine/itantra/semantic/text_codec.py | pack_uint14(), unpack_uint14(), frame codec | SemanticFrame -> bytes -> exact SemanticFrame. |
| packages/engine/itantra/semantic/vq.py | SemanticVQTransmitter, SemanticVQReceiver | Tensor contracts from section 5; Torch checkpoint/profile. |
| packages/engine/itantra/transport/packet.py | Packet.to_bytes(), parse_packet(), CRC validation | Packet dataclass ↔ bytes, no network side effect. |
| packages/engine/itantra/transport/fec.py | convolutional_encode(), viterbi_decode(), xor_parity_recover() | bits/packets -> protected/recovered bits; golden-vector tested. |
| packages/engine/itantra/transport/session.py | HELLO negotiation, tokenizer mismatch, reassembly state | SessionConfig -> ordered ReconstructedFrames. |
| packages/engine/itantra/channel/simulator.py | simulate(), BPSK/AWGN/Rayleigh/GilbertElliott | packet stream + ChannelConfig -> timestamped DeliveryEvents. |
| packages/engine/itantra/tts/base.py | TTSProvider.synthesize() | text/language/permitted voice config -> AudioResult. |
| packages/engine/itantra/tts/indicf5.py | IndicF5Provider, consented-prompt validation | 24 kHz PCM; refuses absent/unauthorized prompt. |
| packages/engine/itantra/tts/piper.py | PiperProvider English fallback | text -> local WAV; no accent claim. |
| packages/engine/itantra/orchestration/run.py | EndToEndRun.execute(), cancellation, artifact lifecycle | audio + config -> RunReport, ephemeral artifacts. |
| packages/engine/itantra/metrics/compute.py | rate/latency/BER/PER/WER/CER/resource metrics | trace/reference/result -> nullable Metrics. |
| packages/protocol/spec/itp1.md | Normative field size/state/version rules | Must match golden vectors, API schema, UI labels. |
| packages/protocol/golden_vectors | Valid/corrupt packets and expected recovery results | Used by Python and browser TypeScript decoder tests. |
| services/api/itantra_api/main.py | FastAPI application/lifespan/model manager/routing | Imports engine; no ML logic in routes. |
| services/api/itantra_api/routes/ | Thin validated HTTP/WebSocket handlers | Pydantic request -> orchestrator -> Pydantic response. |
| services/api/itantra_api/security.py | limits, MIME sniffing, CORS, local-auth hook, deletion | Covered by negative tests. |
| apps/web/src/App.tsx | Research-bench shell/session lifecycle | Typed API client and WebSocket hook. |
| apps/web/src/features/transmitter | capture, waveform, ASR/semantic preview, controls | Browser AudioWorklet + typed state; no mock output. |
| apps/web/src/features/channel | packet timeline/actual counters | DeliveryEvent trace -> accessible SVG/Canvas. |
| apps/web/src/features/receiver | recovered text, audio player, uncertainty | ReconstructedFrame/AudioResult. |
| training/scripts/train_semantic_vq.py | reproducible profile/channel VQ training | YAML -> checkpoint, metrics, data/model hashes. |
| evaluation/scripts/run_matrix.py | factorial evaluation/resume | YAML -> Parquet/CSV/report, never fake cells. |
| scripts/doctor.ps1 | Python/Node/GPU/model/audio-device diagnostics | human-readable report; nonzero exit if core missing. |

### 7.3 Architectural rules

* Core engine is importable without FastAPI, React, Docker, or GPU. Unit tests use fake STT/TTS providers.
* Model providers are plug-ins selected by manifest/registry. A provider can fail unavailable; it cannot silently use a different model/language.
* Protocol package contains no neural dependency and owns byte-exact golden vectors.
* API/UI never calculate or substitute quality metrics. They display engine-produced values only.
* Tests use synthetic fixtures/licensed assets, never captured end-user recordings.
* Every artifact path is explicit and under configured run directory; uploaded filename never determines a path.



## 8. Technology stack

| Layer | Choice | Why | Explicit non-choice |
|---|---|---|---|
| Backend | Python 3.11+, FastAPI, Uvicorn, Pydantic v2 | Type-safe async API, OpenAPI, binary/WebSocket support, ML ecosystem | No Django/ORM for a no-database demo. |
| ML runtime | PyTorch + torchaudio | Required by selected ASR/TTS/VQ integrations; local CUDA/CPU support | No custom CUDA kernels for MVP. |
| Text/transport | SentencePiece, Unicode tools, NumPy | Deterministic multilingual IDs and numerical channel simulator | No LLM needed for text_v1 compression. |
| Audio | soundfile, torchaudio, browser Web Audio API | Resampling, PCM validation, microphone capture/playback | Do not depend on MediaRecorder lossy blobs in streaming core. |
| Indic ASR | AI4Bharat IndicConformer 600M | Documented 22 official-language coverage/local CTC-RNNT interface.[^1] | Do not claim English coverage. |
| English ASR/LID | faster-whisper multilingual small | Local fallback with CPU/GPU modes.[^8] | Do not force as primary Indic recognizer. |
| Indic TTS | AI4Bharat IndicF5 | Required nine Indic outputs, local prompt synthesis.[^2] | No unconsented voice cloning. |
| English TTS | Piper en_US-amy-medium | Small local ONNX fallback.[^16] | No verified Indian-English assertion. |
| Frontend | React + TypeScript + Vite | Browser audio integration and local developer loop; current Vite docs state supported Node versions/scaffolding.[^18] | No generic landing-page framework. |
| Visualization | Native SVG/Canvas/Web Audio analyser | Trace-driven low-dependency packet/signal visuals | No fake animated particles. |
| State | React reducer/context plus typed API client | One demo session has modest state | No Redux unless need is proven. |
| Storage | Ephemeral filesystem; optional SQLite metadata | Enough for local research demo | No Redis/Postgres by default. |
| Tests | pytest, Hypothesis, Playwright, Vitest | Protocol fuzzing, Python integration, browser e2e | No manual-only validation. |
| Containers | Docker Compose, optional NVIDIA profile | Repeatable API/web execution after local-first works | No Kubernetes. |
| Deployment | Windows local first; Docker Desktop/WSL2 or Linux GPU second | Matches target environment/model ecosystem | No paid cloud dependency. |

PyTorch installation commands must be generated from the current official selector for that workstation's CUDA/CPU target; current Windows guidance supports Python 3.10–3.14.[^19] After a tested install, lock actual versions in requirements-lock.txt.

## 9. API specification

### 9.1 Common conventions

Base path: /api/v1. JSON is UTF-8. Audio upload uses multipart/form-data, never base64. Timestamps are integer milliseconds. Mutating request accepts optional X-Request-ID; every response returns request_id. Default server limit is audio ≤30 s and ≤16 MiB, configurable through capabilities.

~~~json
{
  "error": {
    "code": "MODEL_UNAVAILABLE",
    "message": "IndicF5 model is not installed or access has not been accepted.",
    "details": {"provider": "indicf5", "language": "ta"},
    "request_id": "uuid"
  }
}
~~~

Status use: 400 invalid config; 401/403 if auth enabled; 404 absent session/run; 409 protocol/tokenizer mismatch; 413 size/duration; 415 unsupported audio; 422 schema; 429 concurrency; 503 unavailable model/GPU; 500 unexpected fault. Never turn a model exception into fake success.

### 9.2 HTTP endpoints

| Method / path | Request | Response | Latency class / notes |
|---|---|---|---|
| GET /health | none | process/model-manager/storage health | <50 ms target; does not load every model. |
| GET /capabilities | none | providers, revisions, limits, profiles, hardware capability | <100 ms; no secrets/paths. |
| GET /languages | enabled_only optional | LanguageSpec list, ASR/TTS availability/limitations | <100 ms. |
| GET /wire-profiles | none | named transport profiles | <100 ms. |
| POST /sessions | SessionCreate JSON | frozen Session config/hash | <100 ms. |
| DELETE /sessions/{session_id} | none | 204 | Deletes ephemeral buffers/artifacts. |
| POST /transcriptions | multipart audio, language, decoder mode | TranscriptResult | Model-bound; returns measured processing_ms. |
| POST /frames/encode | SemanticFrameInput JSON | EncodedFrame/packet summaries | <50 ms text_v1 target; VQ model-bound. |
| POST /transmissions | binary/JSON packet payload | TransmissionResult plus trace | Fast except real_time_pacing. |
| POST /frames/decode | received packet array/binary artifact | ReconstructedFrame | <50 ms text_v1 target. |
| POST /synthesis | SynthesisRequest JSON | audio/wav stream or artifact ID/metadata | Model-bound; never placeholder waveform. |
| POST /runs | multipart audio plus RunConfig JSON part | 202 RunAccepted | Bounded end-to-end job. |
| GET /runs/{run_id} | none | RunReport/status | <100 ms target. |
| GET /runs/{run_id}/trace | none | JSONL or paginated DeliveryEvents | Only retention-window data. |
| DELETE /runs/{run_id} | none | 204 | Deletes artifacts/report under retention policy. |
| WS /stream/{session_id} | control JSON + binary PCM | bidirectional events | Required for live operation. |

### 9.3 Key schemas

~~~json
POST /api/v1/sessions
{
  "language": "hi",
  "profile": "text_v1",
  "wire_profile": "interactive_2",
  "channel": {
    "mode": "bpsk_awgn",
    "gross_rate_bps": 2000,
    "eb_n0_db": 5.0,
    "base_latency_ms": 80,
    "jitter_ms_stddev": 25,
    "reorder_probability": 0.02,
    "seed": 1729
  },
  "tts_voice_id": "demo_hi_female_01",
  "retain_run": false
}

201
{
  "session_id": "uuid",
  "config_hash": "sha256:...",
  "expires_at_ms": 0,
  "warnings": ["TTS output is synthetic and does not preserve speaker identity."]
}
~~~

~~~text
POST /api/v1/transcriptions
Content-Type: multipart/form-data
fields: audio=@utterance.wav; session_id=uuid; language=hi; decoder_mode=ctc
~~~

~~~json
200
{
  "raw_text": "मैं घर पहुँच गया हूँ",
  "normalized_text": "मैं घर पहुँच गया हूँ",
  "language": "hi",
  "asr_confidence": 0.91,
  "segments": [{"start_ms": 0, "end_ms": 1480, "text": "…", "confidence": 0.91}],
  "model": {"id": "ai4bharat/indic-conformer-600m-multilingual", "revision": "pinned"},
  "processing_ms": 742,
  "request_id": "uuid"
}
~~~

Provider confidence is provider-derived or null. It must never be invented.

~~~json
POST /api/v1/frames/encode
{
  "session_id": "uuid",
  "utterance_id": 17,
  "normalized_text": "मैं घर पहुँच गया हूँ",
  "start_ms": 0,
  "duration_ms": 1480,
  "critical_spans": []
}

200
{
  "frame_id": 17,
  "profile": "text_v1",
  "source_payload_bytes": 19,
  "packets": [{"sequence": 12, "kind": "data", "bytes_base64": "…", "payload_bytes": 32}],
  "token_count": 11,
  "tokenizer_hash_prefix": "…",
  "estimated_wire_bits": 528
}
~~~

bytes_base64 exists only in developer/debug response; normal browser transport uses binary events and trace export is bounded to the active local user.

~~~json
202 POST /api/v1/runs
{
  "run_id": "uuid",
  "status": "queued",
  "session_id": "uuid",
  "poll_url": "/api/v1/runs/uuid",
  "events_url": "/api/v1/stream/uuid"
}
~~~

Final RunReport must include measurable fields:

~~~json
{
  "status": "complete",
  "input": {"duration_ms": 1480, "sample_rate_hz": 16000},
  "transcript": {"raw": "…", "normalized": "…", "language": "hi"},
  "transport": {
    "gross_rate_bps": 2000,
    "wire_bits": 752,
    "source_bits": 152,
    "good_bits": 152,
    "actual_wire_bps": 508.1,
    "packet_count": 4,
    "lost_packets": 1,
    "recovered_packets": 1,
    "measured_ber": 0.003,
    "measured_per": 0.25
  },
  "receiver": {"status": "exact", "text": "…", "tts_status": "complete"},
  "latency_ms": {"capture": 1480, "asr": 742, "encode": 12, "queue_tx": 376, "channel": 91, "decode": 4, "tts": 880, "end_to_end": 3585},
  "metrics": {"wer": null, "cer": null, "stoi": null, "pesq": null},
  "warnings": [],
  "reproducibility": {"seed": 1729, "config_hash": "sha256:…", "model_manifest_hash": "sha256:…"}
}
~~~

Null is correct when a reference/evaluator is unavailable.

### 9.4 WebSocket protocol

1. Connect to WS /api/v1/stream/{session_id}.
2. Send control JSON start with language/profile/channel hash and await acknowledgement.
3. Send binary audio: byte type 0x01, uint32 big-endian sequence, uint32 capture timestamp, then PCM16LE 16 kHz mono; max 320 ms/frame.
4. Receive JSON events: vad, partial_transcript, final_transcript, semantic_frame, packet_trace, receiver_frame, tts_started, metric_update, warning, error, complete.
5. Receive output audio type 0x81 with sequence/timestamp and PCM16LE 24 kHz only after complete receiver frame. AudioWorklet schedules playback.
6. Send control finalize or cancel. Cancellation is cooperative; active buffer is deleted before acknowledgement.

Every event contains session_id, optional run_id, event_seq, event_time_ms, and config_hash. Reconnect can request events after event_seq; it cannot recreate discarded raw audio.





## 10. Frontend research-demo design

### 10.1 Design intent

This is an engineering instrument panel, not an AI SaaS landing page. Its purpose is to make an observer see what is captured, what the system decided to transmit, how the channel altered it, what was recovered, and how the measured rate compares with PCM or a selected baseline. Every visible value is driven by a RunReport or event trace.

Desktop layout: three resizable columns with a persistent top status bar and a bottom trace drawer.

~~~text
┌─────────────────────────────────────────────────────────────────────────────┐
│ iTantra | LOCAL / SIMULATED CHANNEL | session | model status | run controls │
├───────────────────┬────────────────────────────┬────────────────────────────┤
│ TRANSMITTER       │ LOW-BITRATE LINK            │ RECEIVER                   │
│ mic / upload      │ rate & channel controls     │ received frames            │
│ waveform + VAD    │ packet timeline             │ reconstructed text          │
│ language / ASR    │ BER, PER, FEC, queue        │ synthetic output audio      │
│ text / tokens     │ latency/jitter/fading       │ confidence / gaps           │
├───────────────────┴────────────────────────────┴────────────────────────────┤
│ Comparison: PCM vs wire rate | metrics | event trace / export               │
└─────────────────────────────────────────────────────────────────────────────┘
~~~

### 10.2 Required frontend components and data binding

| Panel / control | Required UI behavior | Binding / safety rule |
|---|---|---|
| Model/status bar | Show local model loaded, downloaded but unloaded, access required, CPU/GPU provider | Never say ready solely because config has a model name. |
| Microphone/upload | Start/stop/press-to-talk, device selector, 30 s duration meter, WAV upload | Browser permission error includes recovery path. |
| Input waveform | Draw actual PCM and VAD speech/quiet overlay | Use AudioChunk events; do not render stock waveform. |
| Language selector | Manual choices plus “Auto (best effort)” | Manual language freezes router. Auto shows score/uncertain state. |
| ASR card | Raw and normalized transcript, model/revision, confidence/null, processing time | Raw text remains visible so normalization is auditable. |
| Semantic/frame card | Token count, critical-span table, profile badge, source bytes | In VQ mode call it semantic hypothesis; in text_v1 call it exact token stream. |
| Rate selector | 0.5/1/2/4/8/16 kbps gross profiles | Show useful budget and predicted serial time before transmit. |
| Channel controls | Mode, SNR/BER, loss, burst, latency, jitter, reorder, seed | Disable irrelevant controls by mode; show every active value. |
| Packet timeline | Per-packet sequence, data/parity, sent/delivered/lost/CRC fail/recovered, timestamp | Data comes from DeliveryEvent trace; color is redundant with icon/text. |
| Channel metrics | Actual wire/source/good rate, BER, PER, FEC recoveries, queue delay | Null is shown as “not measured,” never 0. |
| Receiver card | Received packets, recovery state, text, missing spans, confidence/warnings | Missing text remains visibly missing; no optimistic fill. |
| Output audio | Audio player, waveform, sample rate, TTS provider/voice label | Label synthetic receiver voice; no claim original speaker. |
| Comparison | PCM 256 kbps versus actual wire rate; selected baseline after evaluation | Use same-duration denominator and say whether result is measured. |
| Trace/export drawer | Ordered events and reproducibility bundle download | Explain export may include transcript; prompt before enabling persistence. |

### 10.3 State machine

~~~text
idle
 -> acquiring_microphone | file_selected
 -> recording
 -> finalizing_utterance
 -> transcribing
 -> normalizing
 -> encoding
 -> transmitting
 -> receiving
 -> reconstructing
 -> synthesizing
 -> complete
 -> error | cancelled
~~~

The UI must not allow Transmit while ASR is unresolved, nor let a changed channel/profile overwrite a frozen SessionConfig of an in-flight run. A config change creates a new session or asks operator to start a new run. A completed run can be inspected, deleted, or exported; it cannot be relabelled with a different bitrate.

### 10.4 Accessibility and visualization constraints

* Include keyboard stop/start, focus order, text equivalents for graphs, high-contrast colors, and a packet-table alternative.
* Do not encode lost/recovered solely by red/green. Use symbols and status strings.
* Numbers use fixed units: bps, ms, dB, percent, bytes. Round for display but preserve raw value in tooltip/export.
* Channel animation follows simulation timestamps. At fast simulation speed allow pause/step-packet mode so judges can see cause and effect.
* Design target is 1440 px wide desktop but it remains usable at 1024 px by stacking transmitter, link, receiver.

## 11. Real-time and streaming operation

### 11.1 MVP: bounded turn-taking

The first shippable experience is push-to-talk/voice-message streaming, not an unsupported claim of full-duplex telephony. Capture 20 ms audio frames, buffer into 320 ms chunks, run VAD continuously, and finalize after 500 ms silence or 3-second maximum segment. Use 200 ms overlap between pseudo-streaming ASR windows; emit stable text only before the overlap.

IndicConformer is used as a segment recognizer initially. Do not claim native token-level streaming unless a provider adapter has a separately tested streaming interface. Pseudo-streaming means later context can revise provisional words; UI marks them provisional until final.

At 0.5/1 kbps queue complete final semantic frames and synthesize only after full recovery. At 2/4 kbps transmit clauses/segments and play previous recovered clauses while the next travels. Never generate later text from a partial fixed-ID token stream.

### 11.2 Latency targets to measure

| Mode | Segment policy | First usable receiver text target | First audio target | Honest constraint |
|---|---|---:|---:|---|
| ultra_0p5 | 2–3 s message, strict token cap | 3–8 s after final speech | 4–10 s | Message-style; serialization dominates. |
| low_1 | 1–2 s phrase | 2–5 s after final speech | 3–7 s | Stress demo, not conversation guarantee. |
| interactive_2 | 1–2 s clause | <2.0 s after final clause target | <3.5 s target | Depends on locally measured ASR/TTS. |
| robust_4 | 0.8–1.5 s clause | <1.2 s target | <2.5 s target | Best live demo lane. |
| research streaming | Provider-specific partial ASR + incremental TTS | Measured only | Measured only | Not MVP acceptance criterion. |

Break down each final report as capture buffering, VAD finalization, ASR, normalizer, source encode, queue/serialization, channel, jitter/FEC, decode, TTS, playback. A single end_to_end number hides the actionable bottleneck.

### 11.3 Asynchronous execution model

* Browser AudioWorklet owns microphone capture and writes bounded PCM frames to WebSocket.
* FastAPI session task owns finite-state machine and ordered events. CPU preprocess/packet/channel work runs in a bounded thread pool; GPU model calls use a per-model async semaphore of one unless batching is benchmarked.
* Queue has max_frames=8 and a byte cap derived from the wire profile. On overflow server sends backpressure; client stops accepting new speech or drops only unfinalized silence, never declared semantic payload.
* Packet send/delivery timestamps derive from gross rate and channel seed. Demo pacing uses async timers; evaluation executes quickly but preserves virtual timestamps.
* TTS runs only on complete receiver frames. Output audio carries source frame ID so browser never plays out of order.



## 12. Evaluation framework and baseline comparison

### 12.1 Measurement rules

Each metric report includes run/config/model hashes; language; source dataset/split; duration; trial seed; numeric value/unit; evaluator version; failure count; and a field saying measured, target, or not applicable. A chart must never mix targets with measurements.

**Semantic-route metrics**

* ASR WER/CER: reference transcript versus transmitter ASR, then transmitter ASR hypothesis versus receiver text. Report both to separate ASR loss from channel/source loss.
* Exact normalized-frame recovery: percentage of fully recovered fixed-ID frames.
* Critical entity accuracy: exact match for numbers, dates, negation, phone/URL/ID spans.
* Semantic similarity: multilingual BERTScore/embedding score only as secondary evidence, with model/revision named.
* TTS intelligibility: transcribe synthesized receiver audio with a held-out evaluator and report WER against receiver text; optionally use a blinded human intelligibility/MOS protocol.
* Transport: actual wire/source/good bps, header/FEC overhead, BER, PER, frame recovery, queue delay, goodput.
* System: CPU/GPU utilization, peak RAM/VRAM, model load time, real-time factor, and stage latency.

**Acoustic-baseline-only metrics**

* SI-SDR/STOI and, where licensing/evaluator conditions permit, PESQ.
* Human MOS/MUSHRA with the same participant protocol.
* These are not primary text_v1 success metrics because TTS deliberately has different speaker/prosody.

### 12.2 Evaluation matrix

Run a small smoke matrix first, then a reproducible benchmark matrix. Do not launch a huge Cartesian product blindly.

| Dimension | Smoke suite | Benchmark suite |
|---|---|---|
| Languages | hi, ta, en | all ten required languages |
| Utterances | 20/language, speaker-disjoint | at least 100/language, source-separated and documented |
| Gross rates | 1, 2, 4 kbps | 0.5, 1, 2, 4, 8, 16 kbps |
| Channel type | clean, BPSK AWGN | clean, AWGN, Rayleigh, Gilbert–Elliott |
| Eb/N0 | 0, 5, 10 dB | -5, 0, 5, 10, 20 dB |
| Packet loss | 0%, 5% | 0%, 1%, 5%, 10%, 20% |
| Profiles | text_v1 | text_v1, semantic_vq variants, audio-codec baselines |
| Seeds | 3 fixed | at least 5 fixed seeds/stochastic condition |
| Output | CI pass/fail and trace | CSV/Parquet, confidence intervals, failures, reproducibility bundle |

Benchmark records audio duration and source code size. Use macro-average by language and per-language values; do not hide a low-resource failure in a pooled mean.

### 12.3 Required results tables (blank until experiments run)

| System | Language | Gross bps | Actual wire bps | Channel | WER transmitter | WER receiver | Entity accuracy | Frame recovery | E2E p50 / p95 ms | Status |
|---|---|---:|---:|---|---:|---:|---:|---:|---|---|
| Raw PCM | — | — | — | — | — | — | — | — | — | To measure |
| Opus | — | — | — | — | — | — | — | — | — | To measure |
| EnCodec | — | — | — | — | — | — | — | — | — | To measure |
| text_v1 | — | — | — | — | — | — | — | — | — | To measure |
| semantic_vq_v1 | — | — | — | — | — | — | — | — | — | To measure |

| System | Language | Source / split | SI-SDR | STOI | PESQ if allowed | Human MOS | TTS intelligibility WER | Status |
|---|---|---|---:|---:|---:|---:|---:|---|
| Opus | — | — | — | — | — | — | — | To measure |
| EnCodec/DAC | — | — | — | — | — | — | — | To measure |
| text_v1 + TTS | — | — | N/A | N/A | N/A | — | — | To measure |
| semantic_vq_v1 + TTS | — | — | N/A | N/A | N/A | — | — | To measure |

### 12.4 Baselines and fair comparison

| Baseline | Required configuration | What it answers | Fairness rule |
|---|---|---|---|
| Raw PCM | 16 kHz mono 16-bit, same packet/channel wrapper where practical | Uncompressed source/rate reference | Report 256 kbps plus transport overhead. |
| Opus | libopus, 20 ms frames, 6/8/16 kbps source modes | Conventional real-time speech/audio codec | Include same packet/FEC/channel assumptions; Opus is standardized for interactive audio.[^11] |
| AMR/AMR-WB | Optional external/legal tool at documented standard modes | Cellular speech-codec reference | Do not call it a 0.5–4 kbps baseline; document tool/licence and feasible modes. |
| EnCodec | 24 kHz causal, 1.5/3/6/12 kbps source modes | Neural audio codec baseline | EnCodec notes weak official Windows support; run in WSL2/Linux container if needed and count transport bits.[^9] |
| DAC | Published 8/16 kbps modes | Alternative neural audio-codec point | Same source/channel boundaries as EnCodec.[^10] |
| Text-only no protection | Same normalized token payload, no FEC | Quantifies transport-protection benefit | Never use as headline system in loss condition. |
| iTantra text_v1 | Exact IDs + coding/FEC/simulation | Buildable semantic system | Compare semantic metrics, not waveform fidelity. |
| iTantra semantic_vq_v1 | VQ IDs + anchors + coding/FEC | Learned semantic benefit over exact text | Report paraphrase/entity failures and rate budget. |

No baseline gets a cleaner channel, different reference duration, or omitted headers/FEC in the wire-rate graph. For audio codecs, a CRC failure becomes an erasure/PLC event under the same wrapper; report PLC behavior separately.

## 13. Judge-facing 2–3 minute demo

1. **0:00–0:20 — establish source.** Select Hindi, interactive_2, BPSK AWGN 5 dB, and 5% packet loss. Record a short natural sentence containing a time or number. Show real waveform, VAD, raw ASR, normalized transcript, and critical-span highlight.
2. **0:20–0:50 — show what travels.** Press Transmit. Animate actual token/packet bytes, gross 2 kbps budget, serial queue, data/parity, and channel seed. Contrast 256 kbps input PCM with *actual measured* iTantra wire rate. Say that the system is semantic text-to-new-TTS speech.
3. **0:50–1:20 — impairment and recovery.** Highlight a lost packet and its CRC/FEC recovery. Show received packets, recovered exact text, and synthetic receiver audio. If a planned stress case fails, show honest partial/unrecoverable state rather than scripted success.
4. **1:20–1:50 — stress comparison.** Switch to a short 0.5 or 1 kbps message and explain serial delay. Then show an audio-codec baseline lane at feasible 8 kbps, clearly labelled as a baseline and only with measured metrics.
5. **1:50–2:20 — multilingual switch.** Repeat a short Tamil or Bengali phrase. Show changed ASR/TTS providers and native-script text.
6. **2:20–2:45 — research extension.** Toggle semantic_vq_v1 only if checkpoint and measured results are installed. Explain semantic—not verbatim—reconstruction and show anchor protection/uncertainty.
7. **2:45–3:00 — evidence.** Export config/model/seed trace and show evaluation table with measured cells only.

Prepare an offline demo bundle before judging: all model weights, consented prompts, three licensed fixture WAVs, pinned config, a clean environment/container image, and a pre-run doctor report. The live demo must work without internet once this bundle is prepared.



## 14. Development roadmap for the implementation agent

Every phase ends with a working branch, automated tests, a short verification record in docs/verification, and an explicit go/no-go checkpoint. Do not start a later phase by stubbing a missing earlier phase.

| Phase | Files to create / modify | Implementation and dependencies | Tests | Acceptance criteria / expected output |
|---|---|---|---|---|
| 1. Project setup | pyproject.toml, package.json, README.md, .env.example, scripts/bootstrap.ps1, scripts/doctor.ps1, docker skeleton, CI config | Python 3.11, React/TS/Vite scaffold, lint/format/test commands, config loader, structured logging | import smoke, web build, doctor unit test | One PowerShell bootstrap produces a healthy CPU development environment; UI and API health pages run locally. |
| 2. Audio + STT | engine/audio, engine/stt, LanguageRegistry, POST /transcriptions, fixtures | torchaudio/soundfile, VAD, IndicConformer adapter, faster-whisper adapter | PCM/resample tests, VAD fixture, provider contract test, hi/ta/en smoke transcription | User records/uploads audio, gets raw/normalized text and actual model metadata; no fabricated confidence. |
| 3. TTS | engine/tts, models/manifest.yaml, POST /synthesis, consent records | IndicF5 provider, Piper provider, local model manager | manifest checksum test, prompt-consent refusal test, WAV schema test | Nine Indic routes and English fallback show truthful availability; synthesized WAV plays locally or explicit unavailable error appears. |
| 4. Text/semantic codec | engine/text, semantic/schema.py, text_codec.py, protocol spec/vectors | NFC normalizer, SentencePiece trainer/model, uint14 packer | 10,000 property/fuzz round trips, Unicode fixtures, tokenizer mismatch test | Fixed-ID SemanticFrame round trips exactly; literals/critical spans remain byte exact. |
| 5. Channel/transceiver MVP | transport/packet.py, fec.py, session.py, channel/simulator.py, API encode/transmit/decode | CRC, scrambler, convolutional/Viterbi, XOR parity, BPSK/AWGN/Rayleigh/GE | golden vectors, BER sanity curve, CRC corruption rejection, reorder/loss fuzz | Real packet trace shows loss/CRC/recovery; recovered text or honest erasure; actual rate fields exist. |
| 6. Neural semantic profile | training/model/vq files, semantic/vq.py, profile config | PyTorch Transformer/VQ, controlled text dataset, profile/channel masks | tensor shape/gradient test, checkpoint load, rate profile test, anchor failure test | semantic_vq_v1 is behind flag; emits finite IDs, runs a real checkpoint, never claims exact recovery. |
| 7. End-to-end orchestration | orchestration/run.py, POST /runs, artifact manager, RunReport | async queues, cancellation, telemetry, model semaphores | 3-language end-to-end fixture, deletion/cancel test, stage-time accounting | Audio → STT → packet/channel → text → TTS works without manual glue; report is reproducible. |
| 8. Research-demo frontend | all apps/web features, AudioWorklet, typed API/WS client | React/Vite, Canvas/SVG, browser media APIs | component tests, Playwright mic/upload mock, packet trace UI test | Every transmitter/channel/receiver control works and values come from server trace; no placeholder panels. |
| 9. Evaluation/baselines | evaluation suites/baselines/reports, matrix configs | jiwer/Unicode CER, resource sampler, libopus integration; EnCodec in separate optional environment | deterministic matrix resume, report schema, baseline-rate accounting test | A small 3-language smoke matrix produces empty-or-measured tables, artifacts, and no invented cells. |
| 10. Optimization/release | benchmark scripts, Docker Compose profiles, demo bundle, docs | model warmup, CPU/GPU profile, queue tuning, offline bundle | cold/warm start, offline-network test, load/cancel test, full regression | Rehearsed 2–3 minute demo works offline after asset download; README/doctor/known-limitations are complete. |

### Phase gates and stop conditions

* Phase 2 stops if selected-model access or Windows dependency is unresolved; use documented unavailable state rather than substituting a cloud API.
* Phase 4 stops if any corrupted packet can generate a text decode instead of an erasure.
* Phase 5 stops if on-air rate excludes a header, parity, or serial-time component.
* Phase 6 is optional. It stops if it does not beat, or clearly characterize tradeoffs against, text_v1 on held-out data.
* Phase 9 stops claims of superiority until the same channel wrapper, duration, and rate accounting are used for every baseline.

## MASTER INSTRUCTIONS FOR GOOGLE ANTIGRAVITY

Read this complete specification before coding. Treat it as the architecture contract. Implement in the roadmap order and verify each phase before starting the next one.

1. Build the MVP as a transcript-first, locally runnable semantic transceiver. Do not call it DeepJSCC or a learned physical-layer radio.
2. Do not change model choices, packet fields, rate definitions, or success semantics without documenting the rationale and updating protocol tests, configuration, and this specification.
3. Never create fake AI functionality. Core buttons must invoke real capture, ASR, source coding, channel simulation, decode, TTS, or an explicit unavailable/error state.
4. Never fabricate ASR text, synthesized audio, packet traces, model availability, latency, quality metrics, compression ratios, or benchmark results.
5. Use real open-source/local models only where the manifest says they are allowed. Respect Hugging Face gates and model licences; do not bypass them.
6. Make local CPU operation functional where possible. Make GPU acceleration optional and automatically detected. Clearly label operations that are too slow for real-time CPU use.
7. Keep all model access behind provider interfaces. Pin revisions, record checksums and licence/access state in models/manifest.yaml, and do not set trust_remote_code except for an explicit approved manifest entry.
8. Keep original input waveform, ASR raw text, normalized text, semantic frame, packet bytes, receiver text, and TTS output as distinct typed objects. Do not overwrite one with another.
9. Implement text_v1 completely before neural VQ. Make uint14 token packing and protocol golden vectors correct before adding entropy coding or neural compression.
10. On every CRC/FEC failure, produce an erasure/partial state. Do not use a language model or heuristic to silently fill missing exact text.
11. If semantic_vq_v1 is implemented, label it semantic reconstruction, preserve critical literals by an explicit protected lane, expose confidence, and abstain when uncertain.
12. The frontend is a serious research instrument. Bind every control and graph to real APIs/events. Do not make a generic landing page or placeholder dashboard.
13. Save actual rate components separately: source, protected/on-air, goodput, headers, FEC, queue/serialization, and duration. Never show source bitrate as radio bitrate.
14. Label all channel impairment metrics as simulated. Record seed, mode, SNR/BER/loss, FEC profile, and virtual timestamp trace in every RunReport.
15. Add structured logging with redaction. Do not log raw PCM, full transcript, tokens, access tokens, or prompt file paths at INFO level.
16. Add unit, protocol, integration, e2e, and regression tests as features arrive. Any model-dependent test must have a small fake-provider equivalent so CI can run without weights.
17. Use configuration files for language, models, voices, normalizer/tokenizer, profile, bitrate, SNR, loss, latency, data split, and evaluation matrix. Never hard-code them inside UI components.
18. Add graceful error handling for model not installed, model access not accepted, unsupported language, microphone permission, upload type, GPU OOM, tokenizer mismatch, corrupt packets, and TTS unavailable.
19. Store user audio/transcripts only in ephemeral per-run directories by default. Delete them on completion/cancellation. Make retention/export opt-in and visible.
20. Provide PowerShell setup scripts, .env.example, README, Docker support once native local operation works, and a doctor command.
21. Before asserting a model supports a language, read its pinned official model card. Include a provider capability matrix in GET /capabilities and UI.
22. Use measurements, not invented claims, in evaluation. Keep target numbers, unmeasured values, and measured values visibly distinct.
23. Keep commits/phases small and reviewable. At each phase write one short verification record: command run, environment, result, remaining known limitation.
24. If a requirement is blocked by model access, hardware, or legal data terms, stop that branch cleanly, surface the precise blocker, and continue with the documented fallback. Do not conceal the gap.



## 15. Windows-first setup and exact operating commands

These are post-scaffold commands for the repository described above. Run them in PowerShell from the iTantra repository root. The Python executables are used directly so execution-policy restrictions do not prevent a virtual-environment activation script.

### 15.1 Bootstrap and install

~~~powershell
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install --upgrade pip wheel
.\.venv\Scripts\python.exe -m pip install torch torchaudio --index-url https://download.pytorch.org/whl/cpu
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
npm --prefix .\apps\web ci
Copy-Item .env.example .env
.\scripts\doctor.ps1
~~~

For an NVIDIA GPU, the implementation agent must put the **currently tested** Windows CUDA wheel command printed by the PyTorch official selector into scripts/bootstrap.ps1 and requirements-lock.txt. Do not guess a CUDA index. Verify it with:

~~~powershell
.\.venv\Scripts\python.exe -c "import torch; print(torch.__version__); print(torch.cuda.is_available()); print(torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'CPU')"
~~~

### 15.2 Model download

First open the official pages for IndicConformer and IndicF5, accept any applicable access conditions, and authenticate once:

~~~powershell
.\.venv\Scripts\hf.exe auth login
.\scripts\download_models.ps1 -Profile mvp
.\scripts\doctor.ps1 -CheckModels
~~~

The profile script must download only manifest-pinned repositories/revisions to ITANTRA_MODEL_DIR, verify checksums, and print a clear license/access error if unavailable. It must not download English/Indic alternatives “just in case.”

For a developer who needs explicit direct commands after access is accepted:

~~~powershell
.\.venv\Scripts\hf.exe download ai4bharat/indic-conformer-600m-multilingual --local-dir .\models\cache\indic-conformer
.\.venv\Scripts\hf.exe download ai4bharat/IndicF5 --local-dir .\models\cache\indicf5
~~~

The manifest, not a hard-coded directory name, decides what the application loads.

### 15.3 Dataset and tokenizer preparation

~~~powershell
.\.venv\Scripts\python.exe -m training.scripts.fetch_data --config .\configs\train\data_smoke_hi_ta_en.yaml
.\.venv\Scripts\python.exe -m training.scripts.build_manifests --config .\configs\train\data_smoke_hi_ta_en.yaml
.\.venv\Scripts\python.exe -m training.scripts.train_tokenizer --config .\configs\train\tokenizer_indic_en.yaml
.\.venv\Scripts\python.exe -m training.scripts.verify_splits --config .\configs\train\data_smoke_hi_ta_en.yaml
~~~

Each config contains exact source revision/license acknowledgement, selected language, output directory, and split seed. The fetch command must refuse a source lacking a completed metadata entry.

### 15.4 Run backend, frontend, and complete local demo

In terminal one:

~~~powershell
.\.venv\Scripts\python.exe -m uvicorn itantra_api.main:app --host 127.0.0.1 --port 8000 --reload
~~~

In terminal two:

~~~powershell
npm --prefix .\apps\web run dev -- --host 127.0.0.1 --port 5173
~~~

Run one scripted complete demo:

~~~powershell
.\.venv\Scripts\python.exe -m scripts.run_demo --config .\configs\app.local.yaml --fixture .\tests\fixtures\hi_short.wav --profile interactive_2
~~~

### 15.5 Training, evaluation, and tests

~~~powershell
.\.venv\Scripts\python.exe -m training.scripts.train_semantic_vq --config .\configs\train\semantic_vq_64.yaml
.\.venv\Scripts\python.exe -m evaluation.scripts.run_matrix --config .\configs\eval\smoke_3lang.yaml
.\.venv\Scripts\python.exe -m pytest
npm --prefix .\apps\web run test
npm --prefix .\apps\web run build
~~~

Run protocol fuzzing separately in CI and locally:

~~~powershell
.\.venv\Scripts\python.exe -m pytest .\tests\protocol -q
.\.venv\Scripts\python.exe -m pytest .\tests\integration -q
.\.venv\Scripts\python.exe -m pytest .\tests\e2e -q
~~~

### 15.6 Containers

Docker is optional until native local operation passes:

~~~powershell
docker compose --profile cpu up --build
docker compose --profile cpu down --volumes
~~~

A GPU compose profile is permitted only when Docker Desktop/WSL2 GPU integration passes doctor checks:

~~~powershell
docker compose --profile gpu up --build
~~~

EnCodec baseline execution is allowed in a Linux/WSL2 profile because its official repository does not strongly support Windows.[^9] It is an optional evaluation lane, never a dependency of core text_v1.

## 16. Hardware requirements and offline-first operation

| Profile | CPU / RAM | GPU / VRAM | Disk | What it can honestly do |
|---|---|---|---|---|
| Minimum functional MVP | Modern 4-core CPU, 16 GB RAM | None | 50 GB free after chosen models | Text codec, channel, API/UI/tests, short segment ASR/TTS. Model inference may be slow; no real-time promise. |
| Recommended live demo | 8+ CPU cores, 32 GB RAM | NVIDIA CUDA GPU, 12 GB VRAM | 100 GB SSD | Warm local IndicConformer/IndicF5, 2–4 kbps demo, browser + backend, trace visualization. |
| Advanced VQ training | 12–16 cores, 64 GB RAM | 24 GB VRAM | 300–500 GB SSD | VQ Transformer training and substantial matrix evaluation. |
| Foundation-model tuning research | 24+ cores, 128 GB RAM | At least 48 GB VRAM or multi-GPU | 1 TB SSD | Separate ASR/TTS adaptation project; not an MVP requirement. |

CPU can always run protocol/channel/normalization/evaluation logic. CPU can attempt model inference but cannot be advertised as real-time until the exact model/machine benchmark is recorded. Use model warmup at startup and report cold versus warm latency.

Offline-first release procedure:

1. On a connected staging machine, accept model terms, download manifest-pinned weights, verify checksums, download selected licensed datasets/fixtures, and build frontend/API images.
2. Run scripts/create_demo_bundle.ps1 to copy only required weights, consented prompts, config, tokenizer, fixtures, hashes, and software locks to a portable bundle.
3. Disconnect networking and run doctor, automated end-to-end fixture, and the live UI. Any failed asset lookup is a release blocker.
4. The app uses local file cache only. The UI tells operator “offline bundle” and must not silently switch to external API.

## 17. Security and privacy requirements

| Area | Required control |
|---|---|
| Audio lifecycle | Store uploads/capture chunks only in a random per-run temporary directory with restrictive permissions; delete on success, cancel, session expiry, or DELETE endpoint. |
| Retention | Default retain_run=false. Export/retention needs explicit UI confirmation that transcript/trace may be included; use a user-selected local directory. |
| Upload validation | Enforce max duration/bytes/sample rate/channels; MIME sniff and decode safely; reject zip/path-traversal content; never trust filename or extension. |
| API exposure | Bind to 127.0.0.1 by default. If remote access is intentionally enabled, require TLS, authentication, allow-listed origins, rate limits, and CSRF/origin controls. |
| Logging | Redact transcript, token IDs, raw PCM, voice prompt paths, authorization header, and Hugging Face token. Logging default contains run ID and counts only. |
| Model supply chain | Pin repo revision and hash. Use an allow list for remote-code models. Record source/licence. Run model download in a separate script, not inside arbitrary request handling. |
| TTS voice safety | Only locally configured, documented-consent reference prompts. No upload-a-voice and clone-it endpoint in MVP. |
| Artifact URLs | Use opaque run IDs, expiry, authorization where remote; no user-provided path or direct static serving of data directory. |
| Input text | Escape text in UI; do not pass it to shell or build shell commands. Use argument arrays for subprocesses if any codec helper is added. |
| Reproducibility | Hash configs/models/data manifests while redacting private content. This enables audit without preserving voice. |

## 18. Common implementation failures: symptom → cause → solution

| Symptom | Likely cause | Required solution |
|---|---|---|
| Indic ASR fails to load | Model gate not accepted, remote-code dependency/version mismatch | Show MODEL_UNAVAILABLE; use manifest-pinned install instructions; do not replace with fake transcript. |
| English route gives blank/poor text | IndicConformer was called for English or language router misrouted | Send English to faster-whisper; add provider-selection contract test. |
| GPU OOM | ASR/TTS loaded at once in FP32, long clip, no semaphore | Lazy load, shorten segments, use allowed reduced precision, one model call at a time; measure. |
| CPU demo is slow | 600M ASR/TTS foundation model on CPU | Mark CPU mode as functional/slow; use short PTT segments; recommend GPU, do not lie about RTF. |
| Browser mic not available | Insecure context, denied permission, AudioWorklet unsupported | Bind localhost/HTTPS, show permission steps, provide WAV upload fallback. |
| Waveform works but server gets silence | Float32/PCM conversion or sample-rate channel mismatch | Test known sine fixture; assert sample rate/channels/peak/RMS at WebSocket boundary. |
| Hindi/Marathi wrong route in auto mode | Both use Devanagari; script detection cannot identify spoken language | Prefer manual selection; require LID confidence/confirmation. |
| Unicode text changes after transfer | NFD/NFC mismatch or lossy transliteration | NFC normalizer ID in session; native-script round-trip tests; never transliterate wire text. |
| Tokenizer mismatch gives readable but wrong text | Sender/receiver loaded different model | HELLO tokenizer hash check; reject session before frame encode. |
| One bit error corrupts a whole message | Decoder sees corrupt packed IDs or entropy stream | CRC first, bounded fixed-ID frames, treat failure as erasure; no decoder before integrity check. |
| FEC appears to recover too many losses | Test accidentally assumes packets are perfect or block IDs collide | Golden vectors for exactly 0/1/2 erasures and checksum trace; verify block grouping. |
| Claimed bitrate is too low | UI counts only token payload, ignores headers/FEC/time | Compute from transmitted on-air bits and input duration; expose all components. |
| SNR slider has no visible causal effect | It only changes a label or skips modulation | Unit-test BER/SNR curve and show measured BER/PER. |
| Packet reordering breaks text | Receiver assumes arrival order is sequence order | Jitter/reassembly buffer keyed by sequence/frame; timed-out packets are erasures. |
| TTS output plays wrong language | Provider registry/default voice leaks across session | Freeze language/voice in session and assert output language metadata. |
| TTS sounds like user without consent | Prompt/audio upload misuse | No untrusted prompt input; consented local prompt registry only. |
| VQ decoder invents a number | Semantic loss lacks literal/negation weights | Mandatory protected anchor lane; abstain if anchor missing; entity accuracy gate. |
| Text VQ looks good in a cherry-picked demo only | Train/test leakage or no impairment sweep | Speaker/source-disjoint splits; fixed eval matrix; report per-language/failure cases. |
| EnCodec fails on Windows | Official support is weak or dependencies differ | Keep baseline in WSL2/Linux container; do not block MVP. |
| Docker differs from native | Model cache/path/GPU mount mismatch | doctor runs in both; bind explicit model directory; test offline image. |
| Metrics show zero rather than missing | UI coercion of null/NaN | Strong type schema and null-safe renderer; “not measured” label. |
| Latency grows indefinitely | Packet queue has no bound/backpressure | Cap queue, emit backpressure, segment utterances, report serial delay. |
| Windows PowerShell scripts fail | Execution policy/path quoting | Directly invoke .venv Python; quote paths; make scripts idempotent and add doctor diagnostics. |



## 19. MVP, advanced, and research-grade scope

### 19.1 MVP acceptance scope

The MVP must demonstrate the real chain:

~~~text
speech -> STT -> normalized text/token compression -> simulated low-rate channel
       -> packet/FEC recovery -> text reconstruction -> TTS
~~~

It supports **Hindi, Tamil, and English** first. Hindi/Tamil use configured IndicF5 only after legal/model access and consented local prompt availability; English uses Piper fallback. If a model is unavailable, that language is visibly unavailable, not simulated.

MVP includes:

* microphone and WAV upload; VAD; selected-language ASR;
* native-script raw/normalized transcript;
* text_v1 fixed 14-bit SentencePiece source codec;
* ITP/1 packet trace, CRC, convolutional code/Viterbi, XOR parity, abstract/BPSK AWGN/loss/jitter/reorder channel;
* 0.5/1/2/4/8/16 kbps profiles with actual on-air accounting;
* packet recovery/erasure visibility and synthetic TTS receiver audio;
* a 3-language fixture test and smoke evaluation;
* run seed/config/model hashes, deletion, offline demo bundle;
* no neural VQ, continuous DeepJSCC, cloud API, speaker cloning input, fabricated metrics, or unimplemented UI button.

MVP is done only when a new clean machine following README/doctor can execute the scripted fixture run and an operator can perform the live three-language demo offline after installing assets.

### 19.2 Advanced scope

Add only after MVP is accepted:

* complete ten-language registry (Hindi, English, Bengali, Tamil, Telugu, Marathi, Gujarati, Kannada, Malayalam, Punjabi) with explicit provider availability;
* semantic_vq_v1 with real trained checkpoint, VQ profiles, anchors, semantic-mode UI, and held-out evaluation;
* fully featured packet-loss/burst/fading matrix, stronger RS/RaptorQ transport profile after golden vectors;
* pseudo-streaming clause pipeline and real queue/back-pressure visualization;
* baseline container profiles for Opus, EnCodec, DAC, and legally feasible AMR/AMR-WB;
* model quantization/ONNX experiments only if they preserve documented model behavior;
* optional open-source multilingual TTS experiment where its actual card supports the needed language;
* experiment registry and HTML/CSV report generation.

### 19.3 Research-grade scope

A serious research contribution requires more than integration:

* a formal task definition distinguishing exact-text reliability from semantic fidelity;
* a multilingual, code-switched, speaker-disjoint benchmark with published licensing/data documentation;
* a trained channel-conditioned semantic VQ architecture and ablations for anchor lane, rate conditioning, FEC, language conditioning, and channel distribution;
* rigorous baselines under identical on-air budgets and packet/channel rules;
* low-SNR, burst-loss, and unequal-error-protection experiments across all target languages;
* confidence calibration/abstention evaluation, especially for numbers/negations/named entities;
* human intelligibility/meaning-preservation protocol with pre-registered analysis where practical;
* significance intervals, failure taxonomy, artifacts/checkpoints/configs, and a reproducibility release;
* only then, an optional hardware radio testbed with specified waveform, modulation, synchronization, power, bandwidth, antenna/channel conditions, and compliance review.

## 20. Novelty and research contribution: honest assessment

The broad idea is **not established as novel by itself**. Semantic speech communication, text-related speech features, speech recognition/synthesis semantic links, and neural joint source-channel coding already have published precedents.[^12][^13][^14][^15] Combining STT, compressed text, a simulated channel, and TTS is a useful demonstrator but not automatically a research contribution.

Potential hypotheses that could become contributions if evidence supports them:

| Hypothesis | What would make it credible | Required comparison |
|---|---|---|
| Indian-language-aware semantic VQ improves semantic reliability at a fixed on-air budget | Per-language held-out gains with confidence intervals, native-script/code-switch cases | Exact token text_v1, generic multilingual VQ, audio-codec baselines. |
| Protected literal/negation lane materially reduces harmful semantic errors | Ablation showing entity/negation recovery gain at a stated rate cost | VQ with/without anchor lane under same loss/burst channel. |
| Channel-conditioned rate selection improves useful goodput | Controller choices and success/latency improvement over fixed profiles | Fixed 1/2/4 kbps profiles and same channel seeds. |
| Multilingual shared representation aids low-resource Indic languages | Transfer benefit on restricted-data languages without harming high-resource ones | Monolingual versus shared model, same data budget. |
| Learned semantic link degrades more gracefully under burst loss | Curves of semantic/entity accuracy versus loss/SNR and qualitative failure audit | Exact text with FEC, audio codecs, different FEC strength. |

Do not claim any hypothesis until its experiment runs. A good null result—text_v1 is more reliable at all practical budgets—is still a valuable engineering conclusion and a stronger demo than a fabricated “AI compression” result.

## 21. Final recommended architecture, stack, and model choices

| Deliverable | Final decision |
|---|---|
| Recommended architecture | Hybrid semantic speech transceiver: local ASR/TTS around exact normalized text packets, conventional coding/FEC/modulation/channel simulation; advanced neural VQ behind same protocol. |
| MVP model choices | IndicConformer 600M CTC for selected Indic language; faster-whisper multilingual small for English/auto-LID; IndicF5 for nine required Indic TTS languages; Piper en_US-amy-medium for generic English. |
| Technology stack | Python/FastAPI/Pydantic/PyTorch/torchaudio/SentencePiece/NumPy; React/TypeScript/Vite/Web Audio; ephemeral filesystem and optional SQLite; Docker Compose. |
| What travels | Versioned language/text-token frame as uint14 IDs, typed literal escapes, packet header/CRC/FEC/modulation bits—not audio or opaque embedding. |
| Target default | interactive_2 at 2 kbps gross. Actual wire/source/good rate and serial delay decide whether a run succeeds. |
| Training pipeline | No ASR/TTS training in MVP; train tokenizer then VQ semantic codec on legal multilingual text with profile/channel masks; freeze/benchmark foundation models. |
| Evaluation pipeline | Separate ASR, text/source, transport, TTS, acoustic baseline, and system metrics; same on-air accounting/channel wrapper; blank result tables until measured. |
| Risks | Model gates, Windows/GPU dependency, TTS prompt permission, actual ASR/TTS latency, low-rate queueing, VQ hallucination, invalid cross-baseline bitrate claims. |
| Research opportunity | Indian multilingual/code-switched semantic communication with protected critical literals and channel-aware rate adaptation, subject to controlled evidence. |

## Sources

[^1]: AI4Bharat, [IndicConformer 600M Multilingual model card](https://huggingface.co/ai4bharat/indic-conformer-600m-multilingual), accessed 2026-09-09. Documents 600M Conformer hybrid CTC+RNNT, 16 kHz inference interface, IN-22 coverage, and MIT licence.

[^2]: AI4Bharat, [IndicF5 model card](https://huggingface.co/ai4bharat/IndicF5), accessed 2026-09-09. Documents the 11 supported Indian languages, prompt-audio requirement, 24 kHz output, MIT licence, and access condition.

[^3]: AI4Bharat, [Indic Parler-TTS model card](https://huggingface.co/ai4bharat/indic-parler-tts), accessed 2026-09-09. Documents 20 official Indic languages plus English and labels Punjabi as unofficial.

[^4]: AI4Bharat, [IndicVoices-R repository](https://github.com/AI4Bharat/IndicVoices-R) and [paper](https://arxiv.org/abs/2409.05356), accessed 2026-09-09. Documents 1,704 hours, 10,496 speakers, and 22 Indian languages.

[^5]: SPRING Lab, IIT Madras, [SPRING-INX: A Multilingual Indian Language Speech Corpus](https://arxiv.org/abs/2310.14654), accessed 2026-09-09.

[^6]: Mozilla, [Common Voice datasets](https://commonvoice.mozilla.org/os/datasets), accessed 2026-09-09. Check exact locale/release/licence before use.

[^7]: OpenAI, [Whisper repository](https://github.com/openai/whisper), accessed 2026-09-09. Documents multilingual recognition and language identification.

[^8]: SYSTRAN, [faster-whisper repository](https://github.com/SYSTRAN/faster-whisper), accessed 2026-09-09. Documents CTranslate2 implementation, CPU INT8 and GPU modes.

[^9]: Meta/Facebook Research, [EnCodec repository](https://github.com/facebookresearch/encodec) and [High Fidelity Neural Audio Compression](https://arxiv.org/abs/2210.13438), accessed 2026-09-09. Documents 24 kHz causal 1.5/3/6/12/24 kbps modes and Windows support caveat.

[^10]: Descript, [Descript Audio Codec repository](https://github.com/descriptinc/descript-audio-codec) and [paper](https://arxiv.org/abs/2306.06546), accessed 2026-09-09.

[^11]: IETF, [RFC 6716: Definition of the Opus Audio Codec](https://www.rfc-editor.org/rfc/rfc6716), September 2012.

[^12]: Weng, Qin, and Li, [Semantic Communications for Speech Recognition](https://arxiv.org/abs/2107.11190), 2021.

[^13]: Bourtsoulatze, Kurka, and Gündüz, [Deep Joint Source-Channel Coding for Wireless Image Transmission](https://arxiv.org/abs/1809.01733), 2018.

[^14]: Weng, Qin, and Li, [Semantic Communications for Speech Signals](https://arxiv.org/abs/2012.05369), 2021; code: [DeepSC-S](https://github.com/Zhenzi-Weng/DeepSC-S).

[^15]: Weng et al., [Deep Learning Enabled Semantic Communications with Speech Recognition and Synthesis](https://arxiv.org/abs/2205.04603), 2022.

[^16]: Rhasspy, [Piper English US Amy medium voice artifact](https://huggingface.co/rhasspy/piper-voices/tree/main/en/en_US/amy/medium), accessed 2026-09-09.

[^17]: Zhang et al., [SpeechTokenizer](https://arxiv.org/abs/2308.16692) and [configuration](https://github.com/ZhangXInFD/SpeechTokenizer/blob/main/config/spt_base_cfg.json), accessed 2026-09-09.

[^18]: Vite, [Getting Started guide](https://vite.dev/guide/), accessed 2026-09-09.

[^19]: PyTorch, [Start Locally](https://pytorch.org/get-started/locally/), accessed 2026-09-09.

[^20]: Zeghidour et al., [SoundStream: An End-to-End Neural Audio Codec](https://arxiv.org/abs/2107.03312), 2021.

## Final architecture diagram

~~~mermaid
flowchart TB
  subgraph FE["Frontend — React / TypeScript / Web Audio"]
    U[User]
    MIC[Microphone or WAV upload]
    TXUI[Transmitter panel]
    CHUI[Channel visualization]
    RXUI[Receiver & comparison panel]
    U --> MIC --> TXUI
    CHUI --> RXUI --> U
  end

  subgraph API["Local backend — FastAPI orchestration"]
    PRE[PCM validation / mono 16 kHz / VAD]
    LID[Manual language select or cautious LID]
    STT[ASR provider router]
    NORM[NFC normalization + critical-span extraction]
    FRAME[Versioned SemanticFrame]
    ENCODE{Profile}
    TEXT[Text_v1<br/>SentencePiece uint14 IDs]
    VQ[Semantic_vq_v1<br/>VQ indices + anchors]
    PKT[ITP/1 packetizer<br/>CRC + coding/FEC]
    CH[Low-bitrate channel simulator<br/>loss / AWGN / fading / jitter / reorder]
    RXPKT[CRC / reorder / FEC recovery]
    DECODE{Profile decoder}
    TEXTD[Exact token decode]
    VQD[Semantic VQ decode<br/>confidence / abstention]
    TTS[TTS provider router]
    REPORT[RunReport / packet trace / metrics]
  end

  subgraph MODELS["Local model services / pinned manifest"]
    IC[IndicConformer IN-22]
    FW[faster-whisper English / LID]
    IF5[IndicF5 Indic TTS]
    PIPER[Piper English fallback]
    VQM[Optional VQ checkpoint]
  end

  subgraph STORE["Local ephemeral storage"]
    TMP[Per-run temporary audio/artifacts]
    MAN[Model / tokenizer / config manifests]
    OPTDB[Optional SQLite experiment metadata]
  end

  subgraph EVAL["Offline evaluation pipeline"]
    DATA[Licensed data manifests / speaker splits]
    BASE[PCM / Opus / AMR optional / EnCodec / DAC baselines]
    MATRIX[Language × rate × channel matrix]
    RESULTS[CSV / Parquet / report]
  end

  MIC --> PRE --> LID --> STT --> NORM --> FRAME --> ENCODE
  ENCODE --> TEXT --> PKT
  ENCODE --> VQ --> PKT
  PKT --> CH --> RXPKT --> DECODE
  DECODE --> TEXTD --> TTS
  DECODE --> VQD --> TTS
  TTS --> RXUI
  STT -. uses .-> IC
  STT -. uses .-> FW
  TTS -. uses .-> IF5
  TTS -. uses .-> PIPER
  VQ -. uses .-> VQM
  PRE --> TMP
  FRAME --> TMP
  REPORT --> OPTDB
  MAN --> STT
  MAN --> TTS
  MAN --> ENCODE
  FRAME --> REPORT
  PKT --> REPORT
  CH --> REPORT
  RXPKT --> REPORT
  DATA --> MATRIX
  BASE --> MATRIX
  REPORT --> MATRIX --> RESULTS
  RESULTS --> RXUI
~~~
