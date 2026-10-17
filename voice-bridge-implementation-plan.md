# Voice Bridge — Proper Bluetooth + Wi-Fi Direct Implementation Plan

This document describes the proper Android implementation plan for Voice Bridge using normal Wi-Fi/LAN, Wi-Fi Direct, and Bluetooth Classic. The goal is an offline voice-communication system where speech is converted to text locally, the text is transmitted over the available local transport, and the receiving device converts that text back to speech locally.

> **Critical rule:** The transport can change; the voice pipeline must never depend on the transport.

## UI requirement

**The UI has already been designed by the user.** The implementation must use that existing UI as the presentation layer. Do **not** redesign, replace, or invent a different UI unless a technical implementation detail requires a small addition. All networking, voice, permission, state, reliability, discovery, pairing, PTT, alert, and transport controls must be wired into the already-designed screens/components.

The existing UI should drive/display whatever is already present for language, push-to-talk, conversation mode, connection state, selected transport, nearby devices/pairing, voice status, transcript/message state, and alerts.

---

# 1. Overall Architecture

```text
                         VOICE BRIDGE APP
 ┌─────────────────────────────────────────────────────────┐
 │                    Voice Engine                         │
 │                                                         │
 │ Mic → Audio Buffer → VAD → STT → Sentence → Text      │
 │                                             │           │
 │                                             ▼           │
 │                                      Transport Layer    │
 │                                             │           │
 │                                      ┌──────┴──────┐    │
 │                                      │             │    │
 │                                   Wi-Fi        Bluetooth│
 │                                      │             │    │
 │                                   TCP/TLS       RFCOMM   │
 │                                      │             │    │
 │                                      └──────┬──────┘    │
 │                                             │           │
 │                                          Receiver       │
 │                                             │           │
 │                                          Text Queue     │
 │                                             │           │
 │                                            TTS          │
 │                                             │           │
 │                                        AudioTrack       │
 │                                             │           │
 │                                          Speaker        │
 └─────────────────────────────────────────────────────────┘
```

STT/TTS must not know whether the transport is Wi-Fi LAN, Wi-Fi Direct, or Bluetooth.

---

# 2. Transport Abstraction

Implement the abstraction before implementing individual transports.

```kotlin
interface VoiceTransport {

    suspend fun connect(peer: Peer): Result<Unit>

    suspend fun disconnect()

    suspend fun send(message: VoiceMessage): Result<Unit>

    fun observeMessages(): Flow<VoiceMessage>

    fun observeConnectionState(): Flow<ConnectionState>

    suspend fun ping(): Long
}
```

Implementations:

```text
VoiceTransport
       │
       ├── WifiTransport
       │
       ├── WifiDirectTransport
       │
       └── BluetoothTransport
```

The voice engine only communicates with `VoiceTransport`.

---

# 3. Voice Pipeline

The core communication path is:

```text
MIC
 ↓
VAD
 ↓
STT
 ↓
TEXT
 ↓
TRANSPORT
 ↓
TEXT
 ↓
TTS
 ↓
SPEAKER
```

Do **not** turn the system into raw audio streaming. The architecture is specifically intended to reduce data requirements by transmitting text rather than audio.

---

# 4. Voice Engine Components

Split the voice subsystem into:

```text
VoiceEngine
│
├── AudioCapture
├── VAD
├── STTEngine
├── SentenceManager
├── MessageEncoder
├── MessageReceiver
├── TTS
└── AudioPlayer
```

### Sending side

```text
AudioRecord
    ↓
10–30 ms frames
    ↓
RingBuffer
    ↓
VAD
    ↓
speech detected
    ↓
STT
    ↓
partial transcript
    ↓
sentence manager
    ↓
final transcript
    ↓
VoiceMessage
    ↓
Transport
```

### Receiving side

```text
Transport
    ↓
VoiceMessage
    ↓
Priority Queue
    ↓
TTS
    ↓
PCM
    ↓
AudioTrack
```

