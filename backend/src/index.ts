import { createVoiceBridgeServer } from './server.js';

const PORT = Number(process.env.PORT) || 3001;

async function main() {
  const instance = createVoiceBridgeServer(PORT);

  // Activate Wi-Fi LAN, Wi-Fi Direct, Bluetooth listeners and UDP Network Discovery
  await instance.startAllTransports();

  instance.httpServer.listen(PORT, () => {
    console.log(`[VoiceBridge] Backend listening on http://localhost:${PORT}`);
    console.log(`[VoiceBridge] WebSocket endpoint at ws://localhost:${PORT}/api/v1/voicebridge/ws`);
    console.log(`[VoiceBridge] Wi-Fi LAN TCP transport active on port 8988`);
    console.log(`[VoiceBridge] Wi-Fi Direct P2P transport active on port 8990`);
    console.log(`[VoiceBridge] Bluetooth RFCOMM transport active on port 8992`);
  });
}

main().catch((err) => {
  console.error('[VoiceBridge] Startup error:', err);
  process.exit(1);
});

process.on('uncaughtException', (err) => {
  console.error('[VoiceBridge] Safely caught socket/process exception:', err.message);
});

process.on('unhandledRejection', (reason) => {
  console.error('[VoiceBridge] Safely caught unhandled rejection:', reason);
});

