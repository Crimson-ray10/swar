import React, { useEffect, useState } from 'react';
import { HomeView } from './components/HomeView';
import { TransceiverView } from './components/TransceiverView';
import { NeuralModelsView } from './components/NeuralModelsView';
import { ArchitectureView } from './components/ArchitectureView';
import { BottomNav, TabType } from './components/BottomNav';
import { NodeIdentityModal } from './components/NodeIdentityModal';
import { LanguageSpec, RunReport, Peer, TransportType, VoiceMessage, WebClientInfo } from './types';
import { Smartphone, Maximize2, Radio } from 'lucide-react';

const DEFAULT_LANGUAGES: LanguageSpec[] = [
  { id: 'en', name: 'English (India)', native_name: 'English', script: 'Latin', unicode_range: [32, 126], mvp: true, asr_provider: 'faster_whisper', asr_lang_code: 'en', tts_provider: 'piper', tts_lang_code: 'en_US', normalizer: 'standard_en', sample_text: 'I need immediate assistance, there is an emergency here.' },
  { id: 'hi', name: 'Hindi', native_name: 'हिन्दी', script: 'Devanagari', unicode_range: [2304, 2431], mvp: true, asr_provider: 'indic_conformer', asr_lang_code: 'hi', tts_provider: 'indicf5', tts_lang_code: 'hi', normalizer: 'indic_nfc', sample_text: 'मुझे तुरंत मदद चाहिए, यहाँ आग लगी है।' },
  { id: 'ta', name: 'Tamil', native_name: 'தமிழ்', script: 'Tamil', unicode_range: [2944, 3071], mvp: true, asr_provider: 'indic_conformer', asr_lang_code: 'ta', tts_provider: 'indicf5', tts_lang_code: 'ta', normalizer: 'indic_nfc', sample_text: 'எனக்கு உடனடி உதவி தேவை, இங்கே அவசரநிலை உள்ளது.' },
  { id: 'te', name: 'Telugu', native_name: 'తెలుగు', script: 'Telugu', unicode_range: [3072, 3199], mvp: true, asr_provider: 'indic_conformer', asr_lang_code: 'te', tts_provider: 'indicf5', tts_lang_code: 'te', normalizer: 'indic_nfc', sample_text: 'నాకు తక్షణ సహాయం కావాలి, ఇక్కడ అగ్ని ప్రమాదం జరిగింది.' },
  { id: 'mr', name: 'Marathi', native_name: 'मराठी', script: 'Devanagari', unicode_range: [2304, 2431], mvp: false, asr_provider: 'indic_conformer', asr_lang_code: 'mr', tts_provider: 'indicf5', tts_lang_code: 'mr', normalizer: 'indic_nfc', sample_text: 'मला तातडीने मदतीची गरज आहे, येथे आग लागली आहे.' },
  { id: 'bn', name: 'Bengali', native_name: 'বাংলা', script: 'Bengali', unicode_range: [2432, 2559], mvp: false, asr_provider: 'indic_conformer', asr_lang_code: 'bn', tts_provider: 'indicf5', tts_lang_code: 'bn', normalizer: 'indic_nfc', sample_text: 'আমার অবিলম্বে সাহায্য দরকার, এখানে আগুন লেগেছে।' },
  { id: 'kn', name: 'Kannada', native_name: 'ಕನ್ನಡ', script: 'Kannada', unicode_range: [3200, 3327], mvp: false, asr_provider: 'indic_conformer', asr_lang_code: 'kn', tts_provider: 'indicf5', tts_lang_code: 'kn', normalizer: 'indic_nfc', sample_text: 'ನನಗೆ ತಕ್ಷಣ ಸಹಾಯ ಬೇಕು, ಇಲ್ಲಿ ತುರ್ತು ಪರಿಸ್ಥಿತಿ ಇದೆ.' },
  { id: 'gu', name: 'Gujarati', native_name: 'ગુજરાતી', script: 'Gujarati', unicode_range: [2688, 2815], mvp: false, asr_provider: 'indic_conformer', asr_lang_code: 'gu', tts_provider: 'indicf5', tts_lang_code: 'gu', normalizer: 'indic_nfc', sample_text: 'મને તાત્કાલિક મદદની જરૂર છે, અહીં કટોકટી છે.' },
  { id: 'ml', name: 'Malayalam', native_name: 'മലയാളം', script: 'Malayalam', unicode_range: [3328, 3455], mvp: false, asr_provider: 'indic_conformer', asr_lang_code: 'ml', tts_provider: 'indicf5', tts_lang_code: 'ml', normalizer: 'indic_nfc', sample_text: 'എനിക്ക് അടിയന്തിര സഹായം വേണം, ഇവിടെ തീപിടുത്തമുണ്ട്.' },
  { id: 'pa', name: 'Punjabi', native_name: 'ਪੰਜਾਬੀ', script: 'Gurmukhi', unicode_range: [2560, 2687], mvp: false, asr_provider: 'indic_conformer', asr_lang_code: 'pa', tts_provider: 'indicf5', tts_lang_code: 'pa', normalizer: 'indic_nfc', sample_text: 'ਮੈਨੂੰ ਤੁਰੰਤ ਮਦਦ ਚਾਹੀਦੀ ਹੈ, ਇੱਥੇ ਐਮਰਜੈਂਸੀ ਹੈ।' },
  { id: 'ur', name: 'Urdu', native_name: 'اردو', script: 'Arabic-Persian', unicode_range: [1536, 1791], mvp: false, asr_provider: 'indic_conformer', asr_lang_code: 'ur', tts_provider: 'indicf5', tts_lang_code: 'ur', normalizer: 'indic_nfc', sample_text: 'مجھے فوری مدد کی ضرورت ہے، یہاں آگ لگی ہے۔' },
];

