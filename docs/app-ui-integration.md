# iPod frontend integration

The `codex/app-ui-integration` branch starts from `app-ui`. Its new React
transceiver now calls the existing services instead of showing simulated peers,
pairing success, acknowledgements, or fabricated bitrate measurements.

## Android

`apps/web/src/mobile.tsx` renders the transceiver. Vite bundles its JavaScript,
CSS, and licensed pixel font into `mobile/app/src/main/assets/generated/`.
The native WebView serves only these bundled assets at its existing local origin;
network loading remains blocked. `SWAR.command` and `window.onNative` connect
the UI to the existing `LocalTransport` and `SpeechEngine` implementations.

The center button captures while held and releases on pointer-up/cancellation.
Nearby discovery, connection requests, accept/decline, LAN hosting/joining,
language selection, speech-model download, and stopping playback use real native
commands. Received transcripts and connection state come from native events.
Speech recognition packs are separate downloads, not bundled in the APK.

```powershell
npm ci
./scripts/build-mobile.ps1
./scripts/build-mobile.ps1 -Install -Device YOUR_ADB_SERIAL
```

The script accepts `-JavaHome`, `-Sdk`, and `-Gradle`, or uses JAVA_HOME,
ANDROID_HOME, and the local scratch toolchain. Requirements: JDK 17+, Gradle
9.1, Android SDK 36. APK: `mobile/app/build/outputs/apk/debug/app-debug.apk`.
This is a development-signed Android 12+ build with ARM64 speech libraries.
Replacing a differently signed existing app requires resolving the signing
mismatch; do not automatically uninstall it and erase its data.

Two existing clean-build blockers were corrected: the SDK declarations now come
from Gradle instead of the manifest, and LocalTransport has its missing TAG
constant. The unavailable custom debug keystore reference was removed so Gradle
can use its standard development signing configuration.

## Browser

`npm run build` then `npm start` serves the frontend and backend together on
port 3001. The new adapter uses the existing REST endpoints and WebSocket event
schema without modifying the Node backend. Open two clients to use the shared
web relay channel; this backend broadcasts to all clients, including the sender.
Browser recognition depends on browser support and may use internet services.
The Android path remains separate and uses the existing native speech pipeline.

## Verification

- TypeScript and Vite web/mobile builds.
- Six existing backend tests.
- Adapter contract tests: `node backend/node_modules/tsx/dist/cli.mjs --test apps/web/tests/transceiver.test.ts`.
- Existing Java packet tests: 55 cases and all 128 single-bit nibble repairs.
- Existing real TCP loopback transport test.
- Two browser clients: dispatched text appears in the receiving transceiver.

Physical two-phone discovery, speech quality, offline model readiness, and audio
delivery must still be checked on two handsets. Build and loopback tests do not
establish those capabilities. The scope here is frontend integration, not a
replacement or certification of the existing speech and transport engines.
