# Acoustic transport and research procedure

Read `acoustic-audit.md` for the pre-change architecture. Acoustic mode is the default MainActivity transport. LocalTransport and WebsiteTransport are only constructed after explicit selection in the existing connection drawer. AcousticTransport has no socket, IP discovery, Wi-Fi, Bluetooth, HTTP or server dependency. Foreground microphone permission is required. Leaving the app/locking the phone stops the acoustic session and releases audio; resume requires Scan again.

## Physical layer

The current experimental profile uses 16 kHz mono PCM16, binary FSK at 2000/3000 Hz, 160 samples/symbol (100 configured raw bit/s), amplitude 0.25, 64 alternating preamble bits, sync `1ACFFC1D`, protected length and interleaved link bytes, with 250 ms leading/trailing silence. This profile was reduced from 500 and 250 raw bit/s after physical handshake failures; configured bitrate is never labeled measured payload throughput. Frequency, amplitude, sample rate, symbol size, preamble, sync, payload limit, gaps, retry limit and timeouts are constructor configuration, which must match at both ends. Unsupported configurations fail validation. FEC/CRC are explicitly Hamming(8,4)/CRC32; alternative algorithms are not advertised as implemented.

Preamble detection correlates known alternating symbols using matched sine/cosine frequency filters, requiring a matching sync word. Receiver acquisition uses the final 32 preamble symbols to tolerate loss during turnaround. DC removal and frequency-selective correlation avoid aggressive speech processing. Early/late decisions compensate small symbol timing drift. An eight-codeword bit transpose spreads short bursts; the final block may be shorter. The protected length is not interleaved. Signal confidence is tone separation, **not calibrated SNR**. No distance is inferred from it.

AudioRecord reads blocking 20 ms buffers on a dedicated thread. AudioTrack uses bounded nonblocking writes, drain deadlines, and immediate pause/flush cancellation. The modem input is muted during local modem/TTS output and the reverberation guard. Both devices must route to built-in audio; Bluetooth, wired and USB audio routes are rejected. Discovery uses randomized intervals and canonical UUID ordering; frame retries include jitter. The implementation is contention-based half-duplex, not full-duplex or an interference-free channel reservation protocol.

## IAC/1 outer frame

Big-endian, before FEC:

| Field | Bytes |
|---|---:|
| Magic `49 41` | 2 |
| Version 1 | 1 |
| Type enum (HELLO=0 through NACK=12) | 1 |
| Random installation UUID | 16 |
| Random active session UUID | 16 |
| Unsigned sequence | 4 |
| Payload length | 2 |
| Reserved zero | 2 |
| Payload | 0–256 |
| CRC32 of all preceding bytes | 4 |

Each raw byte becomes two SECDED Hamming codewords. Modulation additionally transmits: preamble; 32-bit sync; 16-bit encoded byte length expanded to 32 bits with SECDED; interleaved frame. CRC precedes FEC. The transport silently drops corrupt headers because it cannot safely trust a failed CRC's sender/session/sequence; timeout ARQ handles them. It sends NACK when a valid outer envelope contains invalid ITP/fragment metadata. This avoids reflecting NACKs to guessed senders.

HELLO and HELLO_ACK carry a compact capability record: modem version, encoding version, supported text/STT/TTS language masks, preferred language, maximum payload, sample rate, samples per symbol, carrier frequencies, FEC and modulation IDs. CAPABILITY/ACK repeats agreement; SESSION_START/ACK completes connection. Initial voice capabilities use only locally reported installed assets. The common text languages are hi/en/hinglish; voice masks are reported separately. There is no translation promise and Hinglish remains experimental.

Text carries unchanged ITP/1 bytes (UTF-8/optional DEFLATE, metadata, CRC and Hamming), fragmented with an 8-byte message sequence, 2-byte index and 2-byte count. Reassembly is bounded by ITP's 8192-byte source limit. Stop-and-wait ACK starts its timeout after speaker playback drains. Duplicate frames are ACKed without duplicate delivery; stale sequences, other peers/sessions and out-of-order fragments are rejected. The final ITP is validated before ACK. Only one local message may be outstanding. Both the outer and preserved inner FEC incur overhead; this is intentionally explicit, not a compression claim.