---

# 5. Voice Reliability Rule

Do not make voice dependent on network timing.

Bad:

```text
STT finished
 ↓
send text
 ↓
wait
 ↓
TTS
```

Use a queue:

```text
                 ┌──────────────┐
Network ───────→ │ MessageQueue │
                 └──────┬───────┘
                        │
                ┌───────▼────────┐
                │ PlaybackWorker │
                └───────┬────────┘
                        │
                       TTS
                        │
                     Audio
```

If multiple messages arrive rapidly (`M1` … `M5`), queue them and process them in order.

---

# 6. VoiceMessage Protocol

Start with JSON because it is easy to debug.

```json
{
  "version": 1,
  "messageId": 1024,
  "senderId": "A",
  "language": "hi",
  "type": "NORMAL",
  "sequence": 17,
  "timestamp": 1778750000,
  "text": "मुझे मदद चाहिए"
}
```

Later, move to a compact binary protocol:

```text
┌─────────┬──────────┬──────────┬──────────┬──────────┬────────────┐
│ version │ type     │ language │ sequence │ length   │ text       │
│ 1 byte  │ 1 byte   │ 1 byte   │ 4 bytes  │ 2 bytes  │ N bytes    │
└─────────┴──────────┴──────────┴──────────┴──────────┴────────────┘
```

This minimizes data overhead.

---

# 7. Normal Wi-Fi Architecture

Both phones connect to the same local network:

```text
Phone A
   │
   │ Wi-Fi
   ▼
Router / hotspot
   ▲
   │ Wi-Fi
   │
Phone B
```

Two possible approaches:

## Option A — Direct TCP

```text
Phone A
   │
 TCP socket
   │
Phone B
```

One device acts as server.

## Option B — Local server abstraction

```text
Phone A ────────┐
                │
            Host socket
                │
Phone B ────────┘
```

For the final Android implementation, a simple TCP framed protocol is preferred over a heavy networking stack.

---

# 8. Wi-Fi Device Discovery

Do not require users to manually type IP addresses.

Use Android Network Service Discovery (NSD) on the local network.

```text
Phone A
  │
  │ "_voicebridge._tcp"
  ▼
LAN discovery
  │
  ▼
Phone B
```

Phone B advertises:

```text
Service:
VoiceBridge

Type:
_voicebridge._tcp

Port:
8988

Device:
ABC123
```

Phone A discovers it and performs:

```text
discover
 ↓
resolve
 ↓
connect
 ↓
handshake
```

---

# 9. Wi-Fi Handshake

After TCP connection:

```text
A → HELLO
B → HELLO_ACK
A → DEVICE_INFO
B → DEVICE_INFO
A → READY
B → READY
```

Example:

```json
{
  "protocolVersion": 1,
  "deviceId": "abc123",
  "language": "hi",
  "capabilities": {
    "stt": true,
    "tts": true,
    "languages": 10
  }
}
```

Only after the handshake completes should voice transmission begin.

---

# 10. Wi-Fi Reliability

The connection must recover automatically.

```text
CONNECTED
    ↓
connection lost
    ↓
RECONNECTING
    ↓
discover
    ↓
connect
    ↓
handshake
    ↓
CONNECTED
```

Maintain:

- connection state
- last successful ping
- last sent message ID
- last acknowledged message ID

---

# 11. TCP Framing

TCP is a stream; one `read()` does not necessarily equal one application message.

Use:

```text
[4 byte message length]
[message payload]
```

Example:

```text
00 00 00 78
{ ... 120-byte message ... }
```

Receiver:

```text
read 4 bytes
 ↓
length = 120
 ↓
read until 120 bytes obtained
 ↓
decode message
```

This prevents message corruption and packet-boundary assumptions.

---

# 12. Wi-Fi Security

For the final system, do not leave distress/emergency communication as unauthenticated arbitrary TCP.

Recommended architecture:

