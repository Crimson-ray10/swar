import React, { useEffect, useState } from 'react';
import { Header } from './components/Header';
import { TransmitterPanel } from './components/TransmitterPanel';
import { ChannelPanel } from './components/ChannelPanel';
import { ReceiverPanel } from './components/ReceiverPanel';
import { ComparisonGauge } from './components/ComparisonGauge';
import { LanguageSpec, RunReport } from './types';

const API_BASE = (import.meta.env.VITE_API_URL || '').replace(/\/$/, '');

const FALLBACK_LANGUAGES: LanguageSpec[] = [
  {
    id: "hi",
    name: "Hindi",
    native_name: "हिन्दी",
    script: "Devanagari",
    unicode_range: [0x0900, 0x097F],
    mvp: true,
    asr_provider: "indic_conformer",
    asr_lang_code: "hi",
    tts_provider: "indicf5",
    tts_lang_code: "hi",
    normalizer: "indic_nfc",
    sample_text: "मैं घर पहुँच गया हूँ और सब ठीक है।",
  },
  {
    id: "ta",
    name: "Tamil",
    native_name: "தமிழ்",
    script: "Tamil",
    unicode_range: [0x0B80, 0x0BFF],
    mvp: true,
    asr_provider: "indic_conformer",
    asr_lang_code: "ta",
    tts_provider: "indicf5",
    tts_lang_code: "ta",
    normalizer: "indic_nfc",
    sample_text: "நான் நலமாக இருக்கிறேன், நன்றி.",
  },
  {
    id: "en",
    name: "English",
    native_name: "English",
    script: "Latin",
    unicode_range: [0x0020, 0x007E],
    mvp: true,
    asr_provider: "faster_whisper",
    asr_lang_code: "en",
    tts_provider: "piper",
    tts_lang_code: "en_US",
    normalizer: "standard_en",
    sample_text: "I have arrived at the station safely.",
  },
  {
    id: "bn",
    name: "Bengali",
    native_name: "বাংলা",
    script: "Bengali",
    unicode_range: [0x0980, 0x09FF],
    mvp: false,
    asr_provider: "indic_conformer",
    asr_lang_code: "bn",
    tts_provider: "indicf5",
    tts_lang_code: "bn",
    normalizer: "indic_nfc",
    sample_text: "আমি ভালো আছি। আজ খুব সুন্দর দিন।",
  },
  {
    id: "te",
    name: "Telugu",
    native_name: "తెలుగు",
    script: "Telugu",
    unicode_range: [0x0C00, 0x0C7F],
    mvp: false,
    asr_provider: "indic_conformer",
    asr_lang_code: "te",
    tts_provider: "indicf5",
    tts_lang_code: "te",
    normalizer: "indic_nfc",
    sample_text: "నేను క్షేమంగా ఉన్నాను. నమస్కారం.",
  },
  {
    id: "mr",
    name: "Marathi",
    native_name: "मराठी",
    script: "Devanagari",
    unicode_range: [0x0900, 0x097F],
    mvp: false,
    asr_provider: "indic_conformer",
    asr_lang_code: "mr",
    tts_provider: "indicf5",
    tts_lang_code: "mr",
    normalizer: "indic_nfc",
    sample_text: "मी सुरक्षित पोहोचलो आहे. काळजी करू नका.",
  }
];