The outer decoder also supports a bounded CRC-aided list search for up to four detected double-bit Hamming codewords. It enumerates distance-two candidates and accepts only a unique CRC-valid frame; otherwise it rejects the frame. This does not change the preserved ITP encoding or guarantee recovery of arbitrary multi-bit errors.

TTS is dispatched after the outgoing decode ACK drains. PTT uses the same AudioRecord through Android 13+ `EXTRA_AUDIO_SOURCE`. A 1200 ms input delay allows acoustic synchronization to cancel ASR before modem samples are forwarded. Local modem/TTS output cancels ASR and conversation restarts when the channel becomes available. Android 12 and recognition providers without supplied-audio support cannot use this shared voice path; typed acoustic communication remains available. Platform speech boundary hints are retained; this does not implement a new neural VAD.

## Metrics and privacy

Counters distinguish messages, link frames, corrupted frames, corrected codewords, duplicates, retransmissions and exhausted delivery attempts. `effectiveBps` uses uniquely ACKed compressed payload bytes divided by session elapsed time; `appTxBps` counts link bytes, not physical sound bandwidth. `rawBps` is derived configuration. Requested UI payload budgets 500/1000/2000/4000/8000/16000 do not change the physical modem or certify achieved speeds. End-to-end latency, calibrated SNR, range and model memory remain null unless separately measured. ACK RTT excludes the outgoing frame airtime; message latency includes its queue/retries/ACK. CRC is not authentication or encryption: nearby listeners can decode and attackers can forge valid CRC packets. No encryption is claimed.

Microphone PCM is transient, cleared on reuse/close and never written to disk. Normal transcripts remain in memory. Install UUID and UI preferences are persisted. Structured events exclude transcript content except the existing UI's sent/received messages; debug instrumentation logs its explicit test fixtures. Research exports require an explicit benchmark start and contain only measurements/conditions.

## Repeatable checks

Run `scripts/test-acoustic.ps1`, `scripts/test-mobile-protocol.ps1`, `npm test`, `npm run build:backend`, and `backend/node_modules/.bin/tsx --test backend/tests/website-relay.test.ts` from the root (or the corresponding backend-relative command). Build using `scripts/build-mobile.ps1`; `-AcousticTest` produces a separately labeled test installation for phones whose existing app uses a different signing key. It retains the same UI and code, with a different application ID.

On two phones, prepare local speech assets before offline operation. Enable airplane mode and explicitly disable Wi-Fi/Bluetooth/mobile data. Set Acoustic in the connection drawer; use Scan on both phones. Start 20 cm apart with moderate media volume and unobstructed built-in microphone/speaker openings. Confirm CONNECTED before sending a short typed message; check decoded ACK and received text, then reverse direction. Verify PTT release and conversation separately with Hindi, English and Hinglish speech. USB instrumentation is diagnostic only; repeat a successful session after disconnecting USB to verify the full no-cable setup.

Debug physical test entry: `adb -s SERIAL shell am instrument -w -e role sender -e timeoutMs 240000 in.itantra.mobile.acoustictest/in.itantra.mobile.AcousticDeviceTest`, and role `receiver` on the other phone. It tests fixture text in both directions; a PASS does not prove human speech recognition, a wider range, or a reliability percentage.

Explicit debug bridge commands `benchmarkStart` with `{distanceM:0.2, environment:"quiet room", orientation:"...", packetSize:...}` and `benchmarkEnd` export JSON/CSV under the app's external-files `benchmarks` directory. Distances accepted: 0.2, 0.5, 1, 2, 3, 5 m. Join sender/receiver logs to calculate packet success with an actual denominator. Repeat with orientation changes, speech/music/noise, low volume, obstructed mic, user stop, permission denial, unavailable models, corrupt frames, duplicate/out-of-order frames, missing ACKs, screen lock/background and battery saver. Record failures; do not omit them from range/reliability analysis. Hardware tests not actually executed must remain marked pending.