```text
device discovery
       ↓
pairing code
       ↓
session key
       ↓
encrypted connection
```

A suitable cryptographic architecture is:

```text
ECDH key exchange
      ↓
AES-GCM messages
```

Messages are small, so encryption overhead is acceptable.

---

# 13. Wi-Fi Direct Architecture

Wi-Fi Direct allows two phones to communicate directly without a router or Internet.

```text
Phone A
   │
   │ P2P
   │
Phone B
```

Android `WifiP2pManager` handles peer discovery and connection.

The Wi-Fi Direct network is not the application protocol.

```text
Wi-Fi Direct
    ↓
creates network path
    ↓
TCP socket
    ↓
VoiceBridge protocol
    ↓
Text
```

The STT/TTS layer must never know about `WifiP2pManager`.

---

# 14. Wi-Fi Direct Implementation Sequence

```text
App starts
    ↓
Check Wi-Fi P2P capability
    ↓
Request permissions
    ↓
Initialize WifiP2pManager
    ↓
Register receiver
    ↓
Discover peers
    ↓
Display devices
    ↓
User chooses peer
    ↓
connect()
    ↓
P2P group established
    ↓
get group information
    ↓
obtain peer IP
    ↓
TCP connection
    ↓
voice protocol
```

Relevant Android APIs include `WifiP2pManager.initialize()`, `discoverPeers()`, `connect()`, group-management APIs, and connection-information APIs.

---

# 15. Wi-Fi Direct Permissions

The application must handle modern Android permission requirements.

For Android 13+:

- `NEARBY_WIFI_DEVICES` is relevant to Wi-Fi P2P operations.

Depending on Android version/API, older location-related permissions and location-mode requirements may apply.

The application should explicitly handle:

```text
Wi-Fi ON
Nearby devices permission
Location-mode requirement where applicable
Wi-Fi P2P supported?
```

Do not assume the APIs will work without handling these states.

---

# 16. Wi-Fi Direct Service Discovery

Do not rely only on a raw peer list.

Use Wi-Fi Direct service discovery where practical.

Phone B advertises:

```text
VoiceBridge
port: 8988
deviceId: ABC123
```

Phone A:

```text
discoverServices()
        ↓
VoiceBridge found
        ↓
connect
```

This gives a better UX and lets devices advertise the actual Voice Bridge service.

---

# 17. Bluetooth Architecture

For this application, use:

> **Bluetooth Classic RFCOMM**

rather than implementing the primary communication protocol through BLE characteristics.

Bluetooth Classic is better suited to a continuously connected, bidirectional application stream.

Architecture:

```text
BluetoothTransport
│
├── AdapterManager
├── DiscoveryManager
├── PairingManager
├── ServerSocket
├── ClientSocket
├── InputStream
└── OutputStream
```

Connection:

```text
A discovers B
     ↓
user selects B
     ↓
pair
     ↓
B opens BluetoothServerSocket
     ↓
A creates BluetoothSocket
     ↓
RFCOMM connection
     ↓
handshake
     ↓
voice protocol
```

---

# 18. Bluetooth Permissions

On Android 12+, handle modern Bluetooth runtime permissions as required by the operation.

Typical permissions include:

- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`
- `BLUETOOTH_ADVERTISE`

Request only permissions actually required by the feature.

Example:

```text
Scan devices
   ↓
BLUETOOTH_SCAN

Connect
   ↓
BLUETOOTH_CONNECT

Make discoverable
   ↓
BLUETOOTH_ADVERTISE
```

---

# 19. Bluetooth Persistent Connection

Do not repeatedly connect/disconnect for each message.

Bad:

```text
send message
 ↓
connect
 ↓
send
 ↓
disconnect
```

Correct:

```text
pair
 ↓
connect
 ↓
RFCOMM socket remains open
 ↓
