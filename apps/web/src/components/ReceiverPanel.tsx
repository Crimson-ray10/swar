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

  const toggleAudio = () => {
    if (!audioRef.current) return;
    if (isPlaying) {
      audioRef.current.pause();
      setIsPlaying(false);
    } else {
      audioRef.current.currentTime = 0;
      audioRef.current.play();
      setIsPlaying(true);
    }
  };

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
                IndicF5 / Piper 24 kHz Synthetic Voice
              </span>
            </div>
          </div>

          {/* Audio Action Button */}
          {audioOutputBase64 && (
            <button
              onClick={toggleAudio}
              className={`voice-play-pill-btn ${isPlaying ? 'is-playing' : ''}`}
              title={isPlaying ? 'Pause synthetic audio' : 'Play synthesized receiver voice'}
            >
              {isPlaying ? <VolumeX size={14} /> : <Volume2 size={14} />}
              <span>{isPlaying ? 'Stop Voice' : 'Play Synthetic Voice'}</span>
            </button>
          )}
        </div>

        {/* Hidden/Custom Audio Element */}
        {audioOutputBase64 && (
          <div style={{ marginBottom: '18px' }}>
            <audio
              ref={audioRef}
              src={`data:audio/wav;base64,${audioOutputBase64}`}
              onPlay={() => setIsPlaying(true)}
              onPause={() => setIsPlaying(false)}
              onEnded={() => setIsPlaying(false)}
              controls
              style={{ width: '100%', height: '36px', borderRadius: '8px', opacity: 0.85 }}
            />
          </div>
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
