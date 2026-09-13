import React, { useState } from 'react';
import { Upload, Play, Languages, FileText, Sparkles } from 'lucide-react';
import { LanguageSpec, CriticalSpan } from '../types';
import { MicButton, MicState } from './MicButton';

interface TransmitterPanelProps {
  languages: LanguageSpec[];
  selectedLanguage: string;
  onSelectLanguage: (lang: string) => void;
  onAudioReady: (blob: Blob, sampleText?: string, isSample?: boolean) => void;
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
  const [uploadedFileName, setUploadedFileName] = useState<string | null>(null);
  const [micState, setMicState] = useState<MicState>('Idle');
  const [customPrompt, setCustomPrompt] = useState<string>('');

  const currentLang = languages.find((l) => l.id === selectedLanguage);

  // Sync processing state with mic state
  React.useEffect(() => {
    if (isProcessing) {
      setMicState('Processing');
    } else if (rawTranscript || normalizedTranscript) {
      setMicState('Complete');
    } else {
      setMicState('Idle');
    }
  }, [isProcessing, rawTranscript, normalizedTranscript]);

  const handleFileUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      setUploadedFileName(file.name);
      onAudioReady(file, customPrompt.trim() || undefined, false);
    }
  };

  const transmitWithText = (text: string, isSample: boolean = false) => {
    // Generate standard 16 kHz WAV carrier tone
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
    onAudioReady(blob, text, isSample);
  };

  const handleSampleUtterance = () => {
    const text = currentLang ? currentLang.sample_text : 'I need immediate assistance, there is an emergency here.';
    setUploadedFileName(`Sample: ${currentLang ? currentLang.name : 'English'}`);
    setCustomPrompt(text);
    transmitWithText(text, true);
  };

  // Render critical spans with citation highlight styling
  const renderHighlightedText = () => {
    if (!normalizedTranscript) {
      return <span style={{ color: '#64748b', fontStyle: 'italic' }}>Awaiting speech capture or sample transmission...</span>;
    }
    if (criticalSpans.length === 0) {
      return <span>{normalizedTranscript}</span>;
    }

    const parts: React.ReactNode[] = [];
    let lastIdx = 0;
    criticalSpans.forEach((span, i) => {
      if (span.start_char > lastIdx) {
        parts.push(normalizedTranscript.slice(lastIdx, span.start_char));
      }
      parts.push(
        <span
          key={i}
          className="critical-span"
          title={`Protected literal (${span.span_type}): "${span.literal}"`}
        >
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
    <section id="studio" className="content-section">
      {/* Section Badge */}
      <div className="section-badge">
        <Sparkles size={13} />
        <span>Indian Multilingual Radio Studio</span>
      </div>

      <h2 className="section-title">
        Transmit via Voice
      </h2>
      <p className="section-desc">
        Real-time multilingual speech recognition, semantic normalization, 14-bit tokenization, and wire packet framing.
      </p>

      {/* Language Selector Pill */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '24px', flexWrap: 'wrap', justifyContent: 'center' }}>
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: '8px', padding: '6px 14px', borderRadius: '999px', background: 'rgba(255,255,255,0.06)', border: '1px solid rgba(255,255,255,0.1)' }}>
          <Languages size={14} color="#818cf8" />
          <span style={{ fontSize: '13px', fontWeight: 500, color: '#e2e8f0' }}>Language:</span>
          <select
            value={selectedLanguage}
            onChange={(e) => onSelectLanguage(e.target.value)}
            disabled={isProcessing}
            style={{
              background: 'transparent',
              border: 'none',
              color: '#ffffff',
              fontSize: '13px',
              fontWeight: 600,
              cursor: 'pointer',
              outline: 'none',
              fontFamily: 'var(--font-sans)'
            }}
          >
            {languages.map((l) => (
              <option key={l.id} value={l.id} style={{ background: '#0f172a', color: '#ffffff' }}>
                {l.name} ({l.native_name}) {l.mvp ? '★ MVP' : ''}
              </option>
            ))}
          </select>
        </div>

        {currentLang && (
          <span style={{ fontSize: '12px', color: '#818cf8', fontFamily: 'var(--font-sans)', fontWeight: 600 }}>
            {currentLang.native_name}
          </span>
        )}
      </div>

      {/* Language Quick-Switch Buttons */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '20px', flexWrap: 'wrap', justifyContent: 'center' }}>
        {[
          { id: 'en', label: '🇬🇧 English (India)' },
          { id: 'hi', label: '🇮🇳 हिन्दी Hindi' },
          { id: 'ta', label: '🇮🇳 தமிழ் Tamil' },
          { id: 'te', label: '🇮🇳 తెలుగు Telugu' },
        ].map((lang) => (
          <button
            key={lang.id}
            type="button"
            onClick={() => onSelectLanguage(lang.id)}
            disabled={isProcessing}
            style={{
              padding: '6px 14px',
              borderRadius: '999px',
              fontSize: '12px',
              fontWeight: 600,
              cursor: 'pointer',
              border: selectedLanguage === lang.id ? '1px solid #818cf8' : '1px solid rgba(255,255,255,0.12)',
              background: selectedLanguage === lang.id ? 'rgba(99,102,241,0.25)' : 'rgba(255,255,255,0.04)',
              color: selectedLanguage === lang.id ? '#ffffff' : '#94a3b8',
              transition: 'all 0.15s ease'
            }}
          >
            {lang.label}
          </button>
        ))}
      </div>

      {/* Central Interactive Mic Button (Exact July Experience) */}
      <MicButton
        state={micState}
        selectedLanguage={selectedLanguage}
        onAudioRecorded={(blob, liveTranscript) => {
          setUploadedFileName('Microphone Utterance');
          const phrase = liveTranscript?.trim() || customPrompt.trim();
          if (!phrase) {
            alert('No speech was detected or recognized by the STT engine. Please speak clearly into the microphone or type your sentence in the box below.');
            setMicState('Idle');
            return;
          }
          onAudioReady(blob, phrase, false);
        }}
        onLiveTranscriptChange={(text) => {
          if (text) setCustomPrompt(text);
        }}
        onStateChange={setMicState}
      />

      {/* Secondary Action Buttons (Upload WAV, Sample Utterance) */}
      <div className="mic-actions-row">
        <label className="secondary-pill-btn" title="Upload an uncompressed 16 kHz WAV or FLAC speech file">
          <Upload size={14} />
          <span>Upload Audio</span>
          <input
            type="file"
            accept="audio/wav,audio/flac"
            style={{ display: 'none' }}
            onChange={handleFileUpload}
            disabled={isProcessing}
          />
        </label>

        <button
          className="secondary-pill-btn"
          onClick={handleSampleUtterance}
          disabled={isProcessing}
          title={`Transmit pre-recorded verified sample: "${currentLang?.sample_text || ''}"`}
        >
          <Play size={13} />
          <span>Transmit Pre-recorded Sample</span>
        </button>

        {uploadedFileName && (
          <span style={{ fontSize: '12px', color: '#94a3b8', fontFamily: 'var(--font-mono)' }}>
            📎 {uploadedFileName}
          </span>
        )}
      </div>

      {/* Interactive Custom Text / Spoken Phrase Box */}
      <form
        onSubmit={(e) => {
          e.preventDefault();
          const text = customPrompt.trim();
          if (!text) {
            alert('Please speak into the microphone or type your sentence in the box before transmitting.');
            return;
          }
          setUploadedFileName(`Text: "${text.slice(0, 18)}..."`);
          transmitWithText(text, false);
        }}
        style={{ width: '100%', maxWidth: '640px', marginTop: '16px', display: 'flex', gap: '8px' }}
      >
        <input
          type="text"
          value={customPrompt}
          onChange={(e) => setCustomPrompt(e.target.value)}
          placeholder={`Speak into mic or type sentence in ${currentLang?.name || 'language'}...`}
          disabled={isProcessing}
          style={{
            flex: 1,
            padding: '10px 18px',
            borderRadius: '999px',
            background: 'rgba(255, 255, 255, 0.06)',
            border: '1px solid rgba(255, 255, 255, 0.15)',
            color: '#ffffff',
            fontSize: '13px',
            outline: 'none',
            fontFamily: 'var(--font-sans)',
          }}
        />
        <button
          type="submit"
          disabled={isProcessing || !customPrompt.trim()}
          className="secondary-pill-btn"
          style={{ padding: '0 20px', background: customPrompt.trim() ? '#6366f1' : undefined, color: customPrompt.trim() ? '#ffffff' : undefined }}
        >
          <span>Transmit</span>
        </button>
      </form>

      {/* Quick Test Sentence Suggestions */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginTop: '12px', flexWrap: 'wrap', justifyContent: 'center' }}>
        <span style={{ fontSize: '11px', color: '#94a3b8', fontWeight: 600, textTransform: 'uppercase' }}>Quick Presets:</span>
        {[
          'The patient is stable and arriving now.',
          'Doctor requested assistance in Ward B.',
          'नमस्ते, रोगी की हालत स्थिर है।',
          'எனக்கு உடனடி உதவி தேவை.'
        ].map((phrase, idx) => (
          <button
            key={idx}
            type="button"
            onClick={() => {
              setCustomPrompt(phrase);
              setUploadedFileName(`Preset: "${phrase.slice(0, 15)}..."`);
              transmitWithText(phrase, false);
            }}
            disabled={isProcessing}
            style={{
              padding: '4px 10px',
              borderRadius: '999px',
              background: 'rgba(255, 255, 255, 0.05)',
              border: '1px solid rgba(255, 255, 255, 0.12)',
              color: '#cbd5e1',
              fontSize: '11px',
              cursor: 'pointer',
              transition: 'all 0.15s ease'
            }}
          >
            "{phrase.length > 25 ? phrase.slice(0, 25) + '...' : phrase}"
          </button>
        ))}
      </div>

      {/* Transcripts & Telemetry Cards */}
      <div style={{ width: '100%', maxWidth: '820px', marginTop: '36px', display: 'flex', flexDirection: 'column', gap: '16px' }}>
        {/* ASR Raw Acoustic Transcript */}
        <div className="glass-panel" style={{ borderRadius: '20px', padding: '18px 22px' }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '8px' }}>
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', fontSize: '12px', fontWeight: 600, color: '#94a3b8', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
              <FileText size={13} color="#818cf8" />
              <span>ASR Raw Acoustic Transcript</span>
            </span>
            <span style={{ fontSize: '11px', fontFamily: 'var(--font-mono)', color: '#64748b' }}>
              IndicConformer / Whisper
            </span>
          </div>
          <p style={{ fontSize: '15px', color: rawTranscript ? '#f8fafc' : '#64748b', fontStyle: rawTranscript ? 'normal' : 'italic', lineHeight: 1.5 }}>
            {rawTranscript ? `"${rawTranscript}"` : 'Awaiting speech capture or sample transmission...'}
          </p>
        </div>

        {/* Normalized Semantic Message Card with Critical Literals */}
        <div className="glass-panel" style={{ borderRadius: '20px', padding: '18px 22px' }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '8px', flexWrap: 'wrap', gap: '8px' }}>
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', fontSize: '12px', fontWeight: 600, color: '#94a3b8', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
              <Sparkles size={13} color="#6366f1" />
              <span>Normalized Semantic Message</span>
            </span>
            <span
              style={{
                fontSize: '10px',
                fontFamily: 'var(--font-mono)',
                color: '#c7d2fe',
                background: 'rgba(99,102,241,0.2)',
                border: '1px solid rgba(99,102,241,0.4)',
                borderRadius: '999px',
                padding: '2px 8px'
              }}
              title="Protected literals (digits, negations, dates) preserved with high priority"
            >
              ● Protected Literals
            </span>
          </div>
          <p style={{ fontSize: '15px', color: '#f8fafc', lineHeight: 1.6 }}>
            {renderHighlightedText()}
          </p>
        </div>

        {/* Source Coding & Token Telemetry Grid */}
        <div className="telemetry-grid">
          <div className="metric-box">
            <span className="metric-label">Token Count</span>
            <span className="metric-value">{tokenCount || 0}</span>
            <span className="metric-foot">V=16,384 Unigram</span>
          </div>

          <div className="metric-box">
            <span className="metric-label">Source Bits</span>
            <span className="metric-value">{tokenCount ? tokenCount * 14 : 0}</span>
            <span className="metric-foot">14-bit packed IDs</span>
          </div>

          <div className="metric-box">
            <span className="metric-label">Normalizer</span>
            <span className="metric-value" style={{ fontSize: '15px' }}>indic_nfc</span>
            <span className="metric-foot">Canonical normalization</span>
          </div>

          <div className="metric-box">
            <span className="metric-label">Source Format</span>
            <span className="metric-value" style={{ fontSize: '15px' }}>16 kHz PCM</span>
            <span className="metric-foot">Mono 16-bit</span>
          </div>
        </div>
      </div>
    </section>
  );
};