M1
M2
M3
M4
M5
...
```

This avoids repeated connection overhead.

---

# 20. Bluetooth Message Framing

Use the same framing system as Wi-Fi:

```text
[4-byte length]
[message]
```

Then:

```text
Bluetooth InputStream
       ↓
Frame decoder
       ↓
VoiceMessage
```

The exact same application protocol must be reused.

---

# 21. One Common Protocol for All Transports

```text
                     VoiceMessage
                          │
             ┌────────────┴───────────┐
             │                        │
        WifiTransport         BluetoothTransport
             │                        │
           TCP                    RFCOMM
             │                        │
             └────────────┬───────────┘
                          │
                     Phone B
                          │
                  MessageDecoder
                          │
                       TTS
```

Wi-Fi Direct becomes another TCP-capable transport:

```text
WifiDirectTransport
        ↓
TCP
```

The message protocol remains identical.

---

# 22. "Voice Must Work at Any Cost" — Practical Reliability Definition

The intended engineering target is:

> If any supported local transport is available, the application should automatically attempt to establish and maintain voice communication, recover from drops, and fall back to another available transport when possible.

No software can guarantee connectivity if all radios/hardware are unavailable, but whenever a supported local path exists the app should aggressively recover.

Recommended transport priority:

```text
Wi-Fi Direct
    ↓
Wi-Fi LAN
    ↓
Bluetooth
```

This can be configurable.

---

# 23. Automatic Transport Selection

```text
START
 │
 ├─ Wi-Fi Direct peer found?
 │        │
 │        └─ YES → connect
 │
 ├─ Same Wi-Fi network?
 │        │
 │        └─ YES → connect
 │
 └─ Bluetooth paired?
          │
          └─ YES → connect
```

After connection:

```text
connected
    ↓
monitor
    ↓
connection lost
    ↓
try current transport
    ↓
timeout
    ↓
try next transport
```

---

# 24. Do Not Switch Transports Mid-Sentence Without Reliability Logic

If a user says:

```text
"There is a fire..."
```

and Wi-Fi fails, an immediate reconnect can cause loss or duplication.

Use:

- `messageId`
- `sequence`
- `ACK`

Example:

```text
A → M102
B → ACK M102
```

If Wi-Fi dies before the ACK:

```text
Bluetooth
 ↓
resend M102
```

The receiver must ignore duplicate message IDs.

---

# 25. Message States

Every message should have explicit state:

```text
CREATED
SENT
ACKED
FAILED
RETRYING
DELIVERED
PLAYED
```

This distinguishes:

- network success
- message acknowledgement
- TTS success
- actual audio playback start

These states are also useful for latency measurements.

---

# 26. MessageStore

Persist finalized messages before transmission.

```text
final STT result
       ↓
persist message locally
       ↓
send
```

If the app crashes after STT but before network transmission, it can recover pending messages.

For emergency messages, persistence should be mandatory.

---

# 27. Emergency Message Reliability

For:

```text
type = ALERT
```

Use stronger delivery semantics:

```text
ALERT
 ↓
persist locally
 ↓
send
 ↓
wait ACK
 ↓
no ACK?
 ↓
retry
 ↓
fallback transport
 ↓
ACK
 ↓
TTS
```

Receiver:

```text
ALERT
 ↓
persist
 ↓
TTS priority queue
 ↓
play
```

This reduces the chance of losing an alert during a transient radio failure.

---

# 28. Voice Pipeline Reliability

Recommended architecture:

```text
AudioCapture
      ↓
AudioRingBuffer
      ↓
VAD
      ↓
STT
      ↓
SentenceAssembler
      ↓
MessageStore
      ↓
TransportManager
```

The `MessageStore` separates speech-recognition success from transport availability.

---

# 29. Android Background Operation

The app may need to continue communication while the main Activity is not in the foreground.

Use an appropriate Android foreground service architecture where continuous microphone/network operation requires it.

Suggested structure:

```text
MainActivity
      │
      ▼
VoiceCommunicationService
      │
 ┌────┼────────┐
 │    │        │
