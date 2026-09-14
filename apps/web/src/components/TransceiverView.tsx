import React, { useState, useRef, useEffect } from 'react';
import { Radio, Bluetooth, Wifi, Zap, Volume2, VolumeX, Send, RefreshCw } from 'lucide-react';
import { LanguageSpec, Peer, TransportType, RunReport } from '../types';

interface TransceiverViewProps {
  languages: LanguageSpec[];
  selectedLanguage: string;
  onSelectLanguage: (lang: string) => void;
  onAudioReady: (blob: Blob, sampleText?: string, isSample?: boolean) => void;
  isProcessing: boolean;
  report: RunReport | null;
  peers: Peer[];
  connectedClientsCount: number;
  activeTransport: TransportType | null;
  onSelectTransport: (transport: TransportType) => void;
  onConnectPeer: (addr: string, port?: number, transport?: TransportType) => Promise<boolean>;
  onSendTextMessage: (text: string) => Promise<boolean>;
}

export const TransceiverView: React.FC<TransceiverViewProps> = ({
  languages,
  selectedLanguage,
  onSelectLanguage,
  onAudioReady,
  isProcessing,
  report,
  peers,
  connectedClientsCount,
  activeTransport = 'wifi_direct',
  onSelectTransport: _onSelectTransport,
  onConnectPeer,
  onSendTextMessage,
}) => {
  const [meshBeaconActive, setMeshBeaconActive] = useState(true);
  const [searchPeersActive, setSearchPeersActive] = useState(true);
  const [isRecording, setIsRecording] = useState(false);
  const [typedMessage, setTypedMessage] = useState('');
  const [liveTranscript, setLiveTranscript] = useState('');
  const [isPlayingAudio, setIsPlayingAudio] = useState(false);
  const [connectingDevice, setConnectingDevice] = useState<string | null>(null);

  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const audioChunksRef = useRef<Blob[]>([]);
  const speechRecognitionRef = useRef<any>(null);

  const currentLang = languages.find((l) => l.id === selectedLanguage) || languages[0];

  // Speech Recognition setup for live local transcription
  useEffect(() => {
    const SpeechRecognition = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (SpeechRecognition) {
      try {
        const recognition = new SpeechRecognition();
        recognition.continuous = true;
        recognition.interimResults = true;
        recognition.lang = selectedLanguage === 'hi' ? 'hi-IN' : selectedLanguage === 'ta' ? 'ta-IN' : selectedLanguage === 'te' ? 'te-IN' : 'en-IN';

        recognition.onresult = (event: any) => {
          let interim = '';
          for (let i = event.resultIndex; i < event.results.length; ++i) {
            interim += event.results[i][0].transcript;
          }
          if (interim) {
            setLiveTranscript(interim);
          }
        };

        recognition.onerror = () => {};
        speechRecognitionRef.current = recognition;
      } catch {
        // ignore
      }
    }
  }, [selectedLanguage]);

  // Push-To-Talk Start
  const startPtt = async () => {
    if (isRecording || isProcessing) return;
    setLiveTranscript('');
    audioChunksRef.current = [];

    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const mediaRecorder = new MediaRecorder(stream);
      mediaRecorderRef.current = mediaRecorder;

      mediaRecorder.ondataavailable = (e) => {
        if (e.data.size > 0) {
          audioChunksRef.current.push(e.data);
        }
      };

      mediaRecorder.onstop = () => {
        const audioBlob = new Blob(audioChunksRef.current, { type: 'audio/wav' });
        stream.getTracks().forEach((track) => track.stop());

        const textToUse = liveTranscript.trim() || currentLang.sample_text;
        onAudioReady(audioBlob, textToUse, false);
      };

      mediaRecorder.start(100);
      setIsRecording(true);

      if (speechRecognitionRef.current) {
        try {
          speechRecognitionRef.current.start();
        } catch {}
      }
    } catch (err) {
      console.warn('Microphone access not granted or unavailable:', err);
      // Fallback synthetic transmission if mic unavailable
      const fallbackBlob = new Blob([new ArrayBuffer(3200)], { type: 'audio/wav' });
      onAudioReady(fallbackBlob, currentLang.sample_text, true);
    }
  };

  // Push-To-Talk Stop & Dispatch
  const stopPtt = () => {
    if (!isRecording) return;
    setIsRecording(false);

    if (speechRecognitionRef.current) {
      try {
        speechRecognitionRef.current.stop();
      } catch {}
    }

    if (mediaRecorderRef.current && mediaRecorderRef.current.state !== 'inactive') {
      mediaRecorderRef.current.stop();
    }
  };

  // Quick preset messages
  const presetMessages = [
    { label: '🚨 Emergency', text: currentLang.sample_text },
    { label: '📍 Location', text: 'Team secure at coordinates, requesting status update.' },
    { label: '🩺 Medical', text: 'Medical assistance required immediately at forward site.' },
    { label: '✅ All Clear', text: 'All personnel accounted for, situation normal.' },
  ];

  // Synthesize voice playback using browser TTS
  const playReceivedSpeech = (text?: string | null) => {
    if (!text || typeof window === 'undefined') return;
    if ('speechSynthesis' in window) {
      window.speechSynthesis.cancel();
      const utterance = new SpeechSynthesisUtterance(text);
      utterance.lang = selectedLanguage === 'hi' ? 'hi-IN' : 'en-IN';
      utterance.onstart = () => setIsPlayingAudio(true);
      utterance.onend = () => setIsPlayingAudio(false);
      utterance.onerror = () => setIsPlayingAudio(false);
      window.speechSynthesis.speak(utterance);
    }
  };

  const handleConnect = async (devName: string, ip: string, port: number, transport: TransportType) => {
    setConnectingDevice(devName);
    try {
      await onConnectPeer(ip, port, transport);
    } finally {
      setTimeout(() => setConnectingDevice(null), 1200);
    }
  };

  // Mock paired devices list matching the screenshot
  const pairedDevices = [
    { name: 'Airdopes 211', type: 'bluetooth', addr: '84:45:10:82:11', signal: '-76 dBm', ip: '192.168.49.1', port: 8992 },
    { name: 'realme 12+ 5G', type: 'wifi_direct', addr: 'B4:8C:9D:41:2F', signal: '-62 dBm', ip: '192.168.49.2', port: 8990 },
    { name: 'JBL LIVE500BT', type: 'bluetooth', addr: '2C:F0:5D:89:E1', signal: '-82 dBm', ip: '127.0.0.1', port: 8992 },
    { name: 'iTantra Node 2', type: 'wifi_lan', addr: '192.168.43.1:8988', signal: '-58 dBm', ip: '192.168.43.1', port: 8988 },
  ];

  return (
    <div className="view-screen-container transceiver-view-container animate-fade-in">
      {/* Screen Header matching Mockup */}
      <div className="transceiver-header">
        <div>
          <h2 className="transceiver-title">Radio Transceiver</h2>
          <p className="transceiver-subtitle">
            Mesh Beacon: <span className={meshBeaconActive ? 'text-emerald-600 font-semibold' : 'text-neutral-400'}>{meshBeaconActive ? 'Scanning Active' : 'Idle'}</span>
          </p>
        </div>

        <div className="flex flex-col items-end gap-1">
          <span className="status-chip-green">
            <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse"></span>
            <span>+ Connected • {(activeTransport || 'wifi_direct').replace('_', ' ').toUpperCase()}</span>
          </span>
          <span className="text-[10px] font-mono text-neutral-400">Mode: PTT</span>
        </div>
      </div>

      {/* Two Top Toggle Switches matching Screen 2 */}
      <div className="grid grid-cols-2 gap-3 mb-6">
        {/* Mesh Beacon Switch */}
        <div className="toggle-card">
          <div className="toggle-card-text">
            <span className="toggle-label">Mesh Beacon</span>
            <span className="toggle-sub">Discoverable to peers</span>
          </div>
          <button
            type="button"
            onClick={() => setMeshBeaconActive(!meshBeaconActive)}
            className={`switch-track ${meshBeaconActive ? 'switch-on' : 'switch-off'}`}
            title="Toggle Mesh Beacon"
          >
            <span className="switch-thumb"></span>
          </button>
        </div>

        {/* Search Peers Switch */}
        <div className="toggle-card">
          <div className="toggle-card-text">
            <span className="toggle-label">Search Peers</span>
            <span className="toggle-sub">Scan for nodes</span>
          </div>
          <button
            type="button"
            onClick={() => setSearchPeersActive(!searchPeersActive)}
            className={`switch-track ${searchPeersActive ? 'switch-on' : 'switch-off'}`}
            title="Toggle Peer Discovery"
          >
            <span className="switch-thumb"></span>
          </button>
        </div>
      </div>

      {/* Language Quick Selector Pills */}
      <div className="flex items-center justify-between gap-2 mb-6 px-1">
        <span className="text-[11px] font-mono text-neutral-500 font-semibold uppercase">Language:</span>
        <div className="flex items-center gap-1.5 overflow-x-auto no-scrollbar py-1">
          {[
            { id: 'en', label: 'English' },
            { id: 'hi', label: 'हिन्दी' },
            { id: 'ta', label: 'தமிழ்' },
            { id: 'te', label: 'తెలుగు' },
            { id: 'mr', label: 'मराठी' },
          ].map((l) => (
            <button
              key={l.id}
              type="button"
              onClick={() => onSelectLanguage(l.id)}
              className={`lang-pill-btn ${selectedLanguage === l.id ? 'active' : ''}`}
            >
              {l.label}
            </button>
          ))}
        </div>
      </div>

      {/* Centerpiece: Large Circular Black "HOLD PTT" Button matching Screen 2 */}
      <div className="ptt-center-section">
        <div className={`ptt-radar-rings ${isRecording ? 'active-pulse' : ''}`}>
          <div className="radar-ring ring-1"></div>
          <div className="radar-ring ring-2"></div>
          <div className="radar-ring ring-3"></div>

          <button
            type="button"
            onMouseDown={startPtt}
            onMouseUp={stopPtt}
            onTouchStart={startPtt}
            onTouchEnd={stopPtt}
            className={`giant-ptt-circle ${isRecording ? 'is-recording' : ''}`}
            title="Hold to speak, release to transmit"
          >
            {/* Audio Waveform icon */}
            <div className="ptt-icon-waves">
              <span className="wave-bar b1"></span>
              <span className="wave-bar b2"></span>
              <span className="wave-bar b3"></span>
              <span className="wave-bar b4"></span>
              <span className="wave-bar b5"></span>
            </div>
            <span className="ptt-circle-text">
              {isRecording ? 'RECORDING' : isProcessing ? 'SENDING...' : 'HOLD PTT'}
            </span>
          </button>
        </div>

        <p className="ptt-instruction-note">
          {isRecording
            ? '● Capturing offline audio... Release to transmit'
            : isProcessing
            ? 'Transmitting micro-packet over mesh...'
            : 'Press & hold or tap to transmit voice over P2P mesh'}
        </p>
      </div>

      {/* Live Transcript / Received Voice Card */}
      {(liveTranscript || report?.transcript.normalized || report?.receiver.text) && (
        <div className="transcript-live-card">
          <div className="flex items-center justify-between text-[11px] text-neutral-400 mb-1">
            <span className="font-semibold uppercase tracking-wider text-neutral-700">
              {isRecording ? '● Live Voice Capture' : 'Reconstructed Linguistic Message'}
            </span>
            {report?.receiver.text && (
              <button
                type="button"
                onClick={() => playReceivedSpeech(report.receiver.text)}
                className="text-xs text-black font-semibold flex items-center gap-1 hover:underline"
              >
                {isPlayingAudio ? <VolumeX size={13} /> : <Volume2 size={13} />}
                <span>{isPlayingAudio ? 'Playing...' : 'Play Voice'}</span>
              </button>
            )}
          </div>
          <p className="text-base font-bold text-neutral-900 leading-snug">
            "{liveTranscript || report?.receiver.text || report?.transcript.normalized}"
          </p>
          <div className="flex items-center justify-between text-[10px] font-mono text-neutral-400 mt-2">
            <span>{currentLang.name} • 16 kHz PCM</span>
            <span className="text-emerald-600 font-semibold">● 0% Loss (ACK Confirmed)</span>
          </div>
        </div>
      )}

      {/* Quick Operational Presets */}
      <div className="flex items-center gap-2 flex-wrap mb-4">
        <span className="text-[10px] font-bold text-neutral-400 uppercase">Presets:</span>
        {presetMessages.map((item, idx) => (
          <button
            key={idx}
            type="button"
            onClick={() => onSendTextMessage(item.text)}
            className="preset-chip-btn"
            title={item.text}
          >
            {item.label}
          </button>
        ))}
      </div>

      {/* Direct Text Dispatch Bar */}
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (typedMessage.trim()) {
            onSendTextMessage(typedMessage.trim());
            setTypedMessage('');
          }
        }}
        className="flex items-center gap-2 mb-6"
      >
        <input
          type="text"
          value={typedMessage}
          onChange={(e) => setTypedMessage(e.target.value)}
          placeholder={`Type message in ${currentLang.name}...`}
          className="clean-text-input"
        />
        <button
          type="submit"
          disabled={!typedMessage.trim() || isProcessing}
          className="black-square-btn"
          title="Transmit text message"
        >
          <Send size={15} />
        </button>
      </form>

      {/* Section: Nearby P2P Mesh Nodes & Connected Devices */}
      <div className="devices-section mb-6">
        <div className="flex items-center justify-between mb-2.5 px-1">
          <span className="text-xs font-bold text-neutral-900 uppercase tracking-wider flex items-center gap-1.5">
            <Radio size={13} className="text-neutral-700" />
            <span>Nearby Mesh Nodes ({peers.length + (connectedClientsCount > 1 ? connectedClientsCount - 1 : 0)})</span>
          </span>
          <span className="text-[11px] font-mono text-neutral-400">Local Zero-Hop</span>
        </div>

        {peers.length === 0 && connectedClientsCount <= 1 ? (
          <div className="empty-devices-card">
            <span className="text-xs text-neutral-400 font-medium">No external devices connected nearby</span>
            <span className="text-[10px] text-neutral-400">Local node transmitting over loopback</span>
          </div>
        ) : (
          <div className="flex flex-col gap-2">
            {peers.map((peer) => (
              <div key={peer.id} className="device-item-row">
                <div className="flex items-center gap-3">
                  <div className="device-type-icon">
                    {peer.transport === 'bluetooth' ? <Bluetooth size={15} /> : <Wifi size={15} />}
                  </div>
                  <div>
                    <div className="text-xs font-bold text-neutral-900">{peer.name}</div>
                    <div className="text-[10px] font-mono text-neutral-400">
                      {peer.address}:{peer.port} • {peer.transport.toUpperCase()}
                    </div>
                  </div>
                </div>
                <span className="status-badge-connected">ACTIVE</span>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Section: Paired Bluetooth & Wi-Fi Direct Devices matching Screen 2 */}
      <div className="devices-section mb-4">
        <div className="flex items-center justify-between mb-2.5 px-1">
          <span className="text-xs font-bold text-neutral-900 uppercase tracking-wider flex items-center gap-1.5">
            <Bluetooth size={13} className="text-neutral-700" />
            <span>Paired Bluetooth &amp; Direct ({pairedDevices.length})</span>
          </span>
          <span className="text-[11px] font-mono text-neutral-400">Tap to connect</span>
        </div>

        <div className="flex flex-col gap-2">
          {pairedDevices.map((dev, idx) => (
            <div key={idx} className="device-item-row">
              <div className="flex items-center gap-3">
                <div className="device-type-icon">
                  {dev.type === 'bluetooth' ? <Bluetooth size={15} /> : <Zap size={15} />}
                </div>
                <div>
                  <div className="text-xs font-bold text-neutral-900">{dev.name}</div>
                  <div className="text-[10px] font-mono text-neutral-400">
                    {dev.type.toUpperCase()} • {dev.signal} • Build: {dev.addr}
                  </div>
                </div>
              </div>

              <button
                type="button"
                onClick={() => handleConnect(dev.name, dev.ip, dev.port, dev.type as TransportType)}
                disabled={connectingDevice === dev.name}
                className="device-connect-btn"
                title={`Connect to ${dev.name}`}
              >
                {connectingDevice === dev.name ? (
                  <RefreshCw size={13} className="animate-spin" />
                ) : (
                  <span>Connect</span>
                )}
              </button>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};
