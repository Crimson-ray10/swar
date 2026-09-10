import React, { useEffect, useState } from 'react';
import { Header } from './components/Header';
import { TransmitterPanel } from './components/TransmitterPanel';
import { ChannelPanel } from './components/ChannelPanel';
import { ReceiverPanel } from './components/ReceiverPanel';
import { ComparisonGauge } from './components/ComparisonGauge';
import { LanguageSpec, RunReport } from './types';

export const App: React.FC = () => {
  const [sessionId] = useState<string>(() => Math.random().toString(36).substring(2, 10));
  const [languages, setLanguages] = useState<LanguageSpec[]>([]);
  const [selectedLanguage, setSelectedLanguage] = useState<string>('hi');
  const [hardwareStatus, setHardwareStatus] = useState<string>('Detecting...');
  const [isProcessing, setIsProcessing] = useState<boolean>(false);

  // Channel configuration state
  const [grossRateBps, setGrossRateBps] = useState<number>(2000);
  const [channelMode, setChannelMode] = useState<string>('bpsk_awgn');
  const [ebN0Db, setEbN0Db] = useState<number>(5.0);
  const [packetLossRate, setPacketLossRate] = useState<number>(0.0);
  const [useFec, setUseFec] = useState<boolean>(true);
  const [seed, setSeed] = useState<number>(1729);

  // Run report state
  const [report, setReport] = useState<RunReport | null>(null);

  useEffect(() => {
    // Fetch system health & capabilities
    fetch('/api/v1/health')
      .then((r) => r.json())
      .then(() => {
        setHardwareStatus('CPU / NVIDIA GPU Ready');
      })
      .catch(() => setHardwareStatus('Backend API Offline'));

    // Fetch supported languages
    fetch('/api/v1/languages')
      .then((r) => r.json())
      .then((data: LanguageSpec[]) => {
        setLanguages(data);
      })
      .catch((err) => console.error('Failed to load languages:', err));
  }, []);

  const handleAudioReady = async (audioBlob: Blob) => {
    setIsProcessing(true);

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
    } catch (err) {
      console.error('Error executing semantic radio run:', err);
      alert('Failed to transmit over simulated channel. Ensure backend is running.');
    } finally {
      setIsProcessing(false);
    }
  };

  return (
    <div className="app-container">
      <Header
        sessionId={sessionId}
        hardwareStatus={hardwareStatus}
        isStreaming={isProcessing}
      />

      <main className="main-content">
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
        />

        <ReceiverPanel
          status={report?.receiver.status || 'idle'}
          reconstructedText={report?.receiver.text || null}
          audioOutputBase64={report?.audio_output_base64 || null}
          latencyMs={report?.latency_ms || null}
          warnings={report?.warnings || []}
        />
      </main>

      <ComparisonGauge
        actualWireBps={report?.transport.actual_wire_bps || 0}
        compressionRatio={report?.transport.compression_ratio_vs_pcm || 0}
        lastReport={report}
      />
    </div>
  );
};