Audio Network TTS
```

Do not put the entire voice/network system inside the Activity.

Account for Android's current microphone foreground-service requirements, including the appropriate service type and microphone permission handling, plus restrictions around starting microphone foreground services from the background.

---

# 30. Audio Recording

Use:

```text
AudioRecord
```

Do not save full recordings just to process speech.

Pipeline:

```text
AudioRecord
 ↓
PCM frames
 ↓
ring buffer
 ↓
VAD
```

A reasonable starting point for speech processing is:

```text
16 kHz
mono
16-bit PCM
```

but final settings must match the chosen VAD/STT model requirements and be benchmarked.

---

# 31. Audio Playback

Use:

```text
AudioTrack
```

when the TTS engine provides PCM.

Pipeline:

```text
TTS
 ↓
PCM buffer
 ↓
AudioTrack
 ↓
speaker
```

Keep playback off the Android main/UI thread.

---

# 32. Normal vs Alert Playback

Create:

```text
NORMAL_QUEUE

ALERT_QUEUE
```

Normal messages:

```text
N1
N2
N3
```

Alert:

```text
ALERT
```

When an alert arrives:

```text
normal playback
      ↓
pause/stop according to product policy
      ↓
ALERT TTS
      ↓
play
      ↓
resume normal queue
```

Use supported Android audio controls; do not bypass device safety restrictions to force volume changes.

---

# 33. Continuous Conversation Mode

For the non-PTT phone-like mode:

```text
Person speaks
        ↓
VAD detects start
        ↓
STT continuously updates
        ↓
pause detected
        ↓
sentence finalized
        ↓
message sent
        ↓
receiver TTS
```

Continue with the next sentence rather than waiting for an entire paragraph.

---

# 34. Push-to-Talk Mode

For PTT:

```text
button DOWN
     ↓
enable microphone
     ↓
VAD/STT
     ↓
button UP
     ↓
finalize current sentence
     ↓
send
```

This produces walkie-talkie behavior.

The PTT controls must be implemented using the user's already-designed UI.

---

# 35. ConnectivitySupervisor

Create a dedicated supervisor:

```text
ConnectivitySupervisor
│
├── WifiMonitor
├── WifiDirectMonitor
├── BluetoothMonitor
├── TransportSelector
└── RecoveryManager
```

It should continuously know:

```text
Wi-Fi:        AVAILABLE
Wi-Fi Direct: AVAILABLE
Bluetooth:    CONNECTED
Voice:        READY
```

The existing UI must display these states using the user's design.

---

# 36. Heartbeat

Each active transport should have:

```text
PING
PONG
```

For example:

```text
every 1 second
```

A connection is healthy when a recent PONG is received; tune the exact timeout through testing.

Do not make heartbeats unnecessarily aggressive because they consume battery.

---

# 37. Connection State Machine

Centralize connection logic:

```text
DISCONNECTED
     ↓
DISCOVERING
     ↓
CONNECTING
     ↓
HANDSHAKING
     ↓
CONNECTED
     ↓
DEGRADED
     ↓
RECONNECTING
     ↓
