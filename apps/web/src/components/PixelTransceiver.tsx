import React, { useState, useEffect, useRef, useCallback } from 'react';
import { Volume2, VolumeX, RefreshCw, Unlink, Radio, Zap } from 'lucide-react';
import { sound } from '../utils/audioEffects';
import './PixelTransceiver.css';
import { openRadio, isNative } from '../services/transceiver';
import swanLogo from '../assets/swan-logo.png';

export type ScreenMode = 'devices' | 'transceiver' | 'idle' | 'languages';

export interface PeerDevice {
  id: string;
  name: string;
  transport: string;
  ip: string;
  port?: number;
  p2pAddress?: string;
  btAddress?: string;
  raw?: any;
}

// Strict filter: Available Devices screen ONLY shows Wi-Fi Direct connections!
export const isWifiDirectPeer = (d: any): boolean => {
  if (!d) return false;
  const source = String(d.source || d.raw?.source || '').toLowerCase();
  const transport = String(d.transport || d.raw?.transport || '').toLowerCase();
  const p2pAddr = String(d.p2pAddress || d.raw?.p2pAddress || '').trim();

  // Explicitly remove/filter out LAN, mDNS, relay and pure Bluetooth
  if (source === 'mdns' || source === 'lan' || source === 'bluetooth') return false;
  if (transport.includes('lan') || transport.includes('mdns') || transport.includes('bluetooth') || transport.includes('relay')) return false;

  // Accept Wi-Fi Direct (P2P address, source: p2p, or transport: Wi-Fi Direct / P2P)
  if (p2pAddr.length > 0) return true;
  if (source === 'p2p') return true;
  if (transport.includes('wifi direct') || transport.includes('wi-fi direct') || transport.includes('p2p')) return true;

  return false;
};

// Hugging Face Offline Model Catalogue (Indian Languages IndicConformer STT + Indic-TTS FastPitch)
export interface HFModel {
  tag: string;
  code: string;
  name: string;
  nativeName: string;
  sizeMB: number;
  stt: string;
  tts: string;
}

export const HF_CATALOGUE: HFModel[] = [
  { tag: 'en-IN', code: 'EN', name: 'Indian English', nativeName: 'English', sizeMB: 154.9, stt: 'IndicConformer int8', tts: 'FastPitch + HiFi-GAN' },
  { tag: 'hi-IN', code: 'HI', name: 'Hindi', nativeName: 'हिन्दी', sizeMB: 268.8, stt: 'IndicConformer int8', tts: 'FastPitch + HiFi-GAN' },
  { tag: 'ta-IN', code: 'TA', name: 'Tamil', nativeName: 'தமிழ்', sizeMB: 268.7, stt: 'IndicConformer int8', tts: 'FastPitch + HiFi-GAN' },
  { tag: 'te-IN', code: 'TE', name: 'Telugu', nativeName: 'తెలుగు', sizeMB: 267.6, stt: 'IndicConformer int8', tts: 'FastPitch + HiFi-GAN' },
  { tag: 'mr-IN', code: 'MR', name: 'Marathi', nativeName: 'मराठी', sizeMB: 268.6, stt: 'IndicConformer int8', tts: 'FastPitch + HiFi-GAN' },
  { tag: 'gu-IN', code: 'GU', name: 'Gujarati', nativeName: 'ગુજરાતી', sizeMB: 268.8, stt: 'IndicConformer int8', tts: 'FastPitch + HiFi-GAN' },
  { tag: 'bn-IN', code: 'BN', name: 'Bengali', nativeName: 'বাংলা', sizeMB: 266.7, stt: 'IndicConformer int8', tts: 'FastPitch + HiFi-GAN' },
  { tag: 'kn-IN', code: 'KN', name: 'Kannada', nativeName: 'ಕನ್ನಡ', sizeMB: 268.7, stt: 'IndicConformer int8', tts: 'FastPitch + HiFi-GAN' },
  { tag: 'ml-IN', code: 'ML', name: 'Malayalam', nativeName: 'മലയാളം', sizeMB: 268.1, stt: 'IndicConformer int8', tts: 'FastPitch + HiFi-GAN' },
  { tag: 'or-IN', code: 'OR', name: 'Odia', nativeName: 'ଓଡ଼ିଆ', sizeMB: 268.0, stt: 'IndicConformer int8', tts: 'FastPitch + HiFi-GAN' },
];

const LANGUAGES = [
  { code: 'EN_IN', name: 'English (Offline Neural)', speechCode: 'en-IN' },
];

interface PixelTransceiverProps {
  onBackToMain?: () => void;
  onNavigate?: (view: 'transceiver' | 'experience' | 'studio') => void;
}

