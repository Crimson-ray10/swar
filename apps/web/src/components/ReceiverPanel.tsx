import React from 'react';
import { Volume2, AlertTriangle, CheckCircle, XCircle, Clock } from 'lucide-react';

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
  const renderStatusBadge = () => {
    switch (status) {
      case 'exact':
        return (
          <span style={{ color: 'var(--accent-emerald)', display: 'flex', alignItems: 'center', gap: '4px' }}>
            <CheckCircle size={14} /> EXACT RECOVERY
          </span>
        );
      case 'partial':
        return (
          <span style={{ color: 'var(--accent-amber)', display: 'flex', alignItems: 'center', gap: '4px' }}>
            <AlertTriangle size={14} /> PARTIAL LOSS
          </span>
        );
      case 'unrecoverable':
        return (
          <span style={{ color: 'var(--accent-rose)', display: 'flex', alignItems: 'center', gap: '4px' }}>
            <XCircle size={14} /> UNRECOVERABLE ERASURE
          </span>
        );
      default:
        return <span style={{ color: 'var(--text-muted)' }}>IDLE</span>;
    }
  };

  return (
    <div className="panel-card">
      <div className="panel-title">
        <span>Receiver (Sink)</span>
        <div className="panel-title-badge">{renderStatusBadge()}</div>
      </div>

      {/* Reconstructed Text Card */}
      <div className="control-group">
        <label className="control-label">Reconstructed Linguistic Message</label>
        <div
          className="text-display-card"
          style={{
            borderColor: status === 'exact' ? 'rgba(16, 185, 129, 0.4)' : undefined,
            minHeight: '80px',
          }}
        >
          {reconstructedText || (
            <span style={{ color: 'var(--text-muted)' }}>
              {status === 'unrecoverable'
                ? '[Packet erasure exceeded FEC capability. Speech withheld.]'
                : 'Awaiting decoded frames...'}
            </span>
          )}
        </div>
      </div>

      {/* Audio Playback */}
      <div className="control-group">
        <div className="control-label">
          <span>Synthesized Receiver Voice</span>
          <span style={{ fontSize: '11px', color: 'var(--accent-purple)' }}>
            ● IndicF5 / Piper 24 kHz
          </span>
        </div>

        {audioOutputBase64 ? (
          <div
            style={{
              padding: '12px',
              backgroundColor: 'var(--bg-card-subtle)',
              borderRadius: '8px',
              display: 'flex',
              flexDirection: 'column',
              gap: '8px',
            }}
          >
            <audio
              controls
              autoPlay
              src={`data:audio/wav;base64,${audioOutputBase64}`}
              style={{ width: '100%', height: '36px' }}
            />
            <div style={{ fontSize: '11px', color: 'var(--text-muted)', display: 'flex', alignItems: 'center', gap: '4px' }}>
              <Volume2 size={12} />
              <span>Synthetic receiver voice; does not preserve speaker waveform.</span>
            </div>
          </div>
        ) : (
          <div
            style={{
              padding: '14px',
              backgroundColor: 'var(--bg-card-subtle)',
              borderRadius: '8px',
              color: 'var(--text-muted)',
              fontSize: '12px',
              textAlign: 'center',
            }}
          >
            Audio synthesized only upon verified frame arrival
          </div>
        )}
      </div>

      {/* Latency Waterfall Breakdown */}
      <div className="control-group">
        <div className="control-label">
          <span>Processing & Channel Latency</span>
          <span style={{ fontSize: '11px', color: 'var(--text-muted)', display: 'flex', alignItems: 'center', gap: '4px' }}>
            <Clock size={12} /> Total: {latencyMs?.end_to_end || 0} ms
          </span>
        </div>

        {latencyMs && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '4px', fontSize: '11px', fontFamily: 'var(--font-mono)' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', color: 'var(--text-secondary)' }}>
              <span>ASR / STT:</span>
              <span>{latencyMs.asr} ms</span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', color: 'var(--text-secondary)' }}>
              <span>Source Encode + Miniheader:</span>
              <span>{latencyMs.encode} ms</span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', color: 'var(--accent-cyan)' }}>
              <span>Channel Serialization & Noise:</span>
              <span>{latencyMs.channel} ms</span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', color: 'var(--text-secondary)' }}>
              <span>Packet Decode / Reorder:</span>
              <span>{latencyMs.decode} ms</span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', color: 'var(--accent-purple)' }}>
              <span>TTS Voice Synthesis:</span>
              <span>{latencyMs.tts} ms</span>
            </div>
          </div>
        )}
      </div>

      {/* Warnings */}
      {warnings.length > 0 && (
        <div
          style={{
            padding: '10px',
            backgroundColor: 'rgba(245, 158, 11, 0.1)',
            border: '1px solid rgba(245, 158, 11, 0.3)',
            borderRadius: '6px',
            fontSize: '12px',
            color: '#fbbf24',
          }}
        >
          {warnings.map((w, idx) => (
            <div key={idx}>⚠️ {w}</div>
          ))}
        </div>
      )}
    </div>
  );
};