CONNECTED
```

The existing UI consumes this state and renders it according to the user's screens.

---

# 38. Full Wi-Fi Implementation Plan

## W1 — LAN connection

Implement:

- TCP server
- TCP client
- framing
- handshake
- ACK

Test with:

```text
"hello"
```

## W2 — Discovery

Add:

```text
Android NSD
```

No manual IP entry.

## W3 — Text transport

Move `VoiceMessage` over TCP.

## W4 — Voice

Connect:

```text
STT → VoiceMessage → TCP → TTS
```

## W5 — Recovery

Add:

- ACK
- retry
- reconnect
- deduplication

## W6 — Encryption

Add:

- pairing
- session encryption

## W7 — Benchmark

Measure:

- STT latency
- network latency
- TTS latency
- end-to-end latency
- RTF

---

# 39. Full Wi-Fi Direct Implementation Plan

## P1

Build:

```text
WifiP2pManager
WifiP2pManager.Channel
BroadcastReceiver
```

## P2

Implement:

```text
discoverPeers()
```

## P3

Display:

```text
Nearby Voice Bridge devices
```

Use the user's existing UI.

## P4

Connect:

```text
connect()
```

## P5

Use:

```text
requestConnectionInfo()
```

to obtain group/network information.

## P6

Create:

```text
TCP socket
```

over the Wi-Fi Direct network.

## P7

Reuse the exact same:

```text
VoiceMessage
FrameCodec
Handshake
ACK
Retry
Deduplication
```

from normal Wi-Fi.

## P8

Test with no router and no Internet.

---

# 40. Full Bluetooth Implementation Plan

## B1

Check:

```text
BluetoothAdapter
```

## B2

Request only required Bluetooth permissions.

## B3

Implement discovery.

## B4

Implement pairing.

## B5

Device B:

```kotlin
listenUsingRfcommWithServiceRecord(...)
```

## B6

Device A:

```kotlin
createRfcommSocketToServiceRecord(...)
```

## B7

Keep the RFCOMM socket alive.

## B8

Reuse:

```text
FrameCodec
VoiceMessage
ACK
retry
deduplication
```

## B9

Connect:

```text
STT → Bluetooth → TTS
```

---

# 41. Bluetooth Must Not Carry Live PCM

Do not implement:

```text
AudioRecord
 ↓
Bluetooth
 ↓
AudioTrack
```

Correct:

```text
AudioRecord
 ↓
VAD
 ↓
STT
 ↓
TEXT
 ↓
Bluetooth RFCOMM
 ↓
TEXT
 ↓
TTS
 ↓
AudioTrack
```

This keeps bandwidth requirements extremely small.

---

# 42. Final Architecture

```text
                      ┌───────────────────────┐
                      │    Android UI         │
                      │ Existing User UI      │
                      └───────────┬───────────┘
                                  │
                      ┌───────────▼───────────┐
                      │ ConnectivitySupervisor│
                      └───────────┬───────────┘
                                  │
               ┌──────────────────┼─────────────────┐
               │                  │                 │
           Wi-Fi LAN          Wi-Fi Direct      Bluetooth
               │                  │                 │
              TCP                TCP             RFCOMM
               │                  │                 │
               └──────────────────┼─────────────────┘
                                  │
                         VoiceMessage Protocol
                                  │
                    ┌─────────────▼─────────────┐
                    │ Reliable Message Layer    │
                    │ ACK / retry / dedupe      │
                    └─────────────┬─────────────┘
                                  │
                         Priority Message Queue
                                  │
                  ┌───────────────┴──────────────┐
                  │                              │
              NORMAL_QUEUE                  ALERT_QUEUE
                  │                              │
                  └───────────────┬──────────────┘
                                  │
                              TTS Engine
                                  │
                              AudioTrack
                                  │
                               Speaker
```

---

# 43. Development Order

Do not develop all transports simultaneously.

Build in this order:

```text
1. Voice engine
       ↓
2. Message protocol
       ↓
3. Normal Wi-Fi TCP
       ↓
4. Wi-Fi discovery
       ↓
5. Reliability / ACK / retry
       ↓
6. Wi-Fi Direct
       ↓
7. Bluetooth RFCOMM
       ↓
8. ConnectivitySupervisor
       ↓
9. Automatic fallback
       ↓
10. Offline STT/TTS optimization
```

The most important milestone is:

```text
STT → TEXT → TRANSPORT → TEXT → TTS
```

working reliably before adding transport complexity.

---

# 44. Final Acceptance Tests

## Test A — Normal Wi-Fi

```text
Phone A
Wi-Fi
Phone B

Hindi:
"मुझे तुरंत मदद चाहिए"

