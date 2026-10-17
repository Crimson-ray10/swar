import React, { useState, useEffect, useRef } from 'react';
import { Mic, ArrowLeft, Volume2, VolumeX, Smartphone, Sparkles } from 'lucide-react';
import { sound } from '../utils/audioEffects';
import './PixelTransceiver.css';
import { openRadio, isNative } from '../services/transceiver';

export type ScreenMode = 'devices' | 'talking' | 'idle';

export interface PeerDevice {
  id: string; name: string; transport: string; ip: string; port?: number;
  p2pAddress?: string; btAddress?: string; raw?: any;
}

const TACTICAL_PHRASES = [
  'Route is clear, team advancing carefully.',
  'Urgent assistance needed at sector 4.',
  'Signal acquired. Radio check over.',
  'Holding position at north perimeter.',
  'All units switch to mesh channel 2.',
  'Medical dispatch en route to staging area.'
];

const LANGUAGES = [
  { code: 'EN_IN', name: 'English (Offline Neural)', speechCode: 'en-IN' },
];

interface PixelTransceiverProps {
  onBackToMain?: () => void;
  onNavigate?: (view: 'transceiver' | 'experience' | 'studio') => void;
}

export const PixelTransceiver: React.FC<PixelTransceiverProps> = ({ onBackToMain, onNavigate }) => {
  // Navigation & Screen Mode
  const [screenMode, setScreenMode] = useState<ScreenMode>('devices');
  const [connectedPeer, setConnectedPeer] = useState<PeerDevice | null>(null);
  const [selectedDeviceIndex, setSelectedDeviceIndex] = useState<number>(0);
  const [isPairing, setIsPairing] = useState<boolean>(false);
  const [pairingLog, setPairingLog] = useState<string>('');

  const [peers, setPeers] = useState<PeerDevice[]>([]);
  const [connectionState, setConnectionState] = useState('DISCONNECTED');
  const [notice, setNotice] = useState('Find a nearby phone to begin.');
  const [incoming, setIncoming] = useState<any>(null);
  const [hostInfo, setHostInfo] = useState('');
  const [address, setAddress] = useState('');
  const [pin, setPin] = useState('');
  const radio = useRef<ReturnType<typeof openRadio> | null>(null);
  const held = useRef(false);
  const command = (action: string, data: any = {}) => radio.current?.command(action, data);
  useEffect(() => {
    const normalize = (d: any): PeerDevice => ({ id: d.id || d.p2pAddress || d.btAddress || d.address,
      name: d.name || d.address || 'Nearby phone', ip: d.address || '', port: d.port,
      transport: d.transport || (d.p2pAddress ? 'Wi-Fi Direct' : 'LAN'),
      p2pAddress: d.p2pAddress, btAddress: d.btAddress, raw: d });
    radio.current = openRadio((type, data) => {
      if (type === 'peer') { const peer = normalize(data); setPeers(old => [...old.filter(p => p.id !== peer.id), peer]); }
      if (type === 'peers') setPeers(data.peers.map(normalize));
      if (type === 'peerLost') setPeers(old => old.filter(p => p.id !== normalize(data).id));
      if (type === 'connection') {
        setConnectionState(data.state); setNotice(data.state);
        setIsPairing(['CONNECTING', 'WAITING_APPROVAL', 'HANDSHAKING'].includes(data.state));
        setPairingLog(data.state);
        if (data.state === 'CONNECTED') {
          setConnectedPeer({ id: data.address || 'connected', name: data.peer || 'Connected peer', ip: data.address || '', transport: data.transport || 'LAN' });
          setIncoming(null);
          setIsPairing(false);
          setScreenMode('talking');
        } else {
          setConnectedPeer(null);
          held.current = false;
          setIsTalking(false);
          if (['DISCONNECTED', 'FAILED'].includes(data.state)) {
            setIsPairing(false);
          }
        }
      }
      if (type === 'connection_request') {
        sound.playConnectChirp();
        setIncoming(data);
      }
      if (['request_cancelled', 'request_declined'].includes(type)) { setIncoming(null); setIsPairing(false); setNotice(type.replace('_', ' ')); }
      if (type === 'host') setHostInfo((data.addresses || []).join(' / ') + ' · PIN ' + data.pin);
      if (['partial', 'recognized', 'sent', 'received'].includes(type) && data.text) {
        setLiveTranscript(data.text);
        if (type === 'received') {
          held.current = false; setIsTalking(false);
          recognitionRef.current?.abort();
          setIsPeerSpeaking(true); setScreenMode('talking');
        }
      }
      if (type === 'speech') {
        setNotice(data.state);
        if (/idle|error|released/i.test(data.state)) { held.current = false; setIsTalking(false); setIsPeerSpeaking(false); }
      }
      if (['notice', 'error', 'download_progress'].includes(type)) {
        setNotice(data.message || data.state || JSON.stringify(data));
        if (type === 'error') { held.current = false; setIsTalking(false); setIsPairing(false); }
      }
    });
    command('language', { language: 'en' });
    command('capabilities'); command('discover');
    return () => radio.current?.close();
  }, []);

  // Audio & Talking
  const [isTalking, setIsTalking] = useState<boolean>(false);
  const [liveTranscript, setLiveTranscript] = useState<string>('READY WHEN YOU ARE.');
  const [isPeerSpeaking, setIsPeerSpeaking] = useState<boolean>(false);
  const [soundEnabled, setSoundEnabled] = useState<boolean>(true);
  const [languageIdx, setLanguageIdx] = useState<number>(0);
  const [eqLevels, setEqLevels] = useState<number[]>([12, 24, 18, 38, 48, 30, 20, 35, 42, 28, 16, 22]);

  // Audio animation canvas ref
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const animFrameRef = useRef<number | null>(null);
  const recognitionRef = useRef<any>(null);

  const currentLanguage = LANGUAGES[languageIdx];

  // Sound effects toggle
  const toggleSound = () => {
    const next = !soundEnabled;
    setSoundEnabled(next);
    sound.setEnabled(next);
    sound.playClick();
  };

  // Initialize SpeechRecognition if available in browser
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
            const finalized = Array.from(event.results as ArrayLike<any>).slice(event.resultIndex).filter((r: any) => r.isFinal).map((r: any) => r[0].transcript).join(' ');
            if (finalized.trim()) command('send', { text: finalized });
          }
        };

        rec.onerror = (e: any) => {
          console.log('SpeechRecognition notice:', e.error);
        };

        rec.onend = () => {
          if (held.current) {
            try { rec.start(); } catch (_) {}
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
  }, [currentLanguage]);

  useEffect(() => { command('language', { language: currentLanguage.speechCode.split('-')[0] }); }, [currentLanguage]);

  // Screen 3: Phosphor Oscilloscope & Pixel Animation Canvas
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let t = 0;
    const render = () => {
      t += 0.04;
      const w = canvas.width;
      const h = canvas.height;

      ctx.clearRect(0, 0, w, h);

      // Draw subtle phosphor green matrix grid lines
      ctx.strokeStyle = 'rgba(195, 245, 59, 0.08)';
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

      // Draw harmonic Lissajous / breathing carrier wave
      ctx.lineWidth = 2;
      ctx.strokeStyle = '#c3f53b';
      ctx.shadowColor = 'rgba(195, 245, 59, 0.8)';
      ctx.shadowBlur = 6;

      ctx.beginPath();
      const centerY = h / 2;
      for (let x = 0; x < w; x += 2) {
        // Multi-frequency harmonic wave with subtle pixel stepping
        const wave1 = Math.sin(x * 0.05 + t) * 16;
        const wave2 = Math.cos(x * 0.02 - t * 0.7) * 8;
        const wave3 = Math.sin(x * 0.12 + t * 1.5) * 4;
        const y = centerY + wave1 + wave2 + wave3;

        // Quantize to 2px pixel grid for authentic retro CRT feel
        const pxY = Math.round(y / 2) * 2;

        if (x === 0) ctx.moveTo(x, pxY);
        else ctx.lineTo(x, pxY);
      }
      ctx.stroke();

      // Secondary ghost wave
      ctx.lineWidth = 1;
      ctx.strokeStyle = 'rgba(195, 245, 59, 0.35)';
      ctx.shadowBlur = 0;
      ctx.beginPath();
      for (let x = 0; x < w; x += 3) {
        const y = centerY + Math.sin(x * 0.04 - t * 1.2) * 22;
        const pxY = Math.round(y / 2) * 2;
        if (x === 0) ctx.moveTo(x, pxY);
        else ctx.lineTo(x, pxY);
      }
      ctx.stroke();

      // Telemetry heartbeat pulses at the ends
      const pulseX = (Math.sin(t * 1.8) * 0.5 + 0.5) * w;
      ctx.fillStyle = '#c3f53b';
      ctx.fillRect(Math.round(pulseX / 4) * 4, centerY - 2, 4, 4);

      animFrameRef.current = requestAnimationFrame(render);
    };

    render();

    return () => {
      if (animFrameRef.current) cancelAnimationFrame(animFrameRef.current);
    };
  }, [screenMode]);

  const [playbackSpeed, setPlaybackSpeed] = useState<number>(1);

  // Audio Equalizer bars animation when talking
  useEffect(() => {
    if (!isTalking && !isPeerSpeaking && screenMode !== 'talking') return;
    const intervalMs = playbackSpeed === 2 ? 45 : 90;
    const interval = setInterval(() => {
      setEqLevels(prev =>
        prev.map(() => Math.floor(Math.random() * 42) + 8)
      );
    }, intervalMs);
    return () => clearInterval(interval);
  }, [isTalking, isPeerSpeaking, screenMode, playbackSpeed]);

  const timersRef = useRef<number[]>([]);

  const clearAllTimers = () => {
    timersRef.current.forEach(id => clearTimeout(id));
    timersRef.current = [];
    if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
      window.speechSynthesis.cancel();
    }
  };

  useEffect(() => {
    return () => clearAllTimers();
  }, []);

  const startTalking = () => {
    if (held.current) return;
    if (!connectedPeer) { setNotice('Connect to a peer before speaking.'); return; }
    if (!isNative() && !recognitionRef.current) { setNotice('Speech recognition unavailable in this browser. Use typed dispatch or Android.'); return; }
    held.current = true; setIsTalking(true); setIsPeerSpeaking(false); setScreenMode('talking');
    setLiveTranscript('LISTENING…');
    if (isNative()) command('start');
    else { try { recognitionRef.current.start(); } catch (e) { held.current = false; setIsTalking(false); setNotice(String(e)); } }
  };
  const stopTalking = () => {
    if (!held.current) return;
    held.current = false; setIsTalking(false);
    if (isNative()) command('release');
    else recognitionRef.current?.stop();
  };
  const handleToggleTalk = () => held.current ? stopTalking() : startTalking();
  const handleSendPhrase = (phrase: string) => {
    if (!connectedPeer) { setNotice('Connect to a peer before sending.'); return; }
    command('send', { text: phrase, language: currentLanguage.speechCode.split('-')[0] });
    setNotice('Message submitted. Delivery requires a peer acknowledgement.');
  };
  const handleConnectDevice = (peer: PeerDevice) => {
    setIsPairing(true);
    setPairingLog('CONNECTING TO ' + peer.name);
    // Prioritize direct LAN TCP connection when IP address is known
    if (peer.ip && peer.ip.includes('.')) {
      command('connect', { address: peer.ip, port: peer.port || 8988, pin, peer: peer.raw });
    } else if (isNative() && peer.p2pAddress) {
      command('connectP2p', { p2pAddress: peer.p2pAddress });
    } else if (isNative() && peer.btAddress) {
      command('connectBluetooth', { btAddress: peer.btAddress });
    } else {
      command('connect', { address: peer.ip, port: peer.port || 8988, pin, peer: peer.raw });
    }
  };

  // Screen Navigation handlers (Top = UP, Bottom = DOWN)
  const handleNavigateUp = () => {
    sound.playClick();
    clearAllTimers();
    if (screenMode === 'idle') {
      setScreenMode('devices');
    } else if (screenMode === 'devices') {
      setScreenMode(connectedPeer ? 'talking' : 'idle');
    } else {
      setScreenMode('idle');
    }
  };

  const handleNavigateDown = () => {
    sound.playClick();
    clearAllTimers();
    if (screenMode === 'devices') {
      setScreenMode('idle');
    } else if (screenMode === 'idle') {
      setScreenMode('devices');
    } else {
      setScreenMode('idle');
    }
  };

  // Talking Screen Controls: Rewind & 2X Speed
  // Keyboard navigation support:
  // ArrowUp / ArrowDown navigate screens (only when not in talking screen)
  // ArrowLeft = Rewind, ArrowRight = 2X Speed, Space = Push To Talk
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return;
      if (e.key === 'ArrowUp') {
        if (screenMode !== 'talking') {
          e.preventDefault();
          handleNavigateUp();
        }
      } else if (e.key === 'ArrowDown') {
        if (screenMode !== 'talking') {
          e.preventDefault();
          handleNavigateDown();
        }
      } else if (e.key === 'ArrowLeft' && screenMode === 'talking') {
        e.preventDefault();
        command('stopPlayback');
      } else if (e.key === 'ArrowRight' && screenMode === 'talking') {
        e.preventDefault();
        command('stopPlayback');
      } else if (e.key === ' ' || e.code === 'Space') {
        e.preventDefault();
        if (!e.repeat) startTalking();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [screenMode, playbackSpeed, isTalking, connectedPeer]);

  // Click Wheel Props per page based on requirements:
  // 1) Push to talk is center button on every page
  // 2) On talking screen: REMOVE UP and DOWN. Left is REWIND, right is 2X SPEED
  // 3) On idle & devices screens: top is UP, bottom is DOWN for screen navigation
  const getWheelButtonProps = () => {
    if (screenMode === 'talking') {
      // Requirement: Remove UP and DOWN from talking screen!
      return {
        topText: '',
        topAction: () => {},
        topTitle: '',
        bottomText: '',
        bottomAction: () => {},
        bottomTitle: '',
        leftText: '■ STOP',
        leftAction: () => {
          command('stopPlayback');
          setIsPeerSpeaking(false);
          sound.playClick();
        },
        leftTitle: 'Stop speech playback',
        rightText: playbackSpeed === 2 ? '1X SPEED' : '2X SPEED',
        rightAction: () => {
          setPlaybackSpeed(prev => (prev === 1 ? 2 : 1));
          sound.playClick();
        },
        rightTitle: 'Toggle 2X audio speed playback',
      };
    }

    if (screenMode === 'devices') {
      return {
        topText: '▲ UP',
        topAction: handleNavigateUp,
        topTitle: 'Navigate to Standby Screen (UP)',
        bottomText: '▼ DOWN',
        bottomAction: handleNavigateDown,
        bottomTitle: 'Navigate to Standby Screen (DOWN)',
        leftText: '',
        leftAction: () => {},
        leftTitle: '',
        rightText: '',
        rightAction: () => {},
        rightTitle: '',
      };
    }

    // screenMode === 'idle'
    return {
      topText: '▲ UP',
      topAction: handleNavigateUp,
      topTitle: 'Navigate to Available Devices Screen (UP)',
      bottomText: '▼ DOWN',
      bottomAction: handleNavigateDown,
      bottomTitle: 'Navigate to Available Devices Screen (DOWN)',
      leftText: '',
      leftAction: () => {},
      leftTitle: '',
      rightText: '',
      rightAction: () => {},
      rightTitle: '',
    };
  };

  const wheelProps = getWheelButtonProps();

  return (
    <div className="transceiver-page-wrapper">
      {/* Top Bar Header */}
      <div className="transceiver-topbar">
        <div className="transceiver-topbar-brand">
          <span>SWAR</span>
          <span style={{ fontSize: '14px', opacity: 0.7 }}>TRANSCVR//01</span>
        </div>

        <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
          {onNavigate && (
            <>
              <button
                onClick={() => onNavigate('experience')}
                className="transceiver-nav-pill"
                title="Switch to SWAR Web Presentation"
              >
                <span>WEB EXPERIENCE</span>
              </button>
              <button
                onClick={() => onNavigate('studio')}
                className="transceiver-nav-pill"
                title="Switch to Audio Studio Dashboard"
              >
                <span>VOICE STUDIO</span>
              </button>
            </>
          )}

          <button
            onClick={toggleSound}
            className="transceiver-nav-pill"
            title="Toggle procedural audio sound effects"
          >
            {soundEnabled ? <Volume2 size={13} /> : <VolumeX size={13} />}
            <span>{soundEnabled ? 'FX ON' : 'FX OFF'}</span>
          </button>

          {onBackToMain && (
            <button
              onClick={onBackToMain}
              className="transceiver-nav-pill"
              title="Return to main presentation"
            >
              <ArrowLeft size={13} />
              <span>EXIT</span>
            </button>
          )}
        </div>
      </div>

      {/* Screen Mode Quick Switcher Bar for Review & Testing */}
      <div style={{ display: 'flex', gap: '6px', marginBottom: '14px', flexWrap: 'wrap', justifyContent: 'center' }}>
        <button
          onClick={() => { stopTalking(); setScreenMode('devices'); command('discover'); if (isNative()) command('discoverP2p'); }}
          className="transceiver-nav-pill"
          style={{
            borderColor: screenMode === 'devices' ? 'var(--swar-phosphor)' : undefined,
            color: screenMode === 'devices' ? 'var(--swar-phosphor)' : undefined
          }}
        >
          <Smartphone size={12} />
          <span>1. AVAILABLE DEVICES</span>
        </button>

        <button
          onClick={() => { stopTalking(); setScreenMode('talking'); }}
          className="transceiver-nav-pill"
          style={{
            borderColor: screenMode === 'talking' ? 'var(--swar-phosphor)' : undefined,
            color: screenMode === 'talking' ? 'var(--swar-phosphor)' : undefined
          }}
        >
          <Mic size={12} />
          <span>2. TRANSCRIPT</span>
        </button>

        <button
          onClick={() => { stopTalking(); setScreenMode('idle'); }}
          className="transceiver-nav-pill"
          style={{
            borderColor: screenMode === 'idle' ? 'var(--swar-phosphor)' : undefined,
            color: screenMode === 'idle' ? 'var(--swar-phosphor)' : undefined
          }}
        >
          <Sparkles size={12} />
          <span>3. STATUS</span>
        </button>
      </div>

      {/* ====================================================================
          THE PHYSICAL HARDWARE DEVICE CHASSIS (Matches Reference Image)
          ==================================================================== */}
      <div className="handheld-chassis">
        {/* Top Speaker Cutout / Grill Slot */}
        <div className="chassis-speaker-slit" />

        {/* The Retro Pixelated CRT Bezel */}
        <div className="screen-bezel">
          <div className="pixel-screen">
            {/* Screen Header */}
            <div className="screen-header">
              <div className="screen-header-brand">
                <div className="screen-brand-icon-box">V</div>
                <span className="screen-brand-text">SWAR</span>
              </div>

              <div className="screen-header-status">
                {screenMode === 'devices' ? (
                  <>
                    <span className="status-dot-blink" />
                    <span>RADAR SCAN</span>
                  </>
                ) : screenMode === 'talking' ? (
                  <>
                    <span className="status-dot-blink" style={{ backgroundColor: '#ef4444', boxShadow: '0 0 6px #ef4444' }} />
                    <span style={{ color: isPeerSpeaking ? '#38bdf8' : '#ef4444' }}>
                      {isPeerSpeaking ? '● RX PEER' : isTalking ? '● TX LIVE' : '● STT READY'}
                    </span>
                    {playbackSpeed === 2 && (
                      <span style={{ fontSize: '11px', background: 'var(--swar-phosphor)', color: 'var(--swar-phosphor-bg)', padding: '1px 4px', borderRadius: '2px', fontWeight: 900 }}>
                        2X
                      </span>
                    )}
                  </>
                ) : (
                  <>
                    <span className="status-dot-blink" />
                    <span>■ STANDBY</span>
                  </>
                )}
              </div>
            </div>

            {/* Dotted Baseline (Exact match from photo) */}
            <div className="screen-dotted-bar">
              ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪
            </div>

            {/* Status Box */}
            <div className="screen-status-badge">
              {screenMode === 'devices'
                ? isPairing ? pairingLog : 'SELECT NEARBY PEER TO CONNECT'
                : screenMode === 'talking'
                ? isPeerSpeaking
                  ? `INCOMING AUDIO FROM ${connectedPeer?.name}`
                  : isTalking
                  ? 'MICROPHONE ACTIVE'
                  : `READY TO TRANSMIT • ${playbackSpeed === 2 ? 'SPEED: 2.0X' : 'SPEED: 1.0X'}`
                : 'NO VOICE ACTIVITY'}
            </div>

            {/* Incoming Connection Request Modal / Card right on CRT Screen */}
            {incoming && (
              <div className="screen-incoming-card">
                <div className="incoming-card-title">
                  <span className="status-dot-blink" style={{ backgroundColor: '#22c55e', boxShadow: '0 0 6px #22c55e' }} />
                  <span>► INCOMING LINK REQUEST</span>
                </div>
                <div className="incoming-card-peer">
                  {incoming.name || incoming.peer || incoming.address || 'NEARBY UNIT'}
                </div>
                <div className="incoming-card-meta">
                  [{incoming.transport || 'LAN'}] {incoming.address ? `• ${incoming.address}` : ''}
                </div>
                <div className="incoming-card-actions">
                  <button
                    className="incoming-btn incoming-accept"
                    onPointerDown={(e) => {
                      e.preventDefault();
                      e.stopPropagation();
                      sound.playClick();
                      command('respondConnection', { accept: true });
                      setIncoming(null);
                    }}
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
                    onPointerDown={(e) => {
                      e.preventDefault();
                      e.stopPropagation();
                      sound.playClick();
                      command('respondConnection', { accept: false });
                      setIncoming(null);
                    }}
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
                SCREEN 1: AVAILABLE DEVICES TO CONNECT
                ========================================================== */}
            {screenMode === 'devices' && (
              <div className="screen-device-list-container">
                {peers.map((peer, idx) => {
                  const isSelected = selectedDeviceIndex === idx;
                  const isConnected = connectedPeer?.id === peer.id;
                  return (
                    <div
                      key={peer.id}
                      onClick={() => {
                        setSelectedDeviceIndex(idx);
                        handleConnectDevice(peer);
                      }}
                      className={`screen-device-item ${isSelected ? 'active' : ''}`}
                    >
                      <div className="device-left-meta">
                        <span className="device-name-text">
                          {isSelected ? '► ' : '  '}
                          {peer.name}
                        </span>
                        <span className="device-transport-tag">
                          [{peer.transport}] • {peer.ip}
                        </span>
                      </div>

                      <div className="device-right-action">
                        <span className="device-signal-bars">
                          {peer.transport === 'web_relay' ? 'RELAY' : 'PEER'}
                        </span>
                        <span className="device-action-hint">
                          {isConnected ? 'CONNECTED' : 'CONNECT'}
                        </span>
                      </div>
                    </div>
                  );
                })}

                <div style={{ fontSize: '11px', textAlign: 'center', opacity: 0.65, marginTop: 'auto', padding: '4px' }}>
                  {peers.length ? 'SELECT A PEER' : 'NO PEERS FOUND · TRY SCAN'}
                </div>
              </div>
            )}

            {/* ==========================================================
                SCREEN 2: CONNECTED & TALKING (LIVE STT TEXT & AUDIO VISUALIZER)
                ========================================================== */}
            {screenMode === 'talking' && (
              <>
                {/* Dynamic Live Audio Equalizer Bars */}
                <div className="live-eq-bars-container">
                  {eqLevels.map((lvl, i) => (
                    <div
                      key={i}
                      className="eq-bar"
                      style={{ height: `${lvl}px` }}
                    />
                  ))}
                </div>

                {/* Main Live Transcript Box */}
                <div className="screen-transcript-box">
                  <div>
                    <div className="transcript-label-mini" style={{ display: 'flex', justifyContent: 'space-between' }}>
                      <span>LIVE TRANSCRIPT (STT) • {isPeerSpeaking ? 'REMOTE RECEIVE' : isTalking ? 'LOCAL TRANSMIT' : 'READY'}</span>
                      {playbackSpeed === 2 && <span style={{ color: 'var(--swar-phosphor)', fontWeight: 700 }}>[2X ACTIVE]</span>}
                    </div>
                    <div className="transcript-body-text">
                      {liveTranscript}
                      <span className="cursor-block" />
                    </div>
                  </div>

                  <div className="transcript-telemetry-meta">
                    <span>{connectionState}</span>
                    <span>{isNative() ? "NATIVE TRANSPORT" : "BACKEND RELAY"}</span>
                    <span>SPEED: {playbackSpeed}.0X</span>
                  </div>
                </div>
              </>
            )}

            {/* ==========================================================
                SCREEN 3: CONNECTED & NOT TALKING (RETRO PIXEL ANIMATION)
                ========================================================== */}
            {screenMode === 'idle' && (
              <>
                {/* Phosphor Oscilloscope Sine Waves Pixel Canvas */}
                <div className="screen-animation-viewport">
                  <canvas
                    ref={canvasRef}
                    width={320}
                    height={90}
                    className="pixel-oscilloscope-canvas"
                  />
                </div>

                {/* Main Transcript Display Box (Exact Match to Image) */}
                <div className="screen-transcript-box">
                  <div className="transcript-label-mini">
                    LIVE TRANSCRIPT
                  </div>
                  <div className="transcript-body-text">
                    {liveTranscript}
                    <span className="cursor-block" />
                  </div>
                </div>
              </>
            )}

            {/* Screen Bottom Telemetry Footer */}
            <div className="screen-footer-bar">
              <span
                onClick={() => {
                  sound.playClick();
                  setLanguageIdx((languageIdx + 1) % LANGUAGES.length);
                }}
                style={{ cursor: 'pointer' }}
                title={`Click to cycle language dialect (Current: ${currentLanguage.name})`}
              >
                {currentLanguage.code} ▾
              </span>
              <span>{connectedPeer ? connectedPeer.name : 'NO PEER'}</span>
            </div>
          </div>
        </div>

        {/* ====================================================================
            THE PHYSICAL CLICK WHEEL & CENTER BUTTON (Matches User Requirements)
            1) PUSH TO TALK in the centre button for every page
            2) Top and bottom buttons as UP and DOWN to navigate screens
            3) In talking(STT text) page: right is 2X SPEED and left is REWIND
            4) In idle page: remove every text from circular buttons except center
            ==================================================================== */}
        <div className="click-wheel-container">
          {/* Top Label: UP on idle/devices, Removed on talking */}
          {wheelProps.topText ? (
            <button
              onPointerDown={(e) => { e.preventDefault(); e.stopPropagation(); wheelProps.topAction(); }}
              onClick={wheelProps.topAction}
              className="wheel-label-btn wheel-label-top"
              title={wheelProps.topTitle}
              aria-label="Navigate Screen Up"
            >
              {wheelProps.topText}
            </button>
          ) : null}

          {/* Left Label: REWIND/STOP on talking, Removed on idle/devices */}
          {wheelProps.leftText ? (
            <button
              onPointerDown={(e) => { e.preventDefault(); e.stopPropagation(); wheelProps.leftAction(); }}
              onClick={wheelProps.leftAction}
              className="wheel-label-btn wheel-label-left"
              title={wheelProps.leftTitle}
              aria-label="Stop speech playback"
            >
              {wheelProps.leftText}
            </button>
          ) : null}

          {/* Right Label: 2X SPEED on talking, Removed on idle/devices */}
          {wheelProps.rightText ? (
            <button
              onPointerDown={(e) => { e.preventDefault(); e.stopPropagation(); wheelProps.rightAction(); }}
              onClick={wheelProps.rightAction}
              className="wheel-label-btn wheel-label-right"
              title={wheelProps.rightTitle}
              aria-label="Toggle 2X Speed"
            >
              {wheelProps.rightText}
            </button>
          ) : null}

          {/* Bottom Label: DOWN on idle/devices, Removed on talking */}
          {wheelProps.bottomText ? (
            <button
              onPointerDown={(e) => { e.preventDefault(); e.stopPropagation(); wheelProps.bottomAction(); }}
              onClick={wheelProps.bottomAction}
              className="wheel-label-btn wheel-label-bottom"
              title={wheelProps.bottomTitle}
              aria-label="Navigate Screen Down"
            >
              {wheelProps.bottomText}
            </button>
          ) : null}

          {/* Center Circular Tactile Button: PUSH TO TALK or ACCEPT LINK on EVERY page */}
          {incoming ? (
            <button
              onPointerDown={(e) => {
                e.preventDefault();
                e.stopPropagation();
                sound.playClick();
                command('respondConnection', { accept: true });
                setIncoming(null);
              }}
              onClick={() => {
                sound.playClick();
                command('respondConnection', { accept: true });
                setIncoming(null);
              }}
              className="wheel-center-btn is-incoming-active"
              title="Accept Incoming Connection"
              aria-label="Accept Connection"
            >
              <span style={{ fontSize: '18px' }}>📞</span>
              <span className="center-btn-text" style={{ color: '#16a34a', fontWeight: 900 }}>
                ACCEPT LINK
              </span>
            </button>
          ) : (
            <button
              onPointerDown={e => { e.preventDefault(); e.currentTarget.setPointerCapture(e.pointerId); startTalking(); }}
              onPointerUp={stopTalking}
              onPointerCancel={stopTalking}
              onLostPointerCapture={stopTalking}
              onClick={e => { if (e.detail === 0) handleToggleTalk(); }}
              className={`wheel-center-btn ${isTalking ? 'is-active' : ''}`}
              title="Push to talk / Tap to toggle voice broadcast"
              aria-label="Push To Talk"
            >
              <Mic size={22} className="center-btn-mic" />
              <span className="center-btn-text">
                PUSH TO TALK
              </span>
            </button>
          )}
        </div>

        {/* Chassis Bottom Caption (Matches reference image) */}
        <div className="chassis-bottom-caption">
          HOLD CENTER TO TALK · RELEASE TO SEND
        </div>
      </div>

      <div className="radio-controls">
        <p role="status">{notice}</p>
        {incoming && <div><p>Connection request from {incoming.name || incoming.peer || 'Nearby phone'}</p>
          <button onClick={() => command('respondConnection', { accept: true })}>Accept</button>
          <button onClick={() => { command('respondConnection', { accept: false }); setIncoming(null); }}>Decline</button></div>}
        <button onClick={() => { command('discover'); if (isNative()) command('discoverP2p'); setScreenMode('devices'); }}>Scan nearby</button>
        <button onClick={() => command('disconnect')}>Disconnect</button>
        {isNative() && <details><summary>Connection & speech settings</summary>
          <button onClick={() => command('host')}>Host LAN session</button><p>{hostInfo}</p>
          <label>Peer IP<input value={address} onChange={e => setAddress(e.target.value)} placeholder="192.168.1.2" /></label>
          <label>Joining PIN<input value={pin} onChange={e => setPin(e.target.value)} inputMode="numeric" /></label>
          <button onClick={() => command('connect', { address, pin, port: 8988 })}>Join LAN</button>
          <button onClick={() => command('downloadSelected', { languages: [currentLanguage.speechCode] })}>Download selected speech model</button>
          <button onClick={() => command('ttsSettings')}>Speech output settings</button>
        </details>}
        {!isNative() && <small>Web mode uses the existing backend relay shared by all connected clients. Browser speech recognition may require internet.</small>}
      </div>
      {/* Quick Tactical Dispatch Buttons */}
      <div style={{ marginTop: '16px', textAlign: 'center' }}>
        <div style={{ fontSize: '11px', color: '#94a3b8', marginBottom: '6px', letterSpacing: '0.06em', textTransform: 'uppercase' }}>
          Quick Test Dispatch
        </div>
        <div className="tactical-phrases-bar">
          {TACTICAL_PHRASES.map((phrase, i) => (
            <button
              key={i}
              onClick={() => handleSendPhrase(phrase)}
              className="tactical-pill"
            >
              "{phrase}"
            </button>
          ))}
        </div>
      </div>
    </div>
  );
};
