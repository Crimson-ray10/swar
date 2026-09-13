import React from 'react';
import {
  Mic,
  Activity,
  Binary,
  Radio,
  ZapOff,
  ShieldCheck,
  FileCheck2,
  Volume2,
  CheckCircle2,
  AlertTriangle,
  XCircle,
  Clock,
} from 'lucide-react';
import { RunReport } from '../types';

export interface SemanticPipelineProps {
  isProcessing: boolean;
  activeStageIndex: number;
  report: RunReport | null;
  useFec: boolean;
  grossRateBps: number;
  ebN0Db: number;
  packetLossRate: number;
  channelMode: string;
}

interface StageConfig {
  id: string;
  name: string;
  sublabel: string;
  icon: React.ReactNode;
  getMetric: (report: RunReport | null, props: SemanticPipelineProps) => string | null;
  getStatus: (
    report: RunReport | null,
    isProcessing: boolean,
    activeStageIndex: number,
    index: number
  ) => 'idle' | 'active' | 'completed' | 'warning' | 'error';
  getLatency: (report: RunReport | null) => number | null;
}

export const SemanticPipeline: React.FC<SemanticPipelineProps> = (props) => {
  const { isProcessing, activeStageIndex, report, useFec, grossRateBps, ebN0Db, packetLossRate } = props;

  const stages: StageConfig[] = [
    {
      id: 'input',
      name: '01. Input PCM',
      sublabel: '16 kHz Mono Audio',
      icon: <Mic size={14} />,
      getMetric: (rep) => (rep ? `${rep.input_info.duration_ms} ms` : '16 kHz PCM'),
      getStatus: (rep, proc, activeIdx, idx) => {
        if (proc) return activeIdx === idx ? 'active' : activeIdx > idx ? 'completed' : 'idle';
        if (rep) return 'completed';
        return 'idle';
      },
      getLatency: (rep) => rep?.latency_ms.capture ?? null,
    },
    {
      id: 'asr',
      name: '02. ASR Router',
      sublabel: 'IndicConformer / Whisper',
      icon: <Activity size={14} />,
      getMetric: (rep) => {
        if (!rep) return 'Acoustic Model';
        const conf = rep.transcript.asr_confidence;
        return conf !== null ? `${Math.round(conf * 100)}% conf` : 'CTC greedy';
      },
      getStatus: (rep, proc, activeIdx, idx) => {
        if (proc) return activeIdx === idx ? 'active' : activeIdx > idx ? 'completed' : 'idle';
        if (rep) return 'completed';
        return 'idle';
      },
      getLatency: (rep) => rep?.latency_ms.asr ?? null,
    },
    {
      id: 'encode',
      name: '03. Semantic Enc',
      sublabel: 'NFC + 14-bit Tokens',
      icon: <Binary size={14} />,
      getMetric: (rep) => {
        if (!rep) return '14-bit Unigram';
        const tokens = Math.round(rep.transport.source_bits / 14);
        return `${tokens} tk (${rep.transport.source_bits}b)`;
      },
      getStatus: (rep, proc, activeIdx, idx) => {
        if (proc) return activeIdx === idx ? 'active' : activeIdx > idx ? 'completed' : 'idle';
        if (rep) return 'completed';
        return 'idle';
      },
      getLatency: (rep) => rep?.latency_ms.encode ?? null,
    },
    {
      id: 'channel',
      name: '04. Channel ITP/1',
      sublabel: '14B Header + CRC-16',
      icon: <Radio size={14} />,
      getMetric: (rep) => {
        if (rep) return `${rep.transport.packet_count} pkts`;
        return `${grossRateBps / 1000}k budget`;
      },
      getStatus: (rep, proc, activeIdx, idx) => {
        if (proc) return activeIdx === idx ? 'active' : activeIdx > idx ? 'completed' : 'idle';
        if (rep) return 'completed';
        return 'idle';
      },
      getLatency: (rep) => rep?.latency_ms.channel ?? null,
    },
    {
      id: 'noise',
      name: '05. Noise & Loss',
      sublabel: 'BPSK AWGN / Fading',
      icon: <ZapOff size={14} />,
      getMetric: (rep) => {
        if (rep) {
          const ber = (rep.transport.measured_ber * 100).toFixed(1);
          const per = (rep.transport.measured_per * 100).toFixed(0);
          return `BER ${ber}% | PER ${per}%`;
        }
        return `${ebN0Db.toFixed(1)} dB | ${(packetLossRate * 100).toFixed(0)}% loss`;
      },
      getStatus: (rep, proc, activeIdx, idx) => {
        if (proc) return activeIdx === idx ? 'active' : activeIdx > idx ? 'completed' : 'idle';
        if (rep) {
          if (rep.transport.lost_packets > 0 || rep.transport.crc_fail_count > 0) {
            return rep.status === 'unrecoverable' ? 'error' : 'warning';
          }
          return 'completed';
        }
        return 'idle';
      },
      getLatency: () => null,
    },
    {
      id: 'decode',
      name: '06. FEC & Decode',
      sublabel: 'XOR(4,3) + CRC-16',
      icon: <ShieldCheck size={14} />,
      getMetric: (rep) => {
        if (rep) {
          const rec = rep.transport.recovered_packets;
          const lost = rep.transport.lost_packets;
          if (useFec && rec > 0) return `+${rec} recov (${lost} lost)`;
          if (lost > 0) return `${lost} unrecovered`;
          return useFec ? 'CRC pass (FEC ok)' : 'CRC pass (no FEC)';
        }
        return useFec ? 'XOR Parity ON' : 'Parity OFF';
      },
      getStatus: (rep, proc, activeIdx, idx) => {
        if (proc) return activeIdx === idx ? 'active' : activeIdx > idx ? 'completed' : 'idle';
        if (rep) {
          if (rep.status === 'unrecoverable') return 'error';
          if (rep.status === 'partial') return 'warning';
          return 'completed';
        }
        return 'idle';
      },
      getLatency: (rep) => rep?.latency_ms.decode ?? null,
    },
    {
      id: 'recovered',
      name: '07. Recovered Text',
      sublabel: 'Linguistic Check',
      icon: <FileCheck2 size={14} />,
      getMetric: (rep) => {
        if (!rep) return 'Transcript Sink';
        if (rep.receiver.status === 'exact') return '100% Exact';
        if (rep.receiver.status === 'partial') return 'Partial Loss';
        return 'Erasure';
      },
      getStatus: (rep, proc, activeIdx, idx) => {
        if (proc) return activeIdx === idx ? 'active' : activeIdx > idx ? 'completed' : 'idle';
        if (rep) {
          if (rep.receiver.status === 'exact') return 'completed';
          if (rep.receiver.status === 'partial') return 'warning';
          return 'error';
        }
        return 'idle';
      },
      getLatency: () => null,
    },
    {
      id: 'tts',
      name: '08. TTS Voice',
      sublabel: 'IndicF5 / Piper 24k',
      icon: <Volume2 size={14} />,
      getMetric: (rep) => {
        if (!rep) return 'Synthetic Voice';
        if (rep.receiver.tts_status === 'complete') return '24 kHz WAV';
        if (rep.receiver.tts_status === 'erasure') return 'Withheld';
        return rep.receiver.tts_status;
      },
      getStatus: (rep, proc, activeIdx, idx) => {
        if (proc) return activeIdx === idx ? 'active' : activeIdx > idx ? 'completed' : 'idle';
        if (rep) {
          if (rep.receiver.tts_status === 'complete' || rep.receiver.tts_status === 'synthesized_offline' || !!rep.audio_output_base64) return 'completed';
          if (rep.receiver.tts_status === 'erasure') return 'error';
          return 'warning';
        }
        return 'idle';
      },
      getLatency: (rep) => rep?.latency_ms.tts ?? null,
    },
  ];

  return (
    <div style={{ width: '100%', marginBottom: '24px' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '16px', flexWrap: 'wrap', gap: '8px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <span style={{ width: '8px', height: '8px', borderRadius: '50%', backgroundColor: '#6366f1', display: 'inline-block' }} />
          <span style={{ fontSize: '12px', fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', color: '#f8fafc' }}>
            Semantic Communication Pipeline
          </span>
        </div>
        <span style={{ fontSize: '11px', fontFamily: 'var(--font-mono)', color: '#818cf8', background: 'rgba(99,102,241,0.1)', padding: '3px 10px', borderRadius: '999px', border: '1px solid rgba(99,102,241,0.3)' }}>
          ITP/1 text_v1 • 0.5–2.0 kbps
        </span>
      </div>

      <div className="pipeline-grid">
        {stages.map((stage, idx) => {
          const status = stage.getStatus(report, isProcessing, activeStageIndex, idx);
          const metric = stage.getMetric(report, props);
          const latency = stage.getLatency(report);

          return (
            <div
              key={stage.id}
              className={`pipeline-node status-${status}`}
            >
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '8px' }}>
                <span style={{ color: '#818cf8' }}>{stage.icon}</span>
                <span style={{ fontSize: '10px', fontFamily: 'var(--font-mono)', fontWeight: 600, textTransform: 'uppercase', display: 'flex', alignItems: 'center', gap: '4px' }}>
                  {status === 'completed' && <CheckCircle2 size={11} color="#34d399" />}
                  {status === 'warning' && <AlertTriangle size={11} color="#fbbf24" />}
                  {status === 'error' && <XCircle size={11} color="#fb7185" />}
                  {status === 'active' && (
                    <span style={{ width: '6px', height: '6px', borderRadius: '50%', backgroundColor: '#818cf8', display: 'inline-block' }} />
                  )}
                  <span style={{ color: status === 'completed' ? '#34d399' : status === 'warning' ? '#fbbf24' : status === 'error' ? '#fb7185' : '#64748b' }}>
                    {status}
                  </span>
                </span>
              </div>

              <div>
                <div className="node-title">{stage.name}</div>
                <div className="node-sub">{stage.sublabel}</div>
              </div>

              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: '10px', paddingTop: '6px', borderTop: '1px solid rgba(255,255,255,0.05)' }}>
                <span className="node-metric" title={metric || ''}>
                  {metric}
                </span>
                {latency !== null && latency !== undefined && (
                  <span style={{ fontSize: '10px', fontFamily: 'var(--font-mono)', color: '#94a3b8', display: 'flex', alignItems: 'center', gap: '3px' }}>
                    <Clock size={10} />
                    {latency}ms
                  </span>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};
