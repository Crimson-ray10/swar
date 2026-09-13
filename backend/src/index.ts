import { createVoiceBridgeServer } from './server.js';

const PORT = Number(process.env.PORT) || 3001;

async function main() {
  const instance = createVoiceBridgeServer(PORT);

  // Start NSD discovery and local advertising
  await instance.discovery.startAdvertising();
  await instance.discovery.startDiscovery();

  instance.httpServer.listen(PORT, () => {
    console.log(`[VoiceBridge] Backend listening on http://localhost:${PORT}`);
    console.log(`[VoiceBridge] WebSocket endpoint at ws://localhost:${PORT}/api/v1/voicebridge/ws`);
    console.log(`[VoiceBridge] Wi-Fi LAN TCP transport listening on port 8988`);
    console.log(`[VoiceBridge] Wi-Fi Direct P2P transport listening on port 8990`);
    console.log(`[VoiceBridge] Bluetooth RFCOMM transport listening on port 8992`);
  });
}

main().catch((err) => {
  console.error('[VoiceBridge] Startup error:', err);
  process.exit(1);
});
