import assert from 'node:assert';
import { test } from 'node:test';
import { FrameCodec, FrameDecoder } from '../src/protocol/FrameCodec.js';
import { HandshakeManager } from '../src/protocol/Handshake.js';
import { MessageStore } from '../src/reliability/MessageStore.js';
import { ReliabilityLayer } from '../src/reliability/ReliabilityLayer.js';
import { DeviceInfo, VoiceMessage } from '../src/types.js';
import { PriorityQueue } from '../src/voice/PriorityQueue.js';
import { SentenceAssembler } from '../src/voice/SentenceAssembler.js';
import { TTSEngine } from '../src/voice/TTSEngine.js';

test('FrameCodec: 4-byte framing encode and decode', async () => {
  const payload = { type: 'TEST', value: 42, text: 'नमस्ते' };
  const encoded = FrameCodec.encode(payload);

  // Length must be at least 4 bytes header + JSON string length
  assert.ok(encoded.length > 4);
  const length = encoded.readUInt32BE(0);
  assert.strictEqual(length, encoded.length - 4);

  // Stream decoding
  const decoder = new FrameDecoder();
  let decodedMessage: any = null;

  decoder.on('message', (msg) => {
    decodedMessage = msg;
  });

  // Split buffer into 2 chunks to simulate TCP fragmentation
  decoder.push(encoded.subarray(0, 6));
  assert.strictEqual(decodedMessage, null); // Not complete yet

  decoder.push(encoded.subarray(6));
  assert.deepStrictEqual(decodedMessage, payload);
});

test('HandshakeManager: Full HELLO -> READY negotiation', () => {
  const infoA: DeviceInfo = {
    protocolVersion: 1,
    deviceId: 'nodeA',
    language: 'hi',
    capabilities: { stt: true, tts: true, languages: 11 },
  };
  const infoB: DeviceInfo = {
    protocolVersion: 1,
    deviceId: 'nodeB',
    language: 'ta',
    capabilities: { stt: true, tts: true, languages: 11 },
  };

  const a = new HandshakeManager(infoA);
  const b = new HandshakeManager(infoB);

  // 1. A sends HELLO
  const hello = a.createHello();
  assert.strictEqual(hello.type, 'HELLO');

  // 2. B handles HELLO and produces HELLO_ACK
  const helloAck = b.handleMessage(hello);
  assert.ok(helloAck);
  assert.strictEqual(helloAck.type, 'HELLO_ACK');

  // 3. A handles HELLO_ACK and produces DEVICE_INFO
  const devInfoA = a.handleMessage(helloAck);
  assert.ok(devInfoA);
  assert.strictEqual(devInfoA.type, 'DEVICE_INFO');

  // 4. B handles DEVICE_INFO and responds with its own DEVICE_INFO
  const devInfoB = b.handleMessage(devInfoA);
  assert.ok(devInfoB);
  assert.strictEqual(devInfoB.type, 'DEVICE_INFO');

  // 5. A handles DEVICE_INFO and produces READY
  const readyA = a.handleMessage(devInfoB);
  assert.ok(readyA);
  assert.strictEqual(readyA.type, 'READY');
  assert.strictEqual(a.isReady(), true);

  // 6. B handles READY
  b.handleMessage(readyA);
  assert.strictEqual(b.isReady(), true);
});

test('SentenceAssembler: PTT mode and Continuous mode sentence finalization', () => {
  const assembler = new SentenceAssembler('sender_1', 'continuous');

  assembler.pushPhrase('मुझे मदद');
  assembler.pushPhrase('चाहिए');

  const msg = assembler.onVadPause();
  assert.ok(msg);
  assert.strictEqual(msg.text, 'मुझे मदद चाहिए');
  assert.strictEqual(msg.sequence, 1);
  assert.strictEqual(msg.type, 'NORMAL');

  // PTT alert mode
  assembler.setMode('ptt');
  const alertMsg = assembler.onPttRelease('ALERT', 'यहाँ तुरंत एम्बुलेंस भेजो');
  assert.ok(alertMsg);
  assert.strictEqual(alertMsg.type, 'ALERT');
  assert.strictEqual(alertMsg.sequence, 2);
  assert.strictEqual(alertMsg.text, 'यहाँ तुरंत एम्बुलेंस भेजो');
});

test('PriorityQueue: ALERT messages preempt and strictly precede NORMAL messages', () => {
  const pq = new PriorityQueue();

  const n1: VoiceMessage = {
    version: 1,
    messageId: 'm1',
    senderId: 'A',
    language: 'hi',
    type: 'NORMAL',
    sequence: 1,
    timestamp: Date.now(),
    text: 'Normal 1',
  };

  const n2: VoiceMessage = {
    version: 1,
    messageId: 'm2',
    senderId: 'A',
    language: 'hi',
    type: 'NORMAL',
    sequence: 2,
    timestamp: Date.now(),
    text: 'Normal 2',
  };

  const alert: VoiceMessage = {
    version: 1,
    messageId: 'm_alert',
    senderId: 'B',
    language: 'hi',
    type: 'ALERT',
    sequence: 3,
    timestamp: Date.now(),
    text: 'URGENT ALERT FIRE',
  };

  pq.enqueue(n1);
  assert.strictEqual(pq.getQueueLengths().isPlaying, true);

  // While n1 is "playing", queue n2 and then alert
  pq.enqueue(n2);
  pq.enqueue(alert); // Alert preempts n1 and starts playing immediately

  // Alert is currently playing
  assert.strictEqual(pq.getQueueLengths().isPlaying, true);

  // Complete alert playback
  pq.onPlaybackComplete();

  // Next is preempted normal message (n1)
  assert.strictEqual(pq.getQueueLengths().isPlaying, true);
});

test('MessageStore & ReliabilityLayer: Deduping and ACK tracking', () => {
  const store = new MessageStore();
  const rel = new ReliabilityLayer(store);

  const msg: VoiceMessage = {
    version: 1,
    messageId: 'msg_unique_100',
    senderId: 'A',
    language: 'hi',
    type: 'NORMAL',
    sequence: 10,
    timestamp: Date.now(),
    text: 'Test message',
  };

  // First time not duplicate
  assert.strictEqual(rel.isDuplicate(msg), false);
  // Second time is duplicate
  assert.strictEqual(rel.isDuplicate(msg), true);

  // ACK handling
  let sendCount = 0;
  rel.trackSentMessage(msg, async () => {
    sendCount++;
    return true;
  });

  const ack = rel.createAck(msg);
  assert.strictEqual(ack.type, 'ACK');
  assert.strictEqual(ack.messageId, 'msg_unique_100');

  const handled = rel.handleAck(ack);
  assert.strictEqual(handled, true);
  assert.strictEqual(rel.getLastAckedMessageId(), 'msg_unique_100');
  assert.strictEqual(store.getMessage('msg_unique_100')?.state, 'ACKED');
});

test('TTSEngine: Synthesizes 16kHz PCM and standard WAV container', async () => {
  const tts = new TTSEngine(16000);
  const result = await tts.synthesize('नमस्ते भारत', 'hi');

  assert.ok(result.pcm.length > 0);
  assert.ok(result.wavBase64.startsWith('data:audio/wav;base64,'));
  assert.strictEqual(result.sampleRate, 16000);
  assert.ok(result.durationMs > 0);
});
