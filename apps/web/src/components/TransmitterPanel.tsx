import React, { useRef, useState, useEffect } from 'react';
import { Mic, MicOff, Upload, Play, FileText } from 'lucide-react';
import { LanguageSpec, CriticalSpan } from '../types';

interface TransmitterPanelProps {
  languages: LanguageSpec[];
  selectedLanguage: string;
  onSelectLanguage: (lang: string) => void;
  onAudioReady: (blob: Blob, sampleText?: string) => void;
  rawTranscript: string;
  normalizedTranscript: string;
  criticalSpans: CriticalSpan[];
  tokenCount: number;
  isProcessing: boolean;
}

export const TransmitterPanel: React.FC<TransmitterPanelProps> = ({
  languages,
  selectedLanguage,
  onSelectLanguage,
  onAudioReady,
  rawTranscript,
  normalizedTranscript,
  criticalSpans,
  tokenCount,
  isProcessing,
}) => {
  const [isRecording, setIsRecording] = useState(false);
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const audioChunksRef = useRef<Blob[]>([]);

  // Canvas dummy waveform animation when recording
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let animId: number;
    let phase = 0;

    const draw = () => {
      ctx.fillStyle = '#0d121c';
      ctx.fillRect(0, 0, canvas.width, canvas.height);

      ctx.lineWidth = 2;
      ctx.strokeStyle = isRecording ? '#06b6d4' : '#26334a';
      ctx.beginPath();

      const sliceWidth = canvas.width / 100;
      let x = 0;

      for (let i = 0; i < 100; i++) {
        const amplitude = isRecording ? 20 * Math.sin(i * 0.2 + phase) * Math.cos(i * 0.1) : 2 * Math.sin(i * 0.1);
        const y = canvas.height / 2 + amplitude;
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
        x += sliceWidth;
      }

      ctx.stroke();
      phase += 0.1;
      animId = requestAnimationFrame(draw);
    };

    draw();
    return () => cancelAnimationFrame(animId);
  }, [isRecording]);

  const startRecording = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const mediaRecorder = new MediaRecorder(stream);
      mediaRecorderRef.current = mediaRecorder;
      audioChunksRef.current = [];

      mediaRecorder.ondataavailable = (e) => {
        if (e.data.size > 0) audioChunksRef.current.push(e.data);
      };

      mediaRecorder.onstop = () => {
        const audioBlob = new Blob(audioChunksRef.current, { type: 'audio/wav' });
        onAudioReady(audioBlob);
        stream.getTracks().forEach((track) => track.stop());
      };

      mediaRecorder.start(250);
      setIsRecording(true);
    } catch (err) {
      console.error('Microphone access denied:', err);
      alert('Could not access microphone. You can still use WAV upload or Sample Utterance.');
    }
  };

  const stopRecording = () => {
    if (mediaRecorderRef.current && isRecording) {
      mediaRecorderRef.current.stop();
      setIsRecording(false);
    }
  };

  const handleFileUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      onAudioReady(file);
    }
  };

  const handleSampleUtterance = () => {
    const lang = languages.find((l) => l.id === selectedLanguage);
    const text = lang ? lang.sample_text : 'मैं घर पहुँच गया हूँ';

    // Generate a simple client-side WAV carrier blob
    const sampleRate = 16000;
    const durationS = 1.5;
    const numSamples = sampleRate * durationS;
    const buffer = new ArrayBuffer(44 + numSamples * 2);
    const view = new DataView(buffer);

    const writeString = (offset: number, str: string) => {
      for (let i = 0; i < str.length; i++) view.setUint8(offset + i, str.charCodeAt(i));
    };

    writeString(0, 'RIFF');
    view.setUint32(4, 36 + numSamples * 2, true);
    writeString(8, 'WAVE');
    writeString(12, 'fmt ');
    view.setUint32(16, 16, true);
    view.setUint16(20, 1, true); // PCM
    view.setUint16(22, 1, true); // Mono
    view.setUint32(24, sampleRate, true);
    view.setUint32(28, sampleRate * 2, true);
    view.setUint16(32, 2, true);
    view.setUint16(34, 16, true);
    writeString(36, 'data');
    view.setUint32(40, numSamples * 2, true);

    for (let i = 0; i < numSamples; i++) {
      const sample = Math.sin((2 * Math.PI * 440 * i) / sampleRate) * 0.4 * 32767;
      view.setInt16(44 + i * 2, sample, true);
    }

    const blob = new Blob([buffer], { type: 'audio/wav' });
    onAudioReady(blob, text);
  };

  // Highlight critical spans in normalized text
  const renderHighlightedText = () => {
    if (!normalizedTranscript) return <span style={{ color: 'var(--text-muted)' }}>Awaiting speech input...</span>;
    if (criticalSpans.length === 0) return <span>{normalizedTranscript}</span>;

    const parts = [];
    let lastIdx = 0;
    criticalSpans.forEach((span, i) => {
      if (span.start_char > lastIdx) {
        parts.push(normalizedTranscript.slice(lastIdx, span.start_char));
      }
      parts.push(
        <span key={i} className="critical-span" title={`Critical literal: ${span.span_type}`}>
          {span.literal}
        </span>
      );
      lastIdx = span.end_char;
    });
    if (lastIdx < normalizedTranscript.length) {
      parts.push(normalizedTranscript.slice(lastIdx));
    }
    return parts;
  };

  return (
    <div className="panel-card">
      <div className="panel-title">
        <span>Transmitter (Source)</span>
        <span className="panel-title-badge">16 kHz PCM</span>
      </div>

      <div className="control-group">
        <label className="control-label">Spoken Language</label>
        <select
          value={selectedLanguage}
          onChange={(e) => onSelectLanguage(e.target.value)}
          disabled={isProcessing}
        >
          {languages.map((l) => (
            <option key={l.id} value={l.id}>
              {l.name} ({l.native_name}) {l.mvp ? '• MVP' : ''}
            </option>
          ))}
        </select>
      </div>

      {/* Waveform Canvas */}
      <div className="waveform-container">
        <canvas ref={canvasRef} className="waveform-canvas" width={400} height={80} />
      </div>

      {/* Capture Controls */}
      <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
        {!isRecording ? (
          <button
            className="btn-primary"
            onClick={startRecording}
            disabled={isProcessing}
            style={{ flex: 1 }}
          >
            <Mic size={16} /> Record Mic
          </button>
        ) : (
          <button
            className="btn-primary"
            onClick={stopRecording}
            style={{ flex: 1, background: 'var(--accent-rose)' }}
          >
            <MicOff size={16} /> Stop Recording
          </button>
        )}

        <label className="btn-secondary" style={{ cursor: 'pointer' }}>
          <Upload size={14} /> Upload WAV
          <input
            type="file"
            accept="audio/wav,audio/flac"
            style={{ display: 'none' }}
            onChange={handleFileUpload}
            disabled={isProcessing}
          />
        </label>

        <button
          className="btn-secondary"
          onClick={handleSampleUtterance}
          disabled={isProcessing}
          title="Transmit verified fixture sentence"
        >
          <Play size={14} /> Sample
        </button>
      </div>

      {/* ASR & Normalization Cards */}
      <div className="control-group">
        <div className="control-label">
          <span>ASR Raw Transcript</span>
          <FileText size={14} color="var(--text-muted)" />
        </div>
        <div className="text-display-card" style={{ fontSize: '13px' }}>
          {rawTranscript || <span style={{ color: 'var(--text-muted)' }}>No speech captured yet.</span>}
        </div>
      </div>

      <div className="control-group">
        <div className="control-label">
          <span>Normalized Semantic Text</span>
          <span style={{ fontSize: '11px', color: 'var(--accent-amber)' }}>● Protected Literals</span>
        </div>
        <div className="text-display-card">
          {renderHighlightedText()}
        </div>
      </div>

      {/* Token Telemetry */}
      <div className="telemetry-grid">
        <div className="metric-box">
          <span className="metric-label">Token Count</span>
          <span className="metric-value">{tokenCount || 0}</span>
        </div>
        <div className="metric-box">
          <span className="metric-label">Source Bits (14-bit SP)</span>
          <span className="metric-value">{tokenCount ? tokenCount * 14 : 0} bits</span>
        </div>
      </div>
    </div>
  );
};