export const PixelTransceiver: React.FC<PixelTransceiverProps> = () => {
  // 1. Screen Mode & Navigation (Starts at 'devices' per requirement)
  const [screenMode, setScreenMode] = useState<ScreenMode>('devices');
  const [lastScreenMode, setLastScreenMode] = useState<ScreenMode>('devices');
  const [connectedPeer, setConnectedPeer] = useState<PeerDevice | null>(null);
  const [selectedDeviceIndex, setSelectedDeviceIndex] = useState<number>(0);
  const [isPairing, setIsPairing] = useState<boolean>(false);
  const [pairingLog, setPairingLog] = useState<string>('');
  const [pairingPeerId, setPairingPeerId] = useState<string | null>(null);

  const [peers, setPeers] = useState<PeerDevice[]>([]);
  const [connectionState, setConnectionState] = useState<string>('DISCONNECTED');
  const [notice, setNotice] = useState<string>('SELECT NEARBY PEER TO CONNECT');
  const [incoming, setIncoming] = useState<any>(null);
  const [pin] = useState<string>('');

  // Hugging Face Model State (Requirement 3)
  const [installedModels, setInstalledModels] = useState<string[]>(['en-IN']);
  const [activeModelTag, setActiveModelTag] = useState<string>('en-IN');
  const [selectedModelIndex, setSelectedModelIndex] = useState<number>(0);
  const [downloadingModels, setDownloadingModels] = useState<Record<string, { pct: number; mbDone: number; mbTotal: number }>>({});
  const modelListRef = useRef<HTMLDivElement | null>(null);

  const radio = useRef<ReturnType<typeof openRadio> | null>(null);
  const held = useRef<boolean>(false);
  const command = (action: string, data: any = {}) => radio.current?.command(action, data);

  // Audio & Talking States
  const [isTalking, setIsTalking] = useState<boolean>(false);
  const [liveTranscript, setLiveTranscript] = useState<string>('READY WHEN YOU ARE.');
  const [isPeerSpeaking, setIsPeerSpeaking] = useState<boolean>(false);
  const [soundEnabled, setSoundEnabled] = useState<boolean>(true);
  const [languageIdx] = useState<number>(0);

  // Canvas References
  const idleCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const transceiverCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const animFrameIdleRef = useRef<number | null>(null);
  const animFrameTransceiverRef = useRef<number | null>(null);

  const recognitionRef = useRef<any>(null);
  const INACTIVITY_TIMEOUT_MS = 90000; // 1 min 30 seconds
  const inactivityTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pairingTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const deviceListRef = useRef<HTMLDivElement | null>(null);
  const lastSpokenTextRef = useRef<string>('');
  const hasDispatchedRef = useRef<boolean>(false);

  const currentLanguage = LANGUAGES[languageIdx];
  const currentHFModel = HF_CATALOGUE.find(m => activeModelTag.toLowerCase().startsWith(m.tag.split('-')[0].toLowerCase())) || HF_CATALOGUE[0];

  // Helper to clear inactivity / silence timer
  const clearInactivityTimer = useCallback(() => {
    if (inactivityTimerRef.current) {
      clearTimeout(inactivityTimerRef.current);
      inactivityTimerRef.current = null;
    }
  }, []);

  // 1 min 30 seconds inactivity timer: auto-returns to 'idle' animation screen
  const resetInactivityTimer = useCallback(() => {
    clearInactivityTimer();
    inactivityTimerRef.current = setTimeout(() => {
      setScreenMode('idle');
      setNotice(prev => (prev.includes('LINKED') ? prev : 'IDLE · CARRIER STANDBY'));
    }, INACTIVITY_TIMEOUT_MS);
  }, [clearInactivityTimer]);

  // Aliases for silence completion
  const startSilenceTimer = resetInactivityTimer;
  const clearSilenceTimer = clearInactivityTimer;

  // Discovery trigger
  const triggerDiscovery = useCallback(() => {
    command('discover');
    if (isNative()) command('discoverP2p');
  }, []);

  // 2. Open Radio & Listen for Events
  useEffect(() => {
    const normalize = (d: any): PeerDevice => ({
      id: d.id || d.p2pAddress || d.btAddress || d.address || String(Math.random()),
      name: d.name || d.address || 'Nearby phone',
      ip: d.address || '',
      port: d.port,
      transport: (d.p2pAddress || d.source === 'p2p') ? 'Wi-Fi Direct' : (d.transport || 'P2P'),
      p2pAddress: d.p2pAddress,
      btAddress: d.btAddress,
      raw: d,
    });

    radio.current = openRadio((type, data) => {
      if (type === 'peer') {
        const peer = normalize(data);
        if (!isWifiDirectPeer(peer)) return;
        setPeers(old => [...old.filter(p => p.id !== peer.id), peer]);
      }
      if (type === 'peers') {
        const valid = (data.peers || []).map(normalize).filter(isWifiDirectPeer);
        setPeers(valid);
      }
      if (type === 'peerLost') {
        setPeers(old => old.filter(p => p.id !== normalize(data).id));
      }
      if (type === 'modelSupport') {
        if (Array.isArray(data.installed)) {
          setInstalledModels(data.installed);
        }
      }
      if (type === 'capabilities') {
        if (data.locale) {
          setActiveModelTag(data.locale);
        }
      }
      if (type === 'download_progress') {
        setDownloadingModels(prev => ({
          ...prev,
          [data.tag]: {
            pct: data.pct || 0,
            mbDone: data.mbDone || 0,
            mbTotal: data.mbTotal || 0,
          }
        }));
      }

      if (type === 'connection') {
        setConnectionState(data.state);
        setIsPairing(['CONNECTING', 'WAITING_APPROVAL', 'HANDSHAKING'].includes(data.state));
        setPairingLog(data.state);

        if (data.state === 'CONNECTED') {
          if (pairingTimeoutRef.current) clearTimeout(pairingTimeoutRef.current);
          setConnectedPeer({
            id: data.address || 'connected',
            name: data.peer || 'Connected Peer',
            ip: data.address || '',
            transport: data.transport || 'LAN',
          });
          setIncoming(null);
          setIsPairing(false);
          setPairingPeerId(null);
          sound.playConnectChirp();
          clearSilenceTimer();
          // After connecting -> automatically change to Idle Animation page
          setScreenMode('idle');
          setNotice(`LINKED TO ${data.peer || 'PEER'}`);
        } else if (data.state === 'RECONNECTING') {
          setNotice(`RECONNECTING TO ${connectedPeer?.name || data.peer || 'PEER'}…`);
          setPairingLog('RECONNECTING…');
        } else if (['DISCONNECTED', 'FAILED'].includes(data.state)) {
          setConnectedPeer(null);
          held.current = false;
          setIsTalking(false);
          setIsPeerSpeaking(false);
          clearSilenceTimer();
          setIsPairing(false);
          setPairingPeerId(null);
          sound.playClick();
          // If disconnected -> automatically return to Available Devices page
          setScreenMode('devices');
          setNotice(data.state === 'FAILED' ? 'LINK FAILED · SELECT PEER' : 'DISCONNECTED · SELECT PEER');
          triggerDiscovery();
        }
      }

      if (type === 'connection_request') {
        sound.playConnectChirp();
        command('respondConnection', { accept: true });
        setIncoming(null);
      }

      if (['request_cancelled', 'request_declined'].includes(type)) {
        setIncoming(null);
        setIsPairing(false);
        setPairingPeerId(null);
        setNotice(type.replace('_', ' ').toUpperCase());
      }

      // Requirement 3: When someone speaks (received from peer), automatically switch to Transceiver page!
      if (['partial', 'recognized', 'sent', 'received'].includes(type) && data.text) {
        setLiveTranscript(data.text);
        if (['partial', 'recognized'].includes(type)) {
          lastSpokenTextRef.current = data.text;
        }
        if (['sent', 'recognized'].includes(type)) {
          hasDispatchedRef.current = true;
        }
        if (type === 'received') {
          held.current = false;
          setIsTalking(false);
          recognitionRef.current?.abort();
          setIsPeerSpeaking(true);
          // Automatically switch to Transceiver page on incoming voice!
          setScreenMode('transceiver');
          clearSilenceTimer();
        }
      }

      if (type === 'speech') {
        setNotice(data.state);
        if (/idle|error|released/i.test(data.state)) {
          held.current = false;
          setIsTalking(false);
          setIsPeerSpeaking(false);
          startSilenceTimer();
        }
      }

      if (['notice', 'error'].includes(type)) {
        setNotice(data.message || data.state || JSON.stringify(data));
        if (type === 'error') {
          held.current = false;
          setIsTalking(false);
          setIsPairing(false);
          setPairingPeerId(null);
        }
      }
    });

    command('language', { language: 'en' });
    command('capabilities');
    triggerDiscovery();

    return () => {
      radio.current?.close();
      clearSilenceTimer();
      if (pairingTimeoutRef.current) clearTimeout(pairingTimeoutRef.current);
    };
  }, [clearSilenceTimer, startSilenceTimer, triggerDiscovery]);

  // Requirement: Global inactivity detector (switches to Idle Animation if app is not interacted with for 1m 30s)
  useEffect(() => {
    resetInactivityTimer();

    const handleUserInteraction = () => {
      resetInactivityTimer();
    };

    window.addEventListener('pointerdown', handleUserInteraction, { passive: true });
    window.addEventListener('touchstart', handleUserInteraction, { passive: true });
    window.addEventListener('keydown', handleUserInteraction, { passive: true });
    window.addEventListener('click', handleUserInteraction, { passive: true });
    window.addEventListener('wheel', handleUserInteraction, { passive: true });

    return () => {
      clearInactivityTimer();
      window.removeEventListener('pointerdown', handleUserInteraction);
      window.removeEventListener('touchstart', handleUserInteraction);
      window.removeEventListener('keydown', handleUserInteraction);
      window.removeEventListener('click', handleUserInteraction);
      window.removeEventListener('wheel', handleUserInteraction);
    };
  }, [resetInactivityTimer, clearInactivityTimer]);

  // Periodic discovery refresh when on 'devices' screen and disconnected
  useEffect(() => {
    if (screenMode !== 'devices' || connectedPeer) return;
    const interval = setInterval(() => {
      triggerDiscovery();
    }, 8000);
    return () => clearInterval(interval);
  }, [screenMode, connectedPeer, triggerDiscovery]);

  // Sound effects toggle
  const toggleSound = () => {
    const next = !soundEnabled;
    setSoundEnabled(next);
    sound.setEnabled(next);
    sound.playClick();
  };

  // Browser SpeechRecognition fallback (for Web simulation)
  useEffect(() => {
    if (isNative()) return;
    const SpeechRec = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (SpeechRec) {
      try {
        const rec = new SpeechRec();
        rec.continuous = true;
        rec.interimResults = true;
        rec.lang = currentLanguage.speechCode;

        rec.onresult = (event: any) => {
          let interim = '';
          for (let i = event.resultIndex; i < event.results.length; ++i) {
            interim += event.results[i][0].transcript;
          }
          if (interim.trim()) {
            setLiveTranscript(interim);
            const finalized = Array.from(event.results as ArrayLike<any>)
              .slice(event.resultIndex)
              .filter((r: any) => r.isFinal)
              .map((r: any) => r[0].transcript)
              .join(' ');
            if (finalized.trim()) command('send', { text: finalized });
          }
        };

        rec.onerror = (e: any) => {
          console.log('SpeechRecognition notice:', e.error);
        };

        rec.onend = () => {
          if (held.current) {
            try { rec.start(); } catch (_) {}
          } else {
            startSilenceTimer();
          }
        };

        recognitionRef.current = rec;
      } catch (_) {}
    }

    return () => {
      if (recognitionRef.current) {
        try { recognitionRef.current.stop(); } catch (_) {}
      }
    };
  }, [currentLanguage, startSilenceTimer]);

  useEffect(() => {
    command('language', { language: currentLanguage.speechCode.split('-')[0] });
  }, [currentLanguage]);

  // =========================================================================
  // PAGE 3: Hypnotic Idle Carrier Oscilloscope Animation
  // =========================================================================
  useEffect(() => {
    if (screenMode !== 'idle') return;
    const canvas = idleCanvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let t = 0;
    const render = () => {
      t += 0.035;
      const w = canvas.width;
      const h = canvas.height;

      ctx.clearRect(0, 0, w, h);

      // CRT phosphor grid lines
      ctx.strokeStyle = 'rgba(255, 255, 255, 0.06)';
      ctx.lineWidth = 1;
      const gridSize = 16;
      for (let x = 0; x < w; x += gridSize) {
        ctx.beginPath();
        ctx.moveTo(x, 0);
        ctx.lineTo(x, h);
        ctx.stroke();
      }
      for (let y = 0; y < h; y += gridSize) {
        ctx.beginPath();
        ctx.moveTo(0, y);
        ctx.lineTo(w, y);
        ctx.stroke();
      }

      // Breathing harmonic Lissajous carrier wave
      ctx.lineWidth = 2;
      ctx.strokeStyle = '#e4e4e7';
      ctx.shadowColor = 'rgba(228, 228, 231, 0.65)';
      ctx.shadowBlur = 6;

      ctx.beginPath();
      const centerY = h / 2;
      for (let x = 0; x < w; x += 2) {
        const wave1 = Math.sin(x * 0.045 + t) * 16;
        const wave2 = Math.cos(x * 0.02 - t * 0.6) * 8;
        const wave3 = Math.sin(x * 0.1 + t * 1.4) * 4;
        const y = centerY + wave1 + wave2 + wave3;
        const pxY = Math.round(y / 2) * 2;

        if (x === 0) ctx.moveTo(x, pxY);
        else ctx.lineTo(x, pxY);
      }
      ctx.stroke();

      // Secondary ghost wave
      ctx.lineWidth = 1;
      ctx.strokeStyle = 'rgba(212, 212, 216, 0.3)';
      ctx.shadowBlur = 0;
      ctx.beginPath();
      for (let x = 0; x < w; x += 3) {
        const y = centerY + Math.sin(x * 0.035 - t * 1.1) * 22;
        const pxY = Math.round(y / 2) * 2;
        if (x === 0) ctx.moveTo(x, pxY);
        else ctx.lineTo(x, pxY);
      }
      ctx.stroke();

      // Telemetry heartbeat pulse
      const pulseX = (Math.sin(t * 1.6) * 0.5 + 0.5) * w;
      ctx.fillStyle = '#ffffff';
      ctx.fillRect(Math.round(pulseX / 4) * 4, centerY - 2, 4, 4);

      animFrameIdleRef.current = requestAnimationFrame(render);
    };

    render();

    return () => {
      if (animFrameIdleRef.current) cancelAnimationFrame(animFrameIdleRef.current);
    };
  }, [screenMode]);

  // =========================================================================
  // PAGE 2: Dynamic Voice-Reactive Audio Graph Canvas
  // Reacts vividly when peer is speaking (voice receiving) or user transmits!
  // =========================================================================
  useEffect(() => {
    if (screenMode !== 'transceiver') return;
    const canvas = transceiverCanvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let t = 0;
    let voicePhase = 0;

    const render = () => {
      t += 0.08;
      voicePhase += 0.12;
      const w = canvas.width;
      const h = canvas.height;
      const centerY = h / 2 - 6;

      ctx.clearRect(0, 0, w, h);

      // CRT phosphor grid
      ctx.strokeStyle = 'rgba(255, 255, 255, 0.05)';
      ctx.lineWidth = 1;
      for (let x = 0; x < w; x += 16) {
        ctx.beginPath();
        ctx.moveTo(x, 0);
        ctx.lineTo(x, h);
        ctx.stroke();
      }
      for (let y = 0; y < h; y += 16) {
        ctx.beginPath();
        ctx.moveTo(0, y);
        ctx.lineTo(w, y);
        ctx.stroke();
      }

      // Voice activity multiplier
      let voiceEnergy = 0.15; // idle baseline
      if (isPeerSpeaking) {
        // Dynamic simulated voice cadence with syllables, pitch jumps, formant fluctuations
        voiceEnergy = 0.65 + Math.sin(t * 3.5) * 0.25 + (Math.sin(t * 7.2) > 0.3 ? 0.35 : -0.15) + (Math.random() * 0.2);
      } else if (isTalking) {
        voiceEnergy = 0.55 + Math.sin(t * 4.2) * 0.25 + Math.random() * 0.2;
      }
      voiceEnergy = Math.max(0.12, Math.min(1.2, voiceEnergy));

      // 1. Draw dynamic audio frequency spectrum bars along the bottom
      const numBars = 28;
      const barWidth = Math.floor(w / numBars) - 2;
      for (let i = 0; i < numBars; i++) {
        const barX = i * (barWidth + 2) + 2;
        const freqWeight = Math.sin((i / numBars) * Math.PI); // vocal formant bell curve
        const noise = Math.sin(voicePhase * 2 + i * 0.8) * 0.5 + 0.5;
        const barHeight = Math.max(3, (freqWeight * 34 * voiceEnergy) + (noise * 16 * voiceEnergy));
        
        ctx.fillStyle = isPeerSpeaking
          ? 'rgba(56, 189, 248, 0.75)'
          : isTalking
          ? 'rgba(239, 68, 68, 0.75)'
          : 'rgba(228, 228, 231, 0.45)';
        ctx.fillRect(barX, h - barHeight - 2, barWidth, barHeight);
      }

      // 2. Draw live voice waveform oscillograph
      ctx.lineWidth = isPeerSpeaking || isTalking ? 2.5 : 1.5;
      ctx.strokeStyle = isPeerSpeaking ? '#38bdf8' : isTalking ? '#ffffff' : '#e4e4e7';
      ctx.shadowColor = isPeerSpeaking ? 'rgba(56, 189, 248, 0.8)' : 'rgba(228, 228, 231, 0.7)';
      ctx.shadowBlur = isPeerSpeaking || isTalking ? 8 : 4;

      ctx.beginPath();
      for (let x = 0; x < w; x += 2) {
        const xNorm = x / w;
        const envelope = Math.sin(xNorm * Math.PI); // tapered edges
        const wave1 = Math.sin(x * 0.08 + t * 4) * (24 * voiceEnergy);
        const wave2 = Math.cos(x * 0.16 - t * 6) * (14 * voiceEnergy);
        const wave3 = (Math.sin(x * 0.32 + t * 10)) * (8 * voiceEnergy);
        const jitter = (isPeerSpeaking || isTalking) ? (Math.random() - 0.5) * 3 : 0;
        
        const y = centerY + (wave1 + wave2 + wave3 + jitter) * envelope;
        if (x === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      }
      ctx.stroke();

      animFrameTransceiverRef.current = requestAnimationFrame(render);
    };

    render();

    return () => {
      if (animFrameTransceiverRef.current) cancelAnimationFrame(animFrameTransceiverRef.current);
    };
  }, [screenMode, isPeerSpeaking, isTalking]);

  // Requirement 3: Start talking (PTT pressed)
  // Automatically switches to Transceiver page and starts microphone voice capture!
  const startTalking = () => {
    if (held.current) return;
    clearSilenceTimer();
    held.current = true;
    setIsTalking(true);
    setIsPeerSpeaking(false);
    
    // Automatically switch to Transceiver page!
    setScreenMode('transceiver');
    sound.playPttStart();
    setLiveTranscript('TRANSMITTING VOICE…');
    lastSpokenTextRef.current = '';
    hasDispatchedRef.current = false;

    if (isNative()) {
      command('start');
    } else {
      try {
        recognitionRef.current?.start();
      } catch (e) {
        held.current = false;
        setIsTalking(false);
        setNotice(String(e));
        startSilenceTimer();
      }
    }
  };

  // Stop talking (PTT released)
  const stopTalking = () => {
    if (!held.current) return;
    held.current = false;
    setIsTalking(false);
    sound.playPttEnd();

    const pendingSpoken = lastSpokenTextRef.current;

    if (isNative()) {
      command('release');
      // Safety net: ensure speech is dispatched if native final CTC decode returned empty
      if (pendingSpoken && !hasDispatchedRef.current) {
        setTimeout(() => {
          if (!hasDispatchedRef.current && pendingSpoken) {
            hasDispatchedRef.current = true;
            console.log('Dispatching safety fallback for STT spoken transcript:', pendingSpoken);
            command('send', { text: pendingSpoken, language: 'en' });
          }
        }, 1200);
      }
    } else {
      try { recognitionRef.current?.stop(); } catch (_) {}
    }

    // Start 30s inactivity countdown back to Idle page
    startSilenceTimer();
  };

  // Filtered Wi-Fi Direct peers: ONLY show Wi-Fi Direct connections!
  const wifiDirectPeers = peers.filter(isWifiDirectPeer);

  // Model actions (Requirement 3: Hugging Face STT & TTS download and selection)
  const handleSelectModel = (model: HFModel) => {
    sound.playClick();
    setActiveModelTag(model.tag);
    command('language', { language: model.tag.split('-')[0] });
    setNotice(`ACTIVATED ${model.name.toUpperCase()} (${model.tag}) OFFLINE MODEL`);
  };

  const handleDownloadModel = (model: HFModel) => {
    sound.playClick();
    setNotice(`STARTING DOWNLOAD: ${model.name.toUpperCase()} FROM HUGGING FACE…`);
    setDownloadingModels(prev => ({
      ...prev,
      [model.tag]: { pct: 0, mbDone: 0, mbTotal: model.sizeMB }
    }));
    command('downloadSelected', { languages: [model.tag] });
  };

  const toggleLanguagesScreen = () => {
    sound.playClick();
    if (screenMode === 'languages') {
      setScreenMode(lastScreenMode);
    } else {
      setLastScreenMode(screenMode);
      setScreenMode('languages');
      command('capabilities');
    }
  };

  // Connection handler (strictly prioritizing Wi-Fi Direct / P2P)
  const handleConnectDevice = (peer: PeerDevice) => {
    if (isPairing && pairingPeerId === peer.id) return;
    sound.playClick();
    setIsPairing(true);
    setPairingPeerId(peer.id);
    setPairingLog(`CONNECTING TO ${peer.name.toUpperCase()}…`);
    setNotice(`PAIRING WITH ${peer.name.toUpperCase()}…`);

    if (pairingTimeoutRef.current) clearTimeout(pairingTimeoutRef.current);
    pairingTimeoutRef.current = setTimeout(() => {
      setIsPairing(false);
      setPairingPeerId(null);
      setNotice('PAIRING TIMEOUT · TAP TO RETRY');
    }, 12000);

    if (isNative() && peer.p2pAddress) {
      command('connectP2p', { p2pAddress: peer.p2pAddress, peerName: peer.name });
    } else if (peer.ip && peer.ip.includes('.')) {
      command('connect', { address: peer.ip, port: peer.port || 8988, pin, peer: peer.raw, peerName: peer.name });
    } else {
      command('connectP2p', { p2pAddress: peer.p2pAddress || peer.id, peerName: peer.name });
    }
  };

  const handleCancelPairing = (e?: React.MouseEvent) => {
    e?.stopPropagation();
    sound.playClick();
    setIsPairing(false);
    setPairingPeerId(null);
    command('cancelRequest');
    setNotice('PAIRING CANCELLED');
    if (pairingTimeoutRef.current) clearTimeout(pairingTimeoutRef.current);
  };

  const handleDisconnect = () => {
    sound.playClick();
    command('disconnect');
    setConnectedPeer(null);
    setIsPairing(false);
    setPairingPeerId(null);
    clearSilenceTimer();
    setScreenMode('devices');
    setNotice('DISCONNECTED FROM PEER');
    triggerDiscovery();
  };

  const handleManualScan = () => {
    sound.playClick();
    triggerDiscovery();
    setNotice('SCANNING WI-FI DIRECT FREQUENCIES…');
  };

  // Requirement 2: Up and Down buttons navigate the device list / model list!
  const handleNavUp = () => {
    sound.playClick();
    if (screenMode === 'devices') {
      setSelectedDeviceIndex(prev => (prev > 0 ? prev - 1 : (wifiDirectPeers.length > 0 ? wifiDirectPeers.length - 1 : 0)));
    } else if (screenMode === 'languages') {
      setSelectedModelIndex(prev => (prev > 0 ? prev - 1 : HF_CATALOGUE.length - 1));
    } else if (screenMode === 'idle') {
      setScreenMode('devices');
    } else if (screenMode === 'transceiver') {
      setScreenMode('idle');
    }
  };

  const handleNavDown = () => {
    sound.playClick();
    if (screenMode === 'devices') {
      setSelectedDeviceIndex(prev => (wifiDirectPeers.length > 0 ? (prev + 1) % wifiDirectPeers.length : 0));
    } else if (screenMode === 'languages') {
      setSelectedModelIndex(prev => (prev + 1) % HF_CATALOGUE.length);
    } else if (screenMode === 'idle') {
      if (connectedPeer) handleDisconnect();
      else setScreenMode('devices');
    } else if (screenMode === 'transceiver') {
      setScreenMode('devices');
    }
  };

  // Auto-scroll selected item into view when navigating via UP/DOWN
  useEffect(() => {
    if (screenMode === 'devices' && wifiDirectPeers.length > 0) {
      const el = document.getElementById(`device-row-${selectedDeviceIndex}`);
      if (el) {
        el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      }
    } else if (screenMode === 'languages' && HF_CATALOGUE.length > 0) {
      const el = document.getElementById(`model-row-${selectedModelIndex}`);
      if (el) {
        el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      }
    }
  }, [selectedDeviceIndex, selectedModelIndex, screenMode, wifiDirectPeers.length]);

  // Keyboard navigation & PTT support
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return;
      if (e.key === 'ArrowUp') {
        handleNavUp();
      } else if (e.key === 'ArrowDown') {
        handleNavDown();
      } else if (e.key === 'ArrowLeft') {
        if (screenMode === 'languages') {
          toggleLanguagesScreen();
        } else if (screenMode === 'transceiver') {
          e.preventDefault();
          command('stopPlayback');
          setIsPeerSpeaking(false);
          sound.playClick();
        } else if (screenMode === 'devices') {
          handleManualScan();
        }
      } else if (e.key === 'Enter') {
        if (screenMode === 'devices' && wifiDirectPeers[selectedDeviceIndex]) {
          handleConnectDevice(wifiDirectPeers[selectedDeviceIndex]);
        } else if (screenMode === 'languages') {
          const model = HF_CATALOGUE[selectedModelIndex];
          if (model) {
            const isInstalled = installedModels.some(
              m => m.toLowerCase() === model.tag.toLowerCase() || m.toLowerCase() === model.tag.split('-')[0].toLowerCase()
            );
            if (isInstalled) handleSelectModel(model);
            else if (!downloadingModels[model.tag]) handleDownloadModel(model);
          }
        }
      } else if (e.key === ' ' || e.code === 'Space') {
        e.preventDefault();
        if (!e.repeat) startTalking();
      }
    };

    const handleKeyUp = (e: KeyboardEvent) => {
      if (e.key === ' ' || e.code === 'Space') {
        e.preventDefault();
        stopTalking();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    window.addEventListener('keyup', handleKeyUp);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
      window.removeEventListener('keyup', handleKeyUp);
    };
  }, [screenMode, wifiDirectPeers, selectedDeviceIndex, selectedModelIndex, connectedPeer, installedModels, downloadingModels]);

  // =========================================================================
  // Click Wheel Button Action Props (Strictly NO 2X and NO Rewind per Req 4)
  // =========================================================================
  const getWheelButtonProps = () => {
    // 1. Languages Screen (Hugging Face Model Downloader & Selector)
    if (screenMode === 'languages') {
      return {
        topText: '▲ UP',
        topAction: handleNavUp,
        topTitle: 'Navigate up in language models',
        bottomText: '▼ DOWN',
        bottomAction: handleNavDown,
        bottomTitle: 'Navigate down in language models',
        leftText: '◄ BACK',
        leftAction: toggleLanguagesScreen,
        leftTitle: 'Return to previous screen',
        rightText: 'REFRESH',
        rightAction: () => {
          sound.playClick();
          command('capabilities');
          setNotice('CHECKING HUGGING FACE & LOCAL PACKS…');
        },
        rightTitle: 'Refresh model status',
      };
    }

    // 2. Available Devices Screen
    if (screenMode === 'devices') {
      return {
        topText: '▲ UP',
        topAction: handleNavUp,
        topTitle: 'Navigate up in device list',
        bottomText: '▼ DOWN',
        bottomAction: handleNavDown,
        bottomTitle: 'Navigate down in device list',
        leftText: 'SCAN',
        leftAction: handleManualScan,
        leftTitle: 'Scan for nearby Wi-Fi Direct peers',
        rightText: 'LANG',
        rightAction: () => {
          sound.playClick();
          toggleLanguagesScreen();
        },
        rightTitle: 'Open Hugging Face Language Models repository',
      };
    }

    // 3. Transceiver Screen (Speech Active)
    if (screenMode === 'transceiver') {
      return {
        topText: '▲ STATUS',
        topAction: () => {
          sound.playClick();
          setScreenMode('idle');
        },
        topTitle: 'Go to status / idle screen',
        bottomText: '▼ DEVICES',
        bottomAction: () => {
          sound.playClick();
          setScreenMode('devices');
        },
        bottomTitle: 'Go to available devices list',
        leftText: '■ STOP',
        leftAction: () => {
          command('stopPlayback');
          setIsPeerSpeaking(false);
          sound.playClick();
        },
        leftTitle: 'Stop audio playback',
        rightText: 'LANG',
        rightAction: () => {
          sound.playClick();
          toggleLanguagesScreen();
        },
        rightTitle: 'Open Hugging Face Language Models repository',
      };
    }

    // 4. Idle Animation Screen (Connected & Standby)
    return {
      topText: '▲ DEVICES',
      topAction: () => {
        sound.playClick();
        setScreenMode('devices');
      },
      topTitle: 'Switch to Available Devices list',
      bottomText: '▼ LANGUAGE',
      bottomAction: () => {
        sound.playClick();
        toggleLanguagesScreen();
      },
      bottomTitle: 'Open Hugging Face Language Models repository',
      leftText: 'SCAN',
      leftAction: handleManualScan,
      leftTitle: 'Scan Wi-Fi Direct frequencies',
      rightText: 'TRANS',
      rightAction: () => {
        sound.playClick();
        setScreenMode('transceiver');
      },
      rightTitle: 'Switch to Transceiver page',
    };
  };

  const wheelProps = getWheelButtonProps();

  return (
    <div className="transceiver-page-wrapper">
      {/* ====================================================================
          THE PHYSICAL HARDWARE DEVICE CHASSIS (THE WHOLE APP)
          ==================================================================== */}
      <div className="handheld-chassis">
        {/* Top Speaker Cutout / Grill Slot */}
        <div className="chassis-speaker-slit" />

        {/* ==================================================================
            THE RETRO BLACK CRT SCREEN BEZEL (FILLS TOP HALF OF SCREEN - 50%)
            ================================================================== */}
        <div className="screen-bezel">
          <div className="pixel-screen">
            {/* Screen Header with Swan Logo and Status */}
            <div className="screen-header">
              <div className="screen-header-brand">
                <img src={swanLogo} alt="Swar Icon" className="screen-brand-swan-icon" />
                <span className="screen-brand-text">SWAR</span>
              </div>

              <div className="screen-header-status">
                {screenMode === 'languages' ? (
                  <>
                    <span className="status-dot-blink" style={{ backgroundColor: '#a855f7', boxShadow: '0 0 6px #a855f7' }} />
                    <span style={{ color: '#c084fc', fontWeight: 700 }}>HF REPO</span>
                    <button
                      onClick={toggleLanguagesScreen}
                      className="screen-action-btn-mini"
                      title="Return to radio screen"
                    >
                      <span>× BACK</span>
                    </button>
                  </>
                ) : screenMode === 'devices' ? (
                  <>
                    <span className="status-dot-blink" />
                    <span>WIFI DIRECT</span>
                    <button
                      onClick={handleManualScan}
                      className="screen-action-btn-mini"
                      title="Scan nearby Wi-Fi Direct frequencies"
                    >
                      <RefreshCw size={10} />
                      <span>SCAN</span>
                    </button>
                    <button
                      onClick={toggleLanguagesScreen}
                      className="screen-lang-header-btn"
                      title="Hugging Face Language Models"
                    >
                      <span>🌐 LANG</span>
                    </button>
                  </>
                ) : screenMode === 'transceiver' ? (
                  <>
                    <span
                      className="status-dot-blink"
                      style={{
                        backgroundColor: isPeerSpeaking ? '#38bdf8' : '#ef4444',
                        boxShadow: `0 0 6px ${isPeerSpeaking ? '#38bdf8' : '#ef4444'}`,
                      }}
                    />
                    <span style={{ color: isPeerSpeaking ? '#38bdf8' : '#ef4444', fontWeight: 700 }}>
                      {isPeerSpeaking ? '● RX VOICE' : isTalking ? '● TX LIVE' : '● STT ACTIVE'}
                    </span>
                    <button
                      onClick={toggleLanguagesScreen}
                      className="screen-lang-header-btn"
                      title="Hugging Face Language Models"
                    >
                      <span>🌐 LANG</span>
                    </button>
                  </>
                ) : (
                  <>
                    <span className="status-dot-blink" />
                    <span>● LINKED</span>
                    {connectedPeer && (
                      <button
                        onClick={handleDisconnect}
                        className="screen-action-btn-mini"
                        title="Disconnect current peer"
                      >
                        <Unlink size={10} />
                        <span>DISC</span>
                      </button>
                    )}
                    <button
                      onClick={toggleLanguagesScreen}
                      className="screen-lang-header-btn"
                      title="Hugging Face Language Models"
                    >
                      <span>🌐 LANG</span>
                    </button>
                  </>
                )}

                {/* Micro Sound Toggle Button in Screen Header */}
                <button
                  onClick={toggleSound}
                  className="screen-action-btn-mini"
                  title="Toggle procedural audio effects"
                >
                  {soundEnabled ? <Volume2 size={11} /> : <VolumeX size={11} />}
                </button>
              </div>
            </div>

            {/* Direct 1-Tap Screen Navigation Tabs Bar */}
            <div className="screen-nav-tabs">
              <button
                className={`screen-nav-tab ${screenMode === 'devices' ? 'active' : ''}`}
                onClick={() => {
                  sound.playClick();
                  setScreenMode('devices');
                }}
              >
                DEVICES
              </button>
              <button
                className={`screen-nav-tab ${screenMode === 'transceiver' ? 'active' : ''}`}
                onClick={() => {
                  sound.playClick();
                  setScreenMode('transceiver');
                }}
              >
                TRANSCEIVER
              </button>
              <button
                className={`screen-nav-tab ${screenMode === 'idle' ? 'active' : ''}`}
                onClick={() => {
                  sound.playClick();
                  setScreenMode('idle');
                }}
              >
                STATUS
              </button>
              <button
                className={`screen-nav-tab lang-nav-tab ${screenMode === 'languages' ? 'active' : ''}`}
                onClick={() => {
                  sound.playClick();
                  toggleLanguagesScreen();
                }}
              >
                🌐 LANGUAGE
              </button>
            </div>

            {/* Dotted Baseline */}
            <div className="screen-dotted-bar">
              ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪
            </div>

            {/* Status Badge Box */}
            <div className="screen-status-badge">
              {screenMode === 'languages'
                ? notice.includes('HUGGING FACE') || notice.includes('ACTIVATED') || notice.includes('STARTING')
                  ? notice
                  : 'HUGGING FACE REPOSITORY · ON-DEMAND STT & TTS MODELS'
                : screenMode === 'devices'
                ? isPairing
                  ? pairingLog
                  : notice
                : screenMode === 'transceiver'
                ? isPeerSpeaking
                  ? `INCOMING AUDIO FROM ${connectedPeer?.name || 'REMOTE PEER'}`
                  : isTalking
                  ? 'MICROPHONE ACTIVE · TRANSMITTING'
                  : 'READY TO TRANSMIT · HOLD CENTER TO TALK'
                : `LINK ACTIVE: ${connectedPeer?.name || 'STANDBY'} · WAITING FOR SPEECH`}
            </div>

            {/* Incoming Connection Request Modal Card right on CRT Screen */}
            {incoming && (
              <div className="screen-incoming-card">
                <div className="incoming-card-title">
                  <span className="status-dot-blink" style={{ backgroundColor: '#38bdf8' }} />
                  <span>► INCOMING LINK REQUEST</span>
                </div>
                <div className="incoming-card-peer">
                  {incoming.name || incoming.peer || incoming.address || 'NEARBY UNIT'}
                </div>
                <div className="incoming-card-meta">
                  [{incoming.transport || 'P2P'}] {incoming.address ? `• ${incoming.address}` : ''}
                </div>
                <div className="incoming-card-actions">
                  <button
                    className="incoming-btn incoming-accept"
                    onClick={() => {
                      sound.playClick();
                      command('respondConnection', { accept: true });
                      setIncoming(null);
                    }}
                  >
                    [ ACCEPT ]
                  </button>
                  <button
                    className="incoming-btn incoming-decline"
                    onClick={() => {
                      sound.playClick();
                      command('respondConnection', { accept: false });
                      setIncoming(null);
                    }}
                  >
                    [ DECLINE ]
                  </button>
                </div>
              </div>
            )}

            {/* ==========================================================
                PAGE: HUGGING FACE LANGUAGE MODELS REPOSITORY (Requirement 3)
                Download & Select STT & TTS Models directly from Hugging Face
                ========================================================== */}
            {screenMode === 'languages' && (
              <div className="screen-models-container" ref={modelListRef}>
                {HF_CATALOGUE.map((model, idx) => {
                  const isSelected = selectedModelIndex === idx;
                  const isInstalled = installedModels.some(
                    m => m.toLowerCase() === model.tag.toLowerCase() || m.toLowerCase() === model.tag.split('-')[0].toLowerCase()
                  );
                  const isActive = activeModelTag.toLowerCase().startsWith(model.tag.split('-')[0].toLowerCase());
                  const dlProgress = downloadingModels[model.tag];
                  const isDownloading = !!dlProgress;

                  return (
                    <div
                      key={model.tag}
                      id={`model-row-${idx}`}
                      onClick={() => {
                        setSelectedModelIndex(idx);
                        sound.playClick();
                        if (isActive) {
                          setNotice(`ALREADY ACTIVE: ${model.name.toUpperCase()}`);
                        } else if (isInstalled) {
                          handleSelectModel(model);
                        } else if (!isDownloading) {
                          handleDownloadModel(model);
                        }
                      }}
                      className={`screen-model-item ${isSelected ? 'is-selected' : ''} ${
                        isActive ? 'is-active' : ''
                      }`}
                    >
                      <div className="model-left-meta">
                        <div className="model-name-text">
                          <span className="model-select-arrow">{isSelected ? '►' : ' '}</span>
                          <span className="model-code-tag">[{model.code}]</span>
                          <span>{model.name}</span>
                          <span className="model-native-name">({model.nativeName})</span>
                        </div>
                        <div className="model-tech-meta">
                          STT: IndicConformer • TTS: FastPitch • {model.sizeMB} MB
                        </div>

                        {/* Real-time Retro CRT Download Progress Bar */}
                        {isDownloading && (
                          <div className="model-download-bar-box">
                            <div className="model-download-bar-track">
                              <div
                                className="model-download-bar-fill"
                                style={{ width: `${Math.min(100, Math.max(6, dlProgress.pct))}%` }}
                              />
                            </div>
                            <div className="model-download-stats">
                              <span>HUGGING FACE: {dlProgress.pct}%</span>
                              <span>
                                {dlProgress.mbDone > 0 ? `${dlProgress.mbDone.toFixed(1)} / ` : ''}
                                {dlProgress.mbTotal > 0 ? `${dlProgress.mbTotal.toFixed(1)} MB` : `${model.sizeMB} MB`}
                              </span>
                            </div>
                          </div>
                        )}
                      </div>

                      <div className="model-right-action">
                        {isActive ? (
                          <span className="model-action-chip active">● ACTIVE</span>
                        ) : isInstalled ? (
                          <button
                            className="model-action-chip select"
                            onClick={(e) => {
                              e.stopPropagation();
                              handleSelectModel(model);
                            }}
                          >
                            SELECT →
                          </button>
                        ) : isDownloading ? (
                          <span className="model-action-chip downloading">
                            {dlProgress.pct}% DL
                          </span>
                        ) : (
                          <button
                            className="model-action-chip download"
                            onClick={(e) => {
                              e.stopPropagation();
                              handleDownloadModel(model);
                            }}
                          >
                            [ DOWNLOAD HF ]
                          </button>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            )}

            {/* ==========================================================
                PAGE 1: AVAILABLE DEVICES TO CONNECT (Requirement 1: Wi-Fi Direct ONLY)
                UP/DOWN Buttons & Touch Navigation Support
                ========================================================== */}
            {screenMode === 'devices' && (
              <div className="screen-device-list-container" ref={deviceListRef}>
                {/* Prominent Quick-Jump Banner to Hugging Face Language Models */}
                <div
                  className="devices-lang-banner"
                  onClick={() => {
                    sound.playClick();
                    toggleLanguagesScreen();
                  }}
                  title="Open Hugging Face STT & TTS Models"
                >
                  <span className="lang-banner-title">🌐 AI VOICE & LANGUAGE MODELS</span>
                  <span className="lang-banner-action">[ OPEN REPO ] ▶</span>
                </div>

                {wifiDirectPeers.length === 0 ? (
                  <div className="screen-radar-search-box">
                    <div className="radar-spinner" />
                    <div style={{ fontSize: '13px', letterSpacing: '0.08em', fontWeight: 700 }}>
                      WI-FI DIRECT RADAR ACTIVE
                    </div>
                    <div style={{ fontSize: '11px', opacity: 0.6, letterSpacing: '0.04em' }}>
                      SEARCHING FOR NEARBY WI-FI DIRECT UNITS…
                    </div>
                    <button
                      onClick={handleManualScan}
                      className="screen-action-btn-mini"
                      style={{ marginTop: '6px', padding: '3px 10px', fontSize: '12px' }}
                    >
                      [ RE-SCAN WI-FI DIRECT ]
                    </button>
                  </div>
                ) : (
                  wifiDirectPeers.map((peer, idx) => {
                    const isSelected = selectedDeviceIndex === idx;
                    const isConnected = connectedPeer?.id === peer.id;
                    const isCurrentlyPairing = isPairing && pairingPeerId === peer.id;

                    return (
                      <div
                        key={peer.id}
                        id={`device-row-${idx}`}
                        onClick={() => {
                          setSelectedDeviceIndex(idx);
                          sound.playClick();
                          if (!isConnected) handleConnectDevice(peer);
                        }}
                        className={`screen-device-item ${isSelected ? 'is-selected' : ''} ${
                          isConnected ? 'is-connected' : ''
                        }`}
                      >
                        <div className="device-left-meta">
                          <span className="device-name-text">
                            <span className="device-select-arrow">{isSelected ? '►' : ' '}</span>
                            {peer.name}
                          </span>
                          <span className="device-transport-tag">
                            [WI-FI DIRECT] {peer.p2pAddress ? `• ${peer.p2pAddress}` : (peer.ip ? `• ${peer.ip}` : '')}
                          </span>
                        </div>

                        <div className="device-right-action">
                          {isCurrentlyPairing ? (
                            <div style={{ display: 'flex', gap: '4px', alignItems: 'center' }}>
                              <span className="device-action-chip pairing">PAIRING…</span>
                              <button
                                onClick={handleCancelPairing}
                                className="screen-action-btn-mini"
                                style={{ padding: '0 4px', fontSize: '10px' }}
                              >
                                ×
                              </button>
                            </div>
                          ) : isConnected ? (
                            <span className="device-action-chip connected">CONNECTED</span>
                          ) : (
                            <span className={`device-action-chip ${isSelected ? 'selected' : 'connect'}`}>
                              {isSelected ? 'TAP TO PAIR' : 'CONNECT →'}
                            </span>
                          )}
                        </div>
                      </div>
                    );
                  })
                )}
              </div>
            )}

            {/* ==========================================================
                PAGE 2: TRANSCEIVER PAGE (VOICE-REACTIVE AUDIO GRAPH & TRANSCRIPT)
                ========================================================== */}
            {screenMode === 'transceiver' && (
              <>
                {/* Voice-Reactive Audio Graph Viewport */}
                <div className="transceiver-graph-container">
                  <canvas
                    ref={transceiverCanvasRef}
                    width={340}
                    height={90}
                    className="transceiver-canvas"
                  />
                  <div className="transceiver-graph-badge">
                    {isPeerSpeaking
                      ? '● RX VOICE ACTIVE'
                      : isTalking
                      ? '● TX LIVE MIC'
                      : 'CARRIER 16.0 kHz'}
                  </div>
                </div>

                {/* Main Live Transcript Display Box */}
                <div className="screen-transcript-box">
                  <div>
                    <div className="transcript-label-mini">
                      LIVE TRANSCRIPTION STREAM • {isPeerSpeaking ? 'REMOTE RECEIVE' : isTalking ? 'LOCAL TRANSMIT' : 'READY'}
                    </div>
                    <div className="transcript-body-text">
                      {liveTranscript}
                      <span className="cursor-block" />
                    </div>
                  </div>

                  <div className="transcript-telemetry-meta">
                    <span>{connectedPeer ? `PEER: ${connectedPeer.name}` : connectionState}</span>
                    <span>{isNative() ? 'NATIVE ITP' : 'RELAY'}</span>
                    <span>AUDIO: OPUS 16kHz</span>
                  </div>
                </div>
              </>
            )}

            {/* ==========================================================
                PAGE 3: IDLE ANIMATION PAGE (RETRO CARRIER OSCILLOSCOPE)
                ========================================================== */}
            {screenMode === 'idle' && (
              <>
                {/* Phosphor-Grey Oscilloscope Carrier Wave Viewport */}
                <div className="screen-idle-animation-box">
                  <canvas
                    ref={idleCanvasRef}
                    width={340}
                    height={95}
                    className="pixel-oscilloscope-canvas"
                  />
                </div>

                {/* Active Link Status & Telemetry Card */}
                <div className="idle-telemetry-card">
                  <div>
                    <div className="idle-telemetry-row">
                      <span style={{ opacity: 0.65 }}>LINK STATUS:</span>
                      <strong style={{ color: '#ffffff' }}>CARRIER SECURED · STANDBY</strong>
                    </div>
                    <div className="idle-telemetry-row">
                      <span style={{ opacity: 0.65 }}>CONNECTED PEER:</span>
                      <strong>{connectedPeer ? connectedPeer.name : 'STANDALONE MODE'}</strong>
                    </div>
                    <div className="idle-telemetry-row">
                      <span style={{ opacity: 0.65 }}>TRANSPORT:</span>
                      <span>{connectedPeer ? connectedPeer.transport : 'MESH DIRECT'}</span>
                    </div>
                  </div>

                  <div style={{ marginTop: '4px', borderTop: '1px dotted rgba(255, 255, 255, 0.15)', paddingTop: '4px' }}>
                    <div className="transcript-label-mini" style={{ marginBottom: '2px' }}>LAST TRANSMISSION</div>
                    <div style={{ fontSize: '13px', color: '#ffffff', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      {liveTranscript}
                    </div>
                  </div>
                </div>
              </>
            )}

            {/* Screen Bottom Telemetry Footer */}
            <div className="screen-footer-bar">
              <button
                className="screen-lang-chip-btn"
                onClick={toggleLanguagesScreen}
                title="Tap to manage Hugging Face STT & TTS models"
              >
                🌐 LANG: {currentHFModel.code} ▼
              </button>
              <span>{isNative() ? 'HARDWARE ACTIVE' : 'WEB DEMO'}</span>
              <span
                onClick={() => {
                  sound.playClick();
                  if (screenMode === 'languages') {
                    setScreenMode(lastScreenMode);
                  } else {
                    setScreenMode(prev =>
                      prev === 'devices' ? 'transceiver' : prev === 'transceiver' ? 'idle' : 'devices'
                    );
                  }
                }}
                style={{ cursor: 'pointer', padding: '2px 4px' }}
                title="Tap to cycle screens"
              >
                {screenMode === 'devices'
                  ? 'PAGE 1/3: DEVICES'
                  : screenMode === 'transceiver'
                  ? 'PAGE 2/3: TRANSCEIVER'
                  : screenMode === 'idle'
                  ? 'PAGE 3/3: IDLE'
                  : 'MODELS: HUGGING FACE'}
              </span>
            </div>
          </div>
        </div>

        {/* ==================================================================
            THE PHYSICAL CLICK WHEEL & CENTER BUTTON (LOWER HALF OF CHASSIS)
            ================================================================== */}
        <div className="click-wheel-container">
          {/* Top Button: Navigates UP in device list or switches to Devices */}
          {wheelProps.topText && (
            <button
              onClick={wheelProps.topAction}
              onTouchStart={(e) => {
                e.preventDefault();
                wheelProps.topAction();
              }}
              className="wheel-label-btn wheel-label-top"
              title={wheelProps.topTitle}
            >
              {wheelProps.topText}
            </button>
          )}

          {/* Bottom Button: Navigates DOWN in device list or Disconnects */}
          {wheelProps.bottomText && (
            <button
              onClick={wheelProps.bottomAction}
              onTouchStart={(e) => {
                e.preventDefault();
                wheelProps.bottomAction();
              }}
              className="wheel-label-btn wheel-label-bottom"
              title={wheelProps.bottomTitle}
            >
              {wheelProps.bottomText}
            </button>
          )}

          {/* Left Button */}
          {wheelProps.leftText && (
            <button
              onClick={wheelProps.leftAction}
              onTouchStart={(e) => {
                e.preventDefault();
                wheelProps.leftAction();
              }}
              className="wheel-label-btn wheel-label-left"
              title={wheelProps.leftTitle}
            >
              {wheelProps.leftText}
            </button>
          )}

          {/* Right Button */}
          {wheelProps.rightText && (
            <button
              onClick={wheelProps.rightAction}
              onTouchStart={(e) => {
                e.preventDefault();
                wheelProps.rightAction();
              }}
              className="wheel-label-btn wheel-label-right"
              title={wheelProps.rightTitle}
            >
              {wheelProps.rightText}
            </button>
          )}

          {/* Center Tactile Button: PUSH TO TALK on Transceiver/Idle, CONNECT on Devices, or SELECT/DL on Languages */}
          <button
            onMouseDown={() => {
              if (screenMode === 'devices') {
                if (isPairing) handleCancelPairing();
                else if (wifiDirectPeers.length > 0 && wifiDirectPeers[selectedDeviceIndex]) {
                  handleConnectDevice(wifiDirectPeers[selectedDeviceIndex]);
                }
              } else if (screenMode === 'languages') {
                const model = HF_CATALOGUE[selectedModelIndex];
                if (model) {
                  const isInstalled = installedModels.some(
                    m => m.toLowerCase() === model.tag.toLowerCase() || m.toLowerCase() === model.tag.split('-')[0].toLowerCase()
                  );
                  if (isInstalled) handleSelectModel(model);
                  else if (!downloadingModels[model.tag]) handleDownloadModel(model);
                }
              } else {
                startTalking();
              }
            }}
            onMouseUp={() => {
              if (screenMode !== 'devices' && screenMode !== 'languages') stopTalking();
            }}
            onTouchStart={e => {
              e.preventDefault();
              if (screenMode === 'devices') {
                if (isPairing) handleCancelPairing();
                else if (wifiDirectPeers.length > 0 && wifiDirectPeers[selectedDeviceIndex]) {
                  handleConnectDevice(wifiDirectPeers[selectedDeviceIndex]);
                }
              } else if (screenMode === 'languages') {
                const model = HF_CATALOGUE[selectedModelIndex];
                if (model) {
                  const isInstalled = installedModels.some(
                    m => m.toLowerCase() === model.tag.toLowerCase() || m.toLowerCase() === model.tag.split('-')[0].toLowerCase()
                  );
                  if (isInstalled) handleSelectModel(model);
                  else if (!downloadingModels[model.tag]) handleDownloadModel(model);
                }
              } else {
                startTalking();
              }
            }}
            onTouchEnd={e => {
              e.preventDefault();
              if (screenMode !== 'devices' && screenMode !== 'languages') stopTalking();
            }}
            onClick={() => {
              if (screenMode === 'devices') {
                if (isPairing) handleCancelPairing();
                else if (wifiDirectPeers.length > 0 && wifiDirectPeers[selectedDeviceIndex]) {
                  handleConnectDevice(wifiDirectPeers[selectedDeviceIndex]);
                }
              } else if (screenMode === 'languages') {
                const model = HF_CATALOGUE[selectedModelIndex];
                if (model) {
                  const isInstalled = installedModels.some(
                    m => m.toLowerCase() === model.tag.toLowerCase() || m.toLowerCase() === model.tag.split('-')[0].toLowerCase()
                  );
                  if (isInstalled) handleSelectModel(model);
                  else if (!downloadingModels[model.tag]) handleDownloadModel(model);
                }
              }
            }}
            className={`wheel-center-btn ${isTalking ? 'is-active' : ''} ${incoming ? 'is-incoming-active' : ''}`}
            title="Push to Talk / Select"
          >
            {screenMode === 'devices' ? (
              <>
                <Zap size={18} className="center-btn-mic" />
                <span className="center-btn-text">
                  {isPairing ? 'CANCEL' : 'CONNECT'}
                </span>
              </>
            ) : screenMode === 'languages' ? (
              <>
                <Zap size={18} className="center-btn-mic" />
                <span className="center-btn-text">
                  {installedModels.some(m => m.toLowerCase() === HF_CATALOGUE[selectedModelIndex]?.tag.toLowerCase() || m.toLowerCase() === HF_CATALOGUE[selectedModelIndex]?.tag.split('-')[0].toLowerCase())
                    ? (activeModelTag.toLowerCase().startsWith(HF_CATALOGUE[selectedModelIndex]?.tag.split('-')[0].toLowerCase()) ? 'ACTIVE' : 'SELECT')
                    : downloadingModels[HF_CATALOGUE[selectedModelIndex]?.tag] ? 'DL...' : 'DOWNLOAD'}
                </span>
              </>
            ) : (
              <>
                <Radio size={18} className="center-btn-mic" />
                <span className="center-btn-text">
                  {isTalking ? 'TALKING' : 'PUSH TO TALK'}
                </span>
              </>
            )}
          </button>
        </div>

        {/* Minimal Bottom Helper Note */}
        <div className="chassis-bottom-note">
          {screenMode === 'devices'
            ? 'USE ▲/▼ TO SELECT · CENTER TO CONNECT'
            : screenMode === 'languages'
            ? 'USE ▲/▼ TO BROWSE · CENTER TO SELECT/DOWNLOAD'
            : 'HOLD CENTER TO TALK · RELEASE TO SEND'}
        </div>
      </div>
    </div>
  );
};
