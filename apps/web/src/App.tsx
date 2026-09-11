import React, { useEffect, useState, useRef } from 'react';
import { Header } from './components/Header';
import { Hero } from './components/Hero';
import { TransmitterPanel } from './components/TransmitterPanel';
import { ChannelPanel } from './components/ChannelPanel';
import { ReceiverPanel } from './components/ReceiverPanel';
import { ComparisonGauge } from './components/ComparisonGauge';
import { QueryHistoryDrawer, HistoryItem } from './components/QueryHistoryDrawer';
import { LanguageSpec, RunReport } from './types';

export const App: React.FC = () => {
  const [sessionId] = useState<string>(() => Math.random().toString(36).substring(2, 10));
  const [languages, setLanguages] = useState<LanguageSpec[]>([]);
  const [selectedLanguage, setSelectedLanguage] = useState<string>('hi');
  const [hardwareStatus, setHardwareStatus] = useState<string>('Detecting...');
  const [isProcessing, setIsProcessing] = useState<boolean>(false);
  const [activeStageIndex, setActiveStageIndex] = useState<number>(-1);

  // Active section for smooth navigation
  const [activeSection, setActiveSection] = useState<'hero' | 'studio' | 'channel' | 'receiver'>('hero');
  const [historyDrawerOpen, setHistoryDrawerOpen] = useState(false);
  const [transmissionHistory, setTransmissionHistory] = useState<HistoryItem[]>([]);
  const isManualScrollingRef = useRef(false);

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

  // Fetch backend health & capabilities
  useEffect(() => {
    fetch('/api/v1/health')
      .then((r) => r.json())
      .then(() => {
        setHardwareStatus('CPU / GPU Ready');
      })
      .catch(() => setHardwareStatus('Backend API Offline'));

    fetch('/api/v1/languages')
      .then((r) => r.json())
      .then((data: LanguageSpec[]) => {
        setLanguages(data);
      })
      .catch((err) => console.error('Failed to load languages:', err));
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

  const handleAudioReady = async (audioBlob: Blob, _sampleText?: string) => {
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
        throw new Error(`API error: ${res.statusText}`);
      }

      const runReport: RunReport = await res.json();
      setReport(runReport);
      setActiveStageIndex(7); // Complete all stages

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
    } catch (err) {
      console.error('Error executing semantic radio run:', err);
      alert('Failed to transmit over simulated channel. Ensure backend is running.');
    } finally {
      if (stageTimerRef.current !== null) window.clearInterval(stageTimerRef.current);
      setIsProcessing(false);
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
        onReset={handleReset}
      />

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
      <Hero onGetStarted={() => scrollToSection('studio')} />

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
