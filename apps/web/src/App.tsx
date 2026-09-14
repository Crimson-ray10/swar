import React, { useEffect, useState, useRef } from 'react';
import { Header } from './components/Header';
import { Hero } from './components/Hero';
import { TransmitterPanel } from './components/TransmitterPanel';
import { ChannelPanel } from './components/ChannelPanel';
import { ReceiverPanel } from './components/ReceiverPanel';
import { ComparisonGauge } from './components/ComparisonGauge';
import { QueryHistoryDrawer, HistoryItem } from './components/QueryHistoryDrawer';
import { DeviceConnectionModal } from './components/DeviceConnectionModal';
import { LanguageSpec, RunReport, Peer, TransportType, VoiceMessage, WebClientInfo } from './types';

export const App: React.FC = () => {
  const [sessionId] = useState<string>(() => Math.random().toString(36).substring(2, 10));
  const [languages, setLanguages] = useState<LanguageSpec[]>([]);
  const [selectedLanguage, setSelectedLanguage] = useState<string>('en');
  const [hardwareStatus, setHardwareStatus] = useState<string>('Detecting...');
  const [isProcessing, setIsProcessing] = useState<boolean>(false);
  const [activeStageIndex, setActiveStageIndex] = useState<number>(-1);

  // Active section for smooth navigation
  const [activeSection, setActiveSection] = useState<'hero' | 'studio' | 'channel' | 'receiver'>('hero');
  const [historyDrawerOpen, setHistoryDrawerOpen] = useState(false);
  const [transmissionHistory, setTransmissionHistory] = useState<HistoryItem[]>([]);
  const isManualScrollingRef = useRef(false);

  // Multi-Device P2P Networking & Intercom State
  const [devicesModalOpen, setDevicesModalOpen] = useState(false);
  const [peers, setPeers] = useState<Peer[]>([]);
  const [webClients, setWebClients] = useState<WebClientInfo[]>([]);
  const [selfClient, setSelfClient] = useState<WebClientInfo | null>(null);
  const [wsConnectionStatus, setWsConnectionStatus] = useState<'connected' | 'reconnecting' | 'offline'>('connected');
  const [activeTransport, setActiveTransport] = useState<TransportType | null>(null);
  const [connectionState, setConnectionState] = useState<string>('DISCONNECTED');
  const [incomingAlert, setIncomingAlert] = useState<{ text: string; sender: string } | null>(null);

  // Channel configuration state
  const [grossRateBps, setGrossRateBps] = useState<number>(2000);
  const [channelMode, setChannelMode] = useState<string>('bpsk_awgn');
  const [ebN0Db, setEbN0Db] = useState<number>(5.0);
  const [packetLossRate, setPacketLossRate] = useState<number>(0.0);
  const [useFec, setUseFec] = useState<boolean>(true);
  const [seed, setSeed] = useState<number>(1729);

  // Run report state
  const [report, setReport] = useState<RunReport | null>(null);

  const stageTimerRef = useRef<number | null>(null);

  const fetchStatus = () => {
    fetch('/api/v1/health')
      .then((r) => r.json())
      .then((data) => {
        if (data.state) setConnectionState(data.state);
        const transport = data.active_transport && data.active_transport !== 'auto_selecting'
          ? (data.active_transport as string).replace('_', ' ').toUpperCase()
          : null;
        if (transport) {
          setActiveTransport(data.active_transport);
          setHardwareStatus(`VoiceBridge • ${transport}`);
        } else {
          setHardwareStatus('VoiceBridge • P2P Mesh');
        }
      })
      .catch(() => setHardwareStatus('VoiceBridge Offline'));
  };

  const fetchPeers = () => {
    fetch('/api/v1/voicebridge/peers')
      .then((r) => r.json())
      .then((data: Peer[]) => setPeers(data))
      .catch(() => {});
  };

  // Fetch backend health & capabilities with resilient auto-reconnecting WebSocket
  useEffect(() => {
    fetchStatus();
    fetchPeers();

    fetch('/api/v1/languages')
      .then((r) => r.json())
      .then((data: LanguageSpec[]) => {
        setLanguages(data);
      })
      .catch((err) => console.error('Failed to load languages:', err));

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

  // Dynamic scroll listener to update activeSection on scroll (July behavior)
  useEffect(() => {
    const handleScroll = () => {
      if (isManualScrollingRef.current) return;

      const studioEl = document.getElementById('studio');
      const channelEl = document.getElementById('channel');
      const receiverEl = document.getElementById('receiver');

      if (window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 120) {
        setActiveSection('receiver');
        return;
      }

      const viewportMid = window.scrollY + window.innerHeight * 0.35;

      if (receiverEl && viewportMid >= receiverEl.offsetTop) {
        setActiveSection('receiver');
      } else if (channelEl && viewportMid >= channelEl.offsetTop) {
        setActiveSection('channel');
      } else if (studioEl && viewportMid >= studioEl.offsetTop) {
        setActiveSection('studio');
      } else {
        setActiveSection('hero');
      }
    };

    window.addEventListener('scroll', handleScroll, { passive: true });
    handleScroll();
    return () => window.removeEventListener('scroll', handleScroll);
  }, []);

  const scrollToSection = (sectionId: 'hero' | 'studio' | 'channel' | 'receiver') => {
    isManualScrollingRef.current = true;
    setActiveSection(sectionId);
    if (sectionId === 'hero') {
      window.scrollTo({ top: 0, behavior: 'smooth' });
    } else {
      const element = document.getElementById(sectionId);
      if (element) {
        element.scrollIntoView({ behavior: 'smooth', block: 'start' });
      }
    }
    setTimeout(() => {
      isManualScrollingRef.current = false;
    }, 850);
  };

  const handleReset = () => {
    setReport(null);
    setIsProcessing(false);
    setActiveStageIndex(-1);
    if (stageTimerRef.current !== null) window.clearInterval(stageTimerRef.current);
  };

  const handleAudioReady = async (audioBlob: Blob, sampleText?: string, isSample: boolean = false) => {
    setIsProcessing(true);
    setActiveStageIndex(0);

    // Scroll to studio if user started recording
    if (activeSection === 'hero') {
      scrollToSection('studio');
    }

    // Live stage progression animation during transmission
    let currStage = 0;
    if (stageTimerRef.current !== null) window.clearInterval(stageTimerRef.current);
    stageTimerRef.current = window.setInterval(() => {
      currStage = (currStage + 1) % 8;
      setActiveStageIndex(currStage);
    }, 180);

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
      formData.append('gross_rate_bps', grossRateBps.toString());
      formData.append('eb_n0_db', ebN0Db.toString());
      formData.append('packet_loss_rate', packetLossRate.toString());
      formData.append('seed', seed.toString());
      formData.append('use_fec', useFec ? 'true' : 'false');

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
      setActiveStageIndex(7); // Complete all stages
      scrollToSection('receiver'); // Smoothly bring user to the playback & reconstructed sentence card

      // Append to session transmission history
      const now = new Date();
      const timeStr = now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
      setTransmissionHistory((prev) => [
        {
          id: runReport.run_id || `run_${Date.now()}`,
          timestamp: timeStr,
          report: runReport,
        },
        ...prev.slice(0, 19),
      ]);
    } catch (err: any) {
      console.error('Error executing semantic radio run:', err);
      alert(err.message || 'Failed to transmit over simulated channel. Ensure backend is running.');
    } finally {
      if (stageTimerRef.current !== null) window.clearInterval(stageTimerRef.current);
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

  const handleDisconnect = async () => {
    try {
      await fetch('/api/v1/voicebridge/disconnect', { method: 'POST' });
      setConnectionState('DISCONNECTED');
      setActiveTransport(null);
      fetchStatus();
    } catch {}
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

  const handleSendTestMessage = async (text: string) => {
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
    <div className="page-container select-none">
      {/* 1) Sticky Floating Header (Exact July Treatment) */}
      <Header
        sessionId={sessionId}
        hardwareStatus={hardwareStatus}
        isStreaming={isProcessing}
        activeSection={activeSection}
        onNavigate={(sec) => scrollToSection(sec as any)}
        onOpenHistory={() => setHistoryDrawerOpen(true)}
        historyCount={transmissionHistory.length}
        onOpenDevices={() => setDevicesModalOpen(true)}
        peerCount={peers.length}
        isConnected={connectionState === 'CONNECTED'}
        activeTransport={activeTransport}
        connectedClientsCount={webClients.length}
        wsConnectionStatus={wsConnectionStatus}
        onReset={handleReset}
      />

      {/* P2P Multi-Transport Mesh Devices Modal */}
      <DeviceConnectionModal
        isOpen={devicesModalOpen}
        onClose={() => setDevicesModalOpen(false)}
        peers={peers}
        webClients={webClients}
        selfClient={selfClient}
        activeTransport={activeTransport}
        connectionState={connectionState}
        wsStatus={wsConnectionStatus}
        onRefreshPeers={fetchPeers}
        onConnectPeer={handleConnectPeer}
        onQuickConnectLocal={handleQuickConnectLocal}
        onDisconnect={handleDisconnect}
        onSendTestMessage={handleSendTestMessage}
      />

      {/* Incoming Transmission Toast */}
      {incomingAlert && (
        <div style={{
          position: 'fixed',
          top: '76px',
          left: '50%',
          transform: 'translateX(-50%)',
          zIndex: 9998,
          background: 'rgba(15, 23, 42, 0.96)',
          border: '1px solid rgba(99, 102, 241, 0.5)',
          boxShadow: '0 10px 30px rgba(0, 0, 0, 0.8), 0 0 24px rgba(99, 102, 241, 0.35)',
          borderRadius: '12px',
          padding: '12px 22px',
          display: 'flex',
          alignItems: 'center',
          gap: '12px',
          backdropFilter: 'blur(10px)'
        }}>
          <span style={{ width: '10px', height: '10px', borderRadius: '50%', background: '#10b981', boxShadow: '0 0 8px #10b981' }} />
          <div>
            <div style={{ fontSize: '11px', color: '#94a3b8', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
              Incoming Transmission from {incomingAlert.sender}
            </div>
            <div style={{ fontSize: '14px', fontWeight: 600, color: '#ffffff' }}>
              "{incomingAlert.text}"
            </div>
          </div>
        </div>
      )}

      {/* Query / Transmission History Slide-Over Drawer */}
      <QueryHistoryDrawer
        isOpen={historyDrawerOpen}
        onClose={() => setHistoryDrawerOpen(false)}
        history={transmissionHistory}
        onSelectHistoryItem={(item) => {
          setReport(item.report);
          scrollToSection('receiver');
        }}
        onClearHistory={() => setTransmissionHistory([])}
      />

      {/* 2) Section 1: Hero Landing (Exact July Landing Page Architecture) */}
      <Hero
        onGetStarted={() => scrollToSection('studio')}
        onOpenDevices={() => setDevicesModalOpen(true)}
        connectedClientsCount={webClients.length}
      />

      {/* 3) Section 2: Radio Studio (Interactive July MicButton & Transmitter) */}
      <TransmitterPanel
        languages={languages}
        selectedLanguage={selectedLanguage}
        onSelectLanguage={setSelectedLanguage}
        onAudioReady={handleAudioReady}
        rawTranscript={report?.transcript.raw || ''}
        normalizedTranscript={report?.transcript.normalized || ''}
        criticalSpans={report?.transcript.critical_spans || []}
        tokenCount={report ? Math.round(report.transport.source_bits / 14) : 0}
        isProcessing={isProcessing}
      />

      {/* 4) Section 3: RF Channel Impairment Simulator & 8-Stage Flow */}
      <ChannelPanel
        grossRateBps={grossRateBps}
        onChangeGrossRate={setGrossRateBps}
        channelMode={channelMode}
        onChangeChannelMode={setChannelMode}
        ebN0Db={ebN0Db}
        onChangeEbN0Db={setEbN0Db}
        packetLossRate={packetLossRate}
        onChangePacketLossRate={setPacketLossRate}
        useFec={useFec}
        onToggleFec={setUseFec}
        seed={seed}
        onChangeSeed={setSeed}
        deliveryEvents={report?.delivery_events || []}
        measuredBer={report?.transport.measured_ber || 0}
        measuredPer={report?.transport.measured_per || 0}
        onAirBits={report?.transport.wire_bits || 0}
        actualWireBps={report?.transport.actual_wire_bps || 0}
        isProcessing={isProcessing}
        activeStageIndex={activeStageIndex}
        report={report}
      />

      {/* 5) Section 4: Receiver Sink, Reconstruction & Latency Waterfall */}
      <ReceiverPanel
        status={report?.receiver.status || 'idle'}
        reconstructedText={report?.receiver.text || null}
        audioOutputBase64={report?.audio_output_base64 || null}
        latencyMs={report?.latency_ms || null}
        warnings={report?.warnings || []}
      />

      {/* 6) Bandwidth Comparison Gauge & Export Footer Bar */}
      <ComparisonGauge
        actualWireBps={report?.transport.actual_wire_bps || 0}
        compressionRatio={report?.transport.compression_ratio_vs_pcm || 0}
        lastReport={report}
      />
    </div>
  );
};

export default App;