→ STT
→ text
→ TCP
→ TTS
→ speaker
```

## Test B — Wi-Fi Direct

Turn off the router.

```text
Phone A
   ↕
Wi-Fi Direct
   ↕
Phone B

same message
```

It must still work.

## Test C — Bluetooth

Disable Wi-Fi networking.

```text
Phone A
   ↕
Bluetooth
   ↕
Phone B

same message
```

It must still work.

## Test D — Transport Failure

Start on Wi-Fi and break Wi-Fi.

Expected:

```text
Wi-Fi lost
    ↓
detect failure
    ↓
Wi-Fi Direct/Bluetooth available
    ↓
reconnect
    ↓
continue communication
```

No STT/TTS state should be lost.

---

# 45. Final Engineering Principle

Do **not** implement Bluetooth, Wi-Fi, and Wi-Fi Direct as unrelated networking systems.

Build one reliable communication core:

```text
VoiceMessage
FrameCodec
Handshake
ACK
Retry
Deduplication
MessageStore
PriorityQueue
```

Then build:

```text
WifiLanTransport
WifiDirectTransport
BluetoothTransport
```

as replaceable transport adapters.

This is what makes the requirement **“voice should work at any cost”** practical: the speech system continues independently while the ConnectivitySupervisor maintains or restores a transport underneath it.

The existing UI must remain the user-facing layer throughout. All connection states, transport selection, pairing/discovery, voice status, PTT, alerts, and other controls must be wired into the UI the user has already designed rather than creating a replacement interface.

---

# 46. Android Platform Considerations

The implementation must account for Android platform behavior:

- Wi-Fi Direct requires appropriate runtime permissions and API lifecycle handling.
- Android 13+ devices use `NEARBY_WIFI_DEVICES` for relevant Wi-Fi operations.
- Bluetooth on modern Android requires appropriate Nearby Devices permissions such as `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, and `BLUETOOTH_ADVERTISE`, depending on the operation.
- Continuous microphone communication may require an Android foreground service with the microphone service type and appropriate permission handling.
- Android places restrictions on starting microphone foreground services from the background.
- Wi-Fi Direct creates a local P2P path, after which the application can establish a normal TCP connection.
- Bluetooth RFCOMM provides a persistent stream suitable for the same framed application protocol.

Verify all platform requirements against the Android API level targeted by the final app during implementation.

---

# 47. Target System in One Diagram

```text
                         PHONE A
┌──────────────────────────────────────────────────┐
│                EXISTING USER UI                  │
│  (Use the already-designed UI; do not redesign)  │
└───────────────────────┬──────────────────────────┘
                        │
                        ▼
                  Voice Engine
                        │
           ┌────────────┼────────────┐
           │            │            │
        Audio         VAD           STT
        Capture        │             │
           │           └──────┬──────┘
           └──────────────────▼
                        Sentence
                           │
                           ▼
                     VoiceMessage
                           │
                           ▼
                 ConnectivitySupervisor
                           │
         ┌─────────────────┼──────────────────┐
         │                 │                  │
      Wi-Fi LAN        Wi-Fi Direct       Bluetooth
         │                 │                  │
         TCP               TCP              RFCOMM
         │                 │                  │
         └─────────────────┼──────────────────┘
                           │
                    Reliability Layer
                  ACK / Retry / Dedupe
                           │
                           ▼
                       PHONE B
                           │
                    Message Receiver
                           │
                    Priority Queue
                           │
                    ┌──────┴──────┐
                    │             │
                NORMAL          ALERT
                    │             │
                    └──────┬──────┘
                           │
                          TTS
                           │
                       AudioTrack
                           │
                        Speaker
```

The same architecture works in the reverse direction.

---

## Recommended implementation principle

Treat **Voice Engine**, **Message Reliability**, **Connectivity Supervisor**, and **Transport Adapters** as four separate layers. This is the core design that allows the same existing UI and the same speech system to survive transport changes and temporary connection failures.
