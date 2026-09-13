import React, { useRef, useState } from 'react';
import { Volume2, VolumeX, AlertTriangle, CheckCircle2, XCircle, Clock, ShieldAlert, Radio, Zap, Sparkles } from 'lucide-react';

interface ReceiverPanelProps {
  status: 'exact' | 'partial' | 'unrecoverable' | 'idle';
  reconstructedText: string | null;
  audioOutputBase64: string | null;
  latencyMs: {
    capture: number;
    asr: number;
    encode: number;
    channel: number;
    decode: number;
    tts: number;
    end_to_end: number;
  } | null;
  warnings: string[];
}

export const ReceiverPanel: React.FC<ReceiverPanelProps> = ({
  status,
  reconstructedText,
  audioOutputBase64,
  latencyMs,
  warnings,
}) => {
  const [isPlaying, setIsPlaying] = useState(false);
  const audioRef = useRef<HTMLAudioElement | null>(null);

  // Multilingual Speech Synthesis for the Reconstructed Sentence
  const speakText = React.useCallback((text: string) => {
    if (!text || typeof window === 'undefined') return;

    if ('speechSynthesis' in window) {
      window.speechSynthesis.cancel();
      const utterance = new SpeechSynthesisUtterance(text);

      // Determine language script from characters: prioritize Latin/English if present, otherwise detect Indic scripts
      let langTag = 'en-IN';
      if (/[\u0900-\u097F]/.test(text)) langTag = 'hi-IN';
      else if (/[\u0B80-\u0BFF]/.test(text)) langTag = 'ta-IN';
      else if (/[\u0C00-\u0C7F]/.test(text)) langTag = 'te-IN';
      else if (/[\u0980-\u09FF]/.test(text)) langTag = 'bn-IN';
      else if (/[\u0A80-\u0AFF]/.test(text)) langTag = 'gu-IN';
      else if (/[\u0C80-\u0CFF]/.test(text)) langTag = 'kn-IN';
      else if (/[\u0D00-\u0D7F]/.test(text)) langTag = 'ml-IN';
      else if (/[\u0A00-\u0A7F]/.test(text)) langTag = 'pa-IN';
      else if (/[\u0600-\u06FF]/.test(text)) langTag = 'ur-IN';
      else if (/[a-zA-Z]/.test(text)) langTag = 'en-IN';

      utterance.lang = langTag;

      const voices = window.speechSynthesis.getVoices();
      const matchingVoice =
        voices.find((v) => v.lang.toLowerCase() === langTag.toLowerCase() || v.lang.replace('_', '-').toLowerCase() === langTag.toLowerCase()) ||
        voices.find((v) => v.lang.toLowerCase().startsWith(langTag.slice(0, 2).toLowerCase())) ||
        voices.find((v) => v.lang.toLowerCase().includes('in')) ||
        voices.find((v) => v.lang.toLowerCase().includes('en')) ||
        voices[0];

      if (matchingVoice) {
        utterance.voice = matchingVoice;
      }

      utterance.rate = 0.95;
      utterance.pitch = 1.0;

      utterance.onstart = () => setIsPlaying(true);
      utterance.onend = () => setIsPlaying(false);
      utterance.onerror = () => setIsPlaying(false);

      window.speechSynthesis.speak(utterance);
    } else if (audioRef.current) {
      audioRef.current.currentTime = 0;
      audioRef.current.play()
        .then(() => setIsPlaying(true))
        .catch(() => setIsPlaying(false));
    }
  }, []);

  // Pre-fetch voices on mount
  React.useEffect(() => {
    if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
      window.speechSynthesis.getVoices();
      const onVoices = () => {
        window.speechSynthesis.getVoices();
      };
      window.speechSynthesis.addEventListener('voiceschanged', onVoices);
      return () => {
        window.speechSynthesis.removeEventListener('voiceschanged', onVoices);
        window.speechSynthesis.cancel();
      };
    }
  }, []);

  const toggleAudio = () => {
    if (isPlaying) {
      if ('speechSynthesis' in window) {
        window.speechSynthesis.cancel();
      }
      if (audioRef.current) {
        audioRef.current.pause();
      }
      setIsPlaying(false);
    } else {
      if (reconstructedText) {
        speakText(reconstructedText);
      } else if (audioRef.current) {
        audioRef.current.currentTime = 0;
        audioRef.current.play().then(() => setIsPlaying(true)).catch(() => {});
      }
    }
  };

  // Auto-play synthesized voice when a new reconstructed transmission arrives
  React.useEffect(() => {
    if (reconstructedText && status !== 'unrecoverable') {
      speakText(reconstructedText);
    }
  }, [reconstructedText, status, speakText]);

  const renderStatusBadge = () => {
    switch (status) {
      case 'exact':
        return (
          <span className="status-badge-grounded">
            <CheckCircle2 size={13} color="#34d399" />
            <span>EXACT RECOVERY (0% LOSS)</span>
          </span>
        );
      case 'partial':
        return (
          <span className="status-badge-partial">
            <AlertTriangle size={13} color="#fbbf24" />
            <span>PARTIAL LOSS (DEGRADED)</span>
          </span>
        );
      case 'unrecoverable':
        return (
          <span className="status-badge-erasure">
            <XCircle size={13} color="#fb7185" />
            <span>UNRECOVERABLE ERASURE</span>
          </span>
        );
      default:
        return (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', fontSize: '11px', fontFamily: 'var(--font-mono)', padding: '4px 10px', borderRadius: '999px', background: 'rgba(255,255,255,0.06)', color: '#94a3b8', border: '1px solid rgba(255,255,255,0.1)' }}>
            <Radio size={13} />
            <span>RECEIVER STANDBY</span>
          </span>
        );
    }
  };

  const totalLatency = latencyMs?.end_to_end || 1;
  const latencyStages = latencyMs
    ? [
        { label: 'Capture & Preprocessing', ms: latencyMs.capture, color: '#00f0ff' },
        { label: 'ASR Acoustic Model', ms: latencyMs.asr, color: '#38bdf8' },
        { label: 'Semantic Tokenizer', ms: latencyMs.encode, color: '#a855f7' },
        { label: 'RF Channel Sim', ms: latencyMs.channel, color: '#f59e0b' },
        { label: 'Packet Reassembly & FEC', ms: latencyMs.decode, color: '#10b981' },
        { label: 'TTS Synthetic Voice', ms: latencyMs.tts, color: '#f43f5e' },
      ]
    : [];

  const audioSrc = audioOutputBase64
    ? (audioOutputBase64.startsWith('data:') ? audioOutputBase64 : `data:audio/wav;base64,${audioOutputBase64}`)
    : null;

  return (
    <section id="receiver" className="content-section">
      {/* Section Badge */}
      <div className="section-badge">
        <Sparkles size={13} />
        <span>Receiver Sink & Truth Boundary</span>
      </div>

      <h2 className="section-title">
        Linguistic Reconstruction & Audio
      </h2>
      <p className="section-desc">
        Semantic payload reassembly, CRC validation, error correction, and 24 kHz synthetic speech playback.
      </p>

      {/* AnswerCard Component (Exact July Style) */}
      <div className="answer-card-container">
        {/* Header */}
        <div className="answer-card-header">
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
            <div style={{ padding: '8px', borderRadius: '12px', background: 'rgba(99,102,241,0.2)', color: '#818cf8', border: '1px solid rgba(99,102,241,0.3)' }}>
              <Radio size={18} />
            </div>
            <div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <span style={{ fontSize: '13px', fontWeight: 700, color: '#f1f5f9', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                  Receiver Sink State
                </span>
                {renderStatusBadge()}
              </div>
              <span style={{ fontSize: '11px', color: '#94a3b8', fontFamily: 'var(--font-mono)' }}>
                Indic Neural Speech Synthesizer
              </span>
            </div>
          </div>

          {/* Audio Action Button */}
          {(reconstructedText || audioOutputBase64) && (
            <button
              onClick={toggleAudio}
              className={`voice-play-pill-btn ${isPlaying ? 'is-playing' : ''}`}
              title={isPlaying ? 'Stop speech output' : 'Play spoken reconstructed sentence'}
            >
              {isPlaying ? <VolumeX size={14} /> : <Volume2 size={14} />}
              <span>{isPlaying ? 'Stop Voice' : 'Play Synthetic Voice'}</span>
            </button>
          )}
        </div>

        {/* Hidden Audio Element for Fallback */}
        {audioSrc && (
          <audio
            ref={audioRef}
            src={audioSrc}
            onPlay={() => setIsPlaying(true)}
            onPause={() => setIsPlaying(false)}
            onEnded={() => setIsPlaying(false)}
            style={{ display: 'none' }}
          />
        )}

        {/* Reconstructed Text Content */}
        <div style={{ marginBottom: '24px' }}>
          <span style={{ fontSize: '11px', fontWeight: 600, color: '#94a3b8', textTransform: 'uppercase', letterSpacing: '0.06em', display: 'block', marginBottom: '8px' }}>
            Reconstructed Linguistic Message:
          </span>
          <div className="answer-text-content">
            {reconstructedText ? (
              <span style={{ color: '#ffffff', fontWeight: 500 }}>"{reconstructedText}"</span>
            ) : (
              <span style={{ color: '#64748b', fontStyle: 'italic', fontSize: '15px' }}>
                {status === 'unrecoverable'
                  ? '⚠️ Packet erasure exceeded FEC capability. Speech synthesis withheld per truth boundary.'
                  : 'Awaiting decoded payload frames from transmission...'}
              </span>
            )}
          </div>
        </div>

        {/* Latency Waterfall Breakdown */}
        <div style={{ paddingTop: '20px', borderTop: '1px solid rgba(255,255,255,0.08)' }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '8px' }}>
            <span style={{ fontSize: '12px', fontWeight: 600, color: '#94a3b8', textTransform: 'uppercase', letterSpacing: '0.04em', display: 'inline-flex', alignItems: 'center', gap: '6px' }}>
              <Clock size={13} color="#818cf8" /> Latency Telemetry Waterfall
            </span>
            <div className={`latency-badge-pill ${latencyMs && latencyMs.end_to_end <= 600 ? 'fast' : ''}`}>
              <Zap size={13} />
              <span>Total: {latencyMs?.end_to_end || 0} ms</span>
            </div>
          </div>

          {latencyMs ? (
            <div>
              {/* Stacked visual bar */}
              <div className="waterfall-stacked-bar">
                {latencyStages.map((stg, i) => {
                  const pct = Math.max(2, (stg.ms / totalLatency) * 100);
                  return (
                    <div
                      key={i}
                      className="stacked-segment"
                      style={{ width: `${pct}%`, backgroundColor: stg.color }}
                      title={`${stg.label}: ${stg.ms} ms`}
                    />
                  );
                })}
              </div>

              {/* Table Breakdown */}
              <div className="waterfall-table">
                {latencyStages.map((stg, i) => (
                  <div key={i} className="waterfall-row">
                    <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                      <span style={{ width: '8px', height: '8px', borderRadius: '50%', backgroundColor: stg.color, display: 'inline-block' }} />
                      <span style={{ color: '#cbd5e1' }}>{stg.label}</span>
                    </div>
                    <span style={{ fontFamily: 'var(--font-mono)', fontWeight: 600, color: '#ffffff' }}>
                      {stg.ms} ms
                    </span>
                  </div>
                ))}
              </div>
            </div>
          ) : (
            <div style={{ padding: '16px 0', textAlign: 'center', color: '#64748b', fontSize: '12px', fontStyle: 'italic' }}>
              Latency telemetry calculated per transmission run
            </div>
          )}
        </div>

        {/* Warnings Banner */}
        {warnings.length > 0 && (
          <div style={{ marginTop: '20px', padding: '14px 18px', borderRadius: '14px', background: 'rgba(245,158,11,0.1)', border: '1px solid rgba(245,158,11,0.3)', display: 'flex', flexDirection: 'column', gap: '6px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '6px', color: '#fbbf24', fontSize: '12px', fontWeight: 600 }}>
              <ShieldAlert size={14} />
              <span>Receiver Protocol Notices</span>
            </div>
            <ul style={{ listStyle: 'none', padding: 0, margin: 0 }}>
              {warnings.map((w, idx) => (
                <li key={idx} style={{ fontSize: '12px', color: '#fef3c7', paddingLeft: '8px', lineHeight: 1.4 }}>
                  • {w}
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </section>
  );
};
