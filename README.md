# SWAR — Turn Barriers into Shared Experiences

**SWAR** is an accessibility-first communication bridge and offline neural transceiver designed to connect two disconnected sides across **language**, **physical ability**, **technical expertise**, and **zero-connectivity environments**.

---

## 🌟 Overview & System Architecture

```
                    ┌──────────────────────────────────────────────┐
                    │               SWAR ECOSYSTEM                 │
                    └──────────────────────┬───────────────────────┘
                                           │
             ┌─────────────────────────────┴─────────────────────────────┐
             ▼                                                           ▼
   ┌───────────────────┐                                       ┌───────────────────┐
   │    SWAR WEB       │                                       │   SWAR ANDROID    │
   ├───────────────────┤                                       ├───────────────────┤
   │ • Brand Landing   │                                       │ • On-device ASR   │
   │   Website (HTML5, │                                       │   (AI4Bharat      │
   │   Vanilla CSS &   │                                       │   IndicConformer) │
   │   Web Audio)      │                                       │ • Zero-Config     │
   │ • Tactile Neural  │                                       │   Wi-Fi Direct P2P│
   │   Transceiver     │                                       │ • Local LAN TCP   │
   │   Web Simulator   │                                       │ • Retro Tactile   │
   │ • Direct APK Dist │                                       │   Click-Wheel UI  │
   └───────────────────┘                                       └───────────────────┘
```

SWAR operates both as:
1. **Swar Web Platform** (`apps/web`): An editorial showcase website and interactive browser simulator for the tactile transceiver with realistic acoustic feedback and multi-device animations.
2. **Swar Native Android App** (`mobile/`): A 100% offline, peer-to-peer walkie-talkie and speech transceiver running directly on Android 11+ hardware using embedded neural speech models.

---

## 🚀 Key Features

- **100% Offline Speech Recognition**: Powered by AI4Bharat's `indicconformer_int8.onnx` running locally via `sherpa-onnx` and `onnxruntime-android`. No cloud or internet connection required.
- **Direct P2P Mesh Communication**: Seamless automatic peer discovery and bilateral voice transmission over **Wi-Fi Direct (P2P)** and **Local Wi-Fi LAN**.
- **Tactile Click-Wheel Interface**: Inspired by classic handheld hardware — featuring a central **Push-to-Talk (PTT)** button, directional navigation for switching screens, and live transcript streaming.
- **Editorial Brand Experience**: Warm aesthetic built on cream, terracotta clay, deep forest olive, and sage typography (`DM Serif Display` + `Plus Jakarta Sans`), complete with dynamic 3D mobile screen tumbling animations and interactive harmonic soundscapes.

---

## 📁 Repository Structure

```
swar/
├── apps/
│   └── web/                     # Swar Web: Landing Website & Transceiver Simulator
│       ├── public/              # Static fonts, audio, and distribution APKs
│       ├── src/
│       │   ├── assets/          # VT323 pixel font assets
│       │   ├── components/      # PixelTransceiver React component & styles
│       │   ├── services/        # Transceiver event bridge (Native & Web)
│       │   ├── utils/           # Web Audio synthesizer & sound effects
│       │   ├── main.tsx         # Standalone web simulator entrypoint
│       │   └── mobile.tsx       # Embedded Android WebView entrypoint
│       ├── app.js               # Website interactive animations & audio
│       ├── index.html           # Swar brand landing page
│       ├── mobile.html          # Mobile build template
│       ├── package.json         # Web workspace scripts
│       ├── styles.css           # Website design system styles
│       ├── transceiver.html     # Web transceiver simulator page
│       ├── vite.config.ts       # Multi-page Vite configuration
│       └── vite.mobile.config.ts# Android WebView asset bundler
├── mobile/                      # Swar Android Application (Kotlin/Java)
│   ├── app/
│   │   ├── src/main/assets/     # Bundled web UI and offline neural models
│   │   ├── src/main/java/       # Native communication & speech engine
│   │   │   └── in/itantra/mobile/
│   │   │       ├── MainActivity.kt  # Android UI lifecycle & Web bridge
│   │   │       ├── SpeechEngine.kt  # On-device Sherpa-ONNX speech pipeline
│   │   │       ├── LocalTransport.java # Wi-Fi Direct & LAN TCP socket transport
│   │   │       ├── FrameIO.java     # Binary frame serialization & framing
│   │   │       ├── ItpPacket.java   # Low-bitrate packet protocol
│   │   │       ├── MeshService.kt   # Background connection service
│   │   │       └── BootReceiver.kt  # Android auto-boot recovery
│   │   ├── src/main/jniLibs/    # Precompiled Sherpa-ONNX & ONNXRuntime binaries
│   │   └── build.gradle         # Android application build configuration
│   ├── build_and_deploy.py      # Standalone native compiler tool
│   ├── debug.keystore           # Development signing key
│   ├── signer.jar               # Fast APK v2 signer
│   └── update_ipod_apk.py       # Instant web & model repackaging tool
├── package.json                 # Monorepo root scripts
├── vercel.json                  # Production web deployment configuration
└── .gitignore                   # Clean repository ignore rules
```

---

## 🛠️ Getting Started

### 1. Prerequisites
- **Node.js** v18+ and **npm**
- **Python** 3.10+ (for APK bundling)
- **Android SDK** / ADB (optional, for device installation)

### 2. Run the Web Experience Locally
```bash
# Install dependencies
npm install

# Start development server
npm run dev
```
Navigate to:
- **`http://localhost:5173/`**: Swar Brand Website
- **`http://localhost:5173/transceiver.html`**: Tactile Web Transceiver Simulator

### 3. Build Web & Mobile Bundles
```bash
# Build production web bundle (dist/)
npm run build

# Build mobile assets for Android WebView
npm run build:mobile-ui

# Repackage and sign Android APK
npm run repack:apk
```

---

## 📱 Android App Deployment

To install the latest signed SWAR APK on a connected Android phone:
```bash
adb install -r -d apps/web/public/swar-ipod.apk
```
Or download directly from the web interface on any connected mobile browser.

---

## 📄 License
Open source for community accessibility. © 2026 Swar.