export const App: React.FC = () => {
  // Navigation & Screen Tab State
  const [activeTab, setActiveTab] = useState<TabType>('home');
  const [callsign, setCallsign] = useState<string>(() => {
    return localStorage.getItem('itantra_callsign') || 'Sarthak Patil';
  });
  const [isProfileOpen, setIsProfileOpen] = useState(false);
  const [isPhoneFrame, setIsPhoneFrame] = useState(true);

  // Network & Audio State
  const [languages, setLanguages] = useState<LanguageSpec[]>(DEFAULT_LANGUAGES);
  const [selectedLanguage, setSelectedLanguage] = useState<string>('en');
  const [isProcessing, setIsProcessing] = useState<boolean>(false);
  const [peers, setPeers] = useState<Peer[]>([]);
  const [webClients, setWebClients] = useState<WebClientInfo[]>([]);
  const [, setSelfClient] = useState<WebClientInfo | null>(null);
  const [wsConnectionStatus, setWsConnectionStatus] = useState<'connected' | 'reconnecting' | 'offline'>('connected');
  const [activeTransport, setActiveTransport] = useState<TransportType | null>(null);
  const [, setConnectionState] = useState<string>('DISCONNECTED');
  const [incomingAlert, setIncomingAlert] = useState<{ text: string; sender: string } | null>(null);
  const [report, setReport] = useState<RunReport | null>(null);

  const fetchStatus = () => {
    fetch('/api/v1/health')
      .then((r) => r.json())
      .then((data) => {
        if (data.state) setConnectionState(data.state);
        const transport = data.active_transport && data.active_transport !== 'auto_selecting'
          ? (data.active_transport as TransportType)
          : null;
        if (transport) {
          setActiveTransport(transport);
        }
      })
      .catch(() => {});
  };

  const fetchPeers = () => {
    fetch('/api/v1/voicebridge/peers')
      .then((r) => r.json())
      .then((data: Peer[]) => setPeers(data))
      .catch(() => {});
  };

  useEffect(() => {
    fetchStatus();
    fetchPeers();

    fetch('/api/v1/languages')
      .then((r) => r.json())
      .then((data: LanguageSpec[]) => {
        if (Array.isArray(data) && data.length > 0) {
          setLanguages(data);
        }
      })
      .catch(() => {});

    let ws: WebSocket | null = null;
    let reconnectTimer: number | null = null;
    let pingInterval: number | null = null;
    let isDisposed = false;

    const connectWs = () => {
      if (isDisposed) return;
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const wsUrl = `${protocol}//${window.location.host}/api/v1/voicebridge/ws`;

      try {
        setWsConnectionStatus((prev) => (prev === 'connected' ? 'connected' : 'reconnecting'));
        ws = new WebSocket(wsUrl);

        ws.onopen = () => {
          setWsConnectionStatus('connected');
          fetchStatus();
          if (pingInterval) window.clearInterval(pingInterval);
          pingInterval = window.setInterval(() => {
            if (ws && ws.readyState === WebSocket.OPEN) {
              ws.send(JSON.stringify({ action: 'ping' }));
            }
          }, 10000);
        };

        ws.onmessage = (evt) => {
          try {
            const payload = JSON.parse(evt.data);
            if (payload.event === 'init') {
              if (payload.data?.peers) setPeers(payload.data.peers);
              if (payload.data?.webClients) setWebClients(payload.data.webClients);
              if (payload.data?.selfClient) setSelfClient(payload.data.selfClient);
              if (payload.data?.status?.state) setConnectionState(payload.data.status.state);
              if (payload.data?.status?.activeTransport) setActiveTransport(payload.data.status.activeTransport);
            } else if (payload.event === 'web_clients_updated') {
              setWebClients(payload.data || []);
            } else if (payload.event === 'peer_found' || payload.event === 'peer_updated') {
              const peer = payload.data as Peer;
              setPeers((prev) => {
                const idx = prev.findIndex((p) => p.id === peer.id);
                if (idx >= 0) {
                  const next = [...prev];
                  next[idx] = peer;
                  return next;
                }
                return [...prev, peer];
              });
            } else if (payload.event === 'peer_lost') {
              const peer = payload.data as Peer;
              setPeers((prev) => prev.filter((p) => p.id !== peer.id));
            } else if (payload.event === 'supervisor_state' || payload.event === 'transport_recovered') {
              fetchStatus();
            } else if (payload.event === 'voice_message_received') {
              const msg = payload.data as VoiceMessage;
              setIncomingAlert({ text: msg.text, sender: msg.senderId });
              setTimeout(() => setIncomingAlert(null), 6000);
            } else if (payload.event === 'tts_playback_started') {
              if (payload.data?.audioBase64) {
                const audio = new Audio(`data:audio/wav;base64,${payload.data.audioBase64}`);
                audio.play().catch(() => {});
              }
            }
          } catch {
            // ignore
          }
        };

        ws.onclose = () => {
          if (pingInterval) window.clearInterval(pingInterval);
          if (!isDisposed) {
            setWsConnectionStatus('reconnecting');
            reconnectTimer = window.setTimeout(connectWs, 2000);
          }
        };

        ws.onerror = () => {
          if (ws) ws.close();
        };
      } catch {
        setWsConnectionStatus('offline');
        if (!isDisposed) {
          reconnectTimer = window.setTimeout(connectWs, 3000);
        }
      }
    };

    connectWs();

    return () => {
      isDisposed = true;
      if (reconnectTimer) window.clearTimeout(reconnectTimer);
      if (pingInterval) window.clearInterval(pingInterval);
      if (ws) ws.close();
    };
  }, []);

  const handleSaveCallsign = (newName: string) => {
    setCallsign(newName);
    localStorage.setItem('itantra_callsign', newName);
  };

  const handleAudioReady = async (audioBlob: Blob, sampleText?: string, isSample: boolean = false) => {
    setIsProcessing(true);

    try {
      const formData = new FormData();
      formData.append('audio', audioBlob, 'utterance.wav');
      formData.append('language', selectedLanguage);
      if (sampleText && sampleText.trim().length > 0) {
        formData.append('clientHint', sampleText.trim());
        formData.append('sample_text', sampleText.trim());
      }
      if (isSample) {
        formData.append('is_sample', 'true');
      }
      formData.append('gross_rate_bps', '2000');
      formData.append('eb_n0_db', '5.0');
      formData.append('packet_loss_rate', '0.0');
      formData.append('seed', '1729');
      formData.append('use_fec', 'true');

      const res = await fetch('/api/v1/runs', {
        method: 'POST',
        body: formData,
      });

      if (!res.ok) {
        const errJson = await res.json().catch(() => ({}));
        throw new Error(errJson.error || `API error: ${res.statusText}`);
      }

      const runReport: RunReport = await res.json();
      setReport(runReport);
    } catch (err: any) {
      console.error('Error executing voice transmission run:', err);
    } finally {
      setIsProcessing(false);
    }
  };

  const handleConnectPeer = async (address: string, port?: number, transport?: TransportType) => {
    try {
      const res = await fetch('/api/v1/voicebridge/connect-peer', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ address, port, transport }),
      });
      const data = await res.json();
      if (data.success) {
        setConnectionState('CONNECTED');
        setActiveTransport(data.activeTransport || transport || 'wifi_lan');
        fetchStatus();
        return true;
      }
      return false;
    } catch {
      return false;
    }
  };

  const handleQuickConnectLocal = async (transport: TransportType = 'wifi_direct') => {
    try {
      const res = await fetch('/api/v1/voicebridge/quick-connect-local', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ transport }),
      });
      const data = await res.json();
      if (data.success) {
        setConnectionState('CONNECTED');
        setActiveTransport(data.activeTransport || transport);
        fetchStatus();
        return true;
      }
      return false;
    } catch {
      return false;
    }
  };

  const handleSendTextMessage = async (text: string) => {
    try {
      const res = await fetch('/api/v1/voicebridge/send', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text, language: selectedLanguage }),
      });
      const data = await res.json();
      return !!data.success;
    } catch {
      return false;
    }
  };

  return (
    <div className="app-viewport-shell">
      {/* Top Desktop Presentation Bar */}
      <header className="desktop-preview-bar select-none">
        <div className="flex items-center gap-2.5">
          <span className="w-2.5 h-2.5 rounded-full bg-emerald-500 animate-pulse"></span>
          <span className="text-xs font-mono font-bold tracking-wider text-neutral-300">
            iTantra Mesh Node
          </span>
          <span className="text-[11px] font-mono text-neutral-400 bg-neutral-800 px-2 py-0.5 rounded-md">
            {callsign}
          </span>
        </div>

        <div className="flex items-center gap-3">
          <div className="flex items-center gap-1.5 text-xs text-neutral-400 font-mono">
            <Radio size={13} className="text-emerald-400" />
            <span>{wsConnectionStatus === 'connected' ? 'WebSocket Live' : wsConnectionStatus}</span>
          </div>

          <div className="h-4 w-px bg-neutral-700"></div>

          <button
            type="button"
            onClick={() => setIsPhoneFrame((prev) => !prev)}
            className="flex items-center gap-1.5 text-xs font-medium px-2.5 py-1 rounded-lg bg-neutral-800 hover:bg-neutral-700 text-neutral-200 transition border border-neutral-700"
            title="Toggle between phone mockup and expanded canvas"
          >
            {isPhoneFrame ? (
              <>
                <Maximize2 size={13} />
                <span>Expanded</span>
              </>
            ) : (
              <>
                <Smartphone size={13} />
                <span>Phone Frame</span>
              </>
            )}
          </button>
        </div>
      </header>

      {/* Incoming Transmission Toast Notification */}
      {incomingAlert && (
        <div className="fixed top-12 left-1/2 -translate-x-1/2 z-[9999] bg-neutral-900 border border-neutral-700 text-white px-5 py-3 rounded-2xl shadow-2xl flex items-center gap-3 animate-fade-in max-w-sm">
          <span className="w-2.5 h-2.5 rounded-full bg-emerald-400 animate-ping"></span>
          <div>
            <div className="text-[10px] font-mono uppercase tracking-wider text-neutral-400">
              Transmission from {incomingAlert.sender}
            </div>
            <div className="text-xs font-semibold text-white mt-0.5">
              "{incomingAlert.text}"
            </div>
          </div>
        </div>
      )}

      {/* Main Mobile App Frame */}
      <main
        className={`mobile-device-frame ${!isPhoneFrame ? '!max-w-2xl !h-auto !min-h-[840px]' : ''}`}
      >
        {/* Sleek Dynamic Island / Phone Notch Pill */}
        {isPhoneFrame && <div className="phone-notch-pill"></div>}

        {/* Tab Viewport Content */}
        <div className="flex-1 w-full flex flex-col overflow-hidden">
          {activeTab === 'home' && (
            <HomeView
              onGetStarted={() => setActiveTab('transceiver')}
              onOpenProfile={() => setIsProfileOpen(true)}
              onOpenArchitecture={() => setActiveTab('architecture')}
              callsign={callsign}
              connectedNodesCount={peers.length + 1}
            />
          )}

          {activeTab === 'transceiver' && (
            <TransceiverView
              languages={languages}
              selectedLanguage={selectedLanguage}
              onSelectLanguage={setSelectedLanguage}
              onAudioReady={handleAudioReady}
              isProcessing={isProcessing}
              report={report}
              peers={peers}
              connectedClientsCount={webClients.length}
              activeTransport={activeTransport}
              onSelectTransport={handleQuickConnectLocal}
              onConnectPeer={handleConnectPeer}
              onSendTextMessage={handleSendTextMessage}
            />
          )}

          {activeTab === 'models' && <NeuralModelsView />}

          {activeTab === 'architecture' && <ArchitectureView callsign={callsign} />}
        </div>

        {/* Bottom Navigation Dock */}
        <BottomNav
          activeTab={activeTab}
          onSelectTab={setActiveTab}
          isPttActive={isProcessing}
        />
      </main>

      {/* Node Identity & Hardware Modal Sheet (Screen 4) */}
      <NodeIdentityModal
        isOpen={isProfileOpen}
        onClose={() => setIsProfileOpen(false)}
        callsign={callsign}
        onSaveCallsign={handleSaveCallsign}
        activeTransport={activeTransport ? activeTransport.replace('_', ' ').toUpperCase() : 'Wi-Fi Direct P2P + Bluetooth 5.x'}
      />
    </div>
  );
};

export default App;