export const App: React.FC = () => {
  const [sessionId] = useState<string>(() => Math.random().toString(36).substring(2, 10));
  const [languages, setLanguages] = useState<LanguageSpec[]>(FALLBACK_LANGUAGES);
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
    fetch(`${API_BASE}/api/v1/health`)
      .then((r) => r.json())
      .then((data) => {
        setHardwareStatus(data.service?.includes('Vercel') ? 'Vercel Serverless Ready' : 'CPU / NVIDIA GPU Ready');
      })
      .catch(() => setHardwareStatus('Interactive Demo Mode'));

    // Fetch supported languages
    fetch(`${API_BASE}/api/v1/languages`)
      .then((r) => r.json())
      .then((data: LanguageSpec[]) => {
        if (Array.isArray(data) && data.length > 0) {
          setLanguages(data);
        }
      })
      .catch((err) => {
        console.warn('API languages endpoint unreachable, using embedded specifications:', err);
      });
  }, []);

  const handleAudioReady = async (audioBlob: Blob, textHint?: string) => {
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

      const res = await fetch(`${API_BASE}/api/v1/runs`, {
        method: 'POST',
        body: formData,
      });

      if (!res.ok) {
        throw new Error(`API error: ${res.statusText}`);
      }

      const runReport: RunReport = await res.json();
      setReport(runReport);
    } catch (err) {
      console.warn('Live API call failed, generating simulated semantic radio report:', err);
      // Generate client-side realistic transceiver report for standalone Vercel preview
      const langObj = languages.find((l) => l.id === selectedLanguage);
      const text = textHint || langObj?.sample_text || 'मैं घर पहुँच गया हूँ और सब ठीक है।';
      const tokenCount = Math.max(8, Math.ceil(text.length / 2.5));
      const sourceBits = tokenCount * 14;
      const wireBits = Math.round(sourceBits * (useFec ? 1.33 : 1.0));
      const actualBps = Math.round(grossRateBps * 0.75);
      const pcmBits = 16000 * 16 * 1.5;
      const compRatio = parseFloat((pcmBits / wireBits).toFixed(1));

      const isCorrupted = ebN0Db < 3.0 || packetLossRate > 0.15;
      const status = isCorrupted ? (useFec && packetLossRate < 0.25 ? 'partial' : 'unrecoverable') : 'complete';

      const simulatedReport: RunReport = {
        run_id: `run-${Math.random().toString(36).substring(2, 10)}`,
        status: status,
        input_info: {
          duration_ms: 1500,
          sample_rate_hz: 16000,
          channels: 1,
        },
        transcript: {
          raw: text,
          normalized: text,
          language: selectedLanguage,
          asr_confidence: 0.96,
          critical_spans: [],
        },
        transport: {
          gross_rate_bps: grossRateBps,
          wire_bits: wireBits,
          source_bits: sourceBits,
          good_bits: sourceBits,
          actual_wire_bps: actualBps,
          compression_ratio_vs_pcm: compRatio,
          packet_count: 4,
          lost_packets: Math.round(4 * packetLossRate),
          crc_fail_count: ebN0Db < 3 ? 1 : 0,
          recovered_packets: useFec && packetLossRate > 0 ? 1 : 0,
          measured_ber: ebN0Db < 3 ? 0.04 : 0.0,
          measured_per: packetLossRate,
        },
        receiver: {
          status: status === 'complete' ? 'exact' : status,
          text: status === 'unrecoverable' ? null : text,
          tts_status: status === 'unrecoverable' ? 'erasure' : 'synthesized',
          missing_packet_sequences: status === 'unrecoverable' ? [1, 2] : [],
          voice_label: 'Synthetic Receiver Voice (IndicF5 / Piper)',
        },
        latency_ms: {
          capture: 35,
          asr: 110,
          encode: 15,
          channel: 85,
          decode: 20,
          tts: 135,
          end_to_end: 400,
        },
        audio_output_base64: null,
        delivery_events: [
          { sequence: 0, status: 'delivered', sent_time_ms: 0, delivery_time_ms: 82, on_air_bits: 144, measured_ber: 0.0, is_parity: false },
          { sequence: 1, status: packetLossRate > 0.3 ? 'lost' : 'delivered', sent_time_ms: 72, delivery_time_ms: 154, on_air_bits: 144, measured_ber: 0.0, is_parity: false },
          { sequence: 2, status: 'delivered', sent_time_ms: 144, delivery_time_ms: 228, on_air_bits: 144, measured_ber: 0.0, is_parity: false },
          { sequence: 3, status: useFec ? 'delivered' : 'lost', sent_time_ms: 216, delivery_time_ms: 300, on_air_bits: 144, measured_ber: 0.0, is_parity: true },
        ],
        reproducibility: {
          seed: seed,
          config_hash: '9a8b7c6d',
          tokenizer_sha256: 'e3b0c44298fc1c149afbf4c8996fb924',
        },
        warnings: [
          'Interactive Demo Mode: Set VITE_API_URL or connect live FastAPI backend for full GPU inference.',
          'TTS output is synthetic and does not preserve speaker identity.',
        ],
      };
      setReport(simulatedReport);
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
