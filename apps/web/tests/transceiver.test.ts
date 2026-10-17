import assert from 'node:assert/strict';
import { test } from 'node:test';
import { openRadio } from '../src/services/transceiver';

test('Android commands retain native names and JSON payloads; native events reach UI', () => {
  const commands: unknown[] = [];
  const events: unknown[] = [];
  (globalThis as any).window = { SWAR: { command: (...args: unknown[]) => commands.push(args) } };
  const radio = openRadio((...args) => events.push(args));
  radio.command('connect', { address: '192.168.1.2', port: 8988, pin: '123456' });
  radio.command('start'); radio.command('release');
  window.onNative?.('received', { text: 'नमस्ते' });
  assert.deepEqual(commands, [
    ['connect', '{"address":"192.168.1.2","port":8988,"pin":"123456"}'],
    ['start', '{}'], ['release', '{}'],
  ]);
  assert.deepEqual(events, [['received', { text: 'नमस्ते' }]]);
  radio.close();
  assert.deepEqual(commands.at(-1), ['cancel', '{}']);
  assert.equal(window.onNative, undefined);
  radio.command('start');
  assert.equal(commands.length, 4);
});

test('Browser uses existing backend schema and excludes itself from discovered relay clients', async () => {
  const events: any[] = [];
  const requests: any[] = [];
  let ws: any;
  (globalThis as any).window = {};
  (globalThis as any).location = { protocol: 'http:', host: 'localhost:3001' };
  (globalThis as any).WebSocket = class {
    onmessage: any; onclose: any;
    constructor(public url: string) { ws = this; }
    close() {}
  };
  (globalThis as any).fetch = async (...args: any[]) => {
    requests.push(args); return { ok: true, json: async () => ({ success: true }) };
  };
  const radio = openRadio((type, data) => events.push([type, data]));
  assert.equal(ws.url, 'ws://localhost:3001/api/v1/voicebridge/ws');
  ws.onmessage({ data: JSON.stringify({ event: 'init', data: { selfClient: { id: 'a' }, peers: [], webClients: [{ id: 'a' }, { id: 'b', ip: '10.0.0.2' }] } }) });
  assert.equal(events[0][1].peers.length, 1);
  assert.equal(events[0][1].peers[0].id, 'b');
  assert.equal(events[1][1].state, 'CONNECTED');
  radio.command('language', { language: 'hi' });
  radio.command('send', { text: 'नमस्ते' });
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(requests[0][0], '/api/v1/voicebridge/send');
  assert.deepEqual(JSON.parse(requests[0][1].body), { text: 'नमस्ते', language: 'hi' });
  ws.onmessage({ data: JSON.stringify({ event: 'voice_message_received', data: { text: 'Reply' } }) });
  assert.deepEqual(events.at(-1), ['received', { text: 'Reply' }]);
  radio.close();
});
