import React from 'react';
import { Radio, ShieldCheck, Sliders, Activity, Disc3, Layers } from 'lucide-react';
import { DeliveryEvent, RunReport } from '../types';
import { SemanticPipeline } from './SemanticPipeline';

interface ChannelPanelProps {
  grossRateBps: number;
  onChangeGrossRate: (rate: number) => void;
  channelMode: string;
  onChangeChannelMode: (mode: string) => void;
  ebN0Db: number;
  onChangeEbN0Db: (val: number) => void;
  packetLossRate: number;
  onChangePacketLossRate: (val: number) => void;
  useFec: boolean;
  onToggleFec: (val: boolean) => void;
  seed: number;
  onChangeSeed: (seed: number) => void;
  deliveryEvents: DeliveryEvent[];
  measuredBer: number;
  measuredPer: number;
  onAirBits: number;
  actualWireBps: number;
  isProcessing: boolean;
  activeStageIndex: number;
  report: RunReport | null;
}

export const ChannelPanel: React.FC<ChannelPanelProps> = ({
  grossRateBps,
  onChangeGrossRate,
  channelMode,
  onChangeChannelMode,
  ebN0Db,
  onChangeEbN0Db,
  packetLossRate,
  onChangePacketLossRate,
  useFec,
  onToggleFec,
  seed,
  onChangeSeed,
  deliveryEvents,
  measuredBer,
  measuredPer,
  onAirBits,
  actualWireBps,
  isProcessing,
  activeStageIndex,
  report,
}) => {
  return (
    <section id="channel" className="content-section">
      {/* Section Badge */}
      <div className="section-badge">
        <Radio size={13} />
        <span>Simulated Low-Bitrate RF Link & Impairments</span>
      </div>

      <h2 className="section-title">
        Channel & Pipeline Architecture
      </h2>
      <p className="section-desc">
        Configure BPSK physical noise, Rayleigh fading, packet loss rates, and XOR(4,3) parity forward error correction.
      </p>

      <div className="channel-container-card">
        {/* 8-Stage Semantic Communication Flow Pipeline */}
        <SemanticPipeline
          isProcessing={isProcessing}
          activeStageIndex={activeStageIndex}
          report={report}
          useFec={useFec}
          grossRateBps={grossRateBps}
          ebN0Db={ebN0Db}
          packetLossRate={packetLossRate}
          channelMode={channelMode}
        />

        <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
          {/* Gross Rate Selection (500, 1000, 2000, 4000 bps) */}
          <div>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '8px' }}>
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', fontSize: '12px', fontWeight: 600, color: '#94a3b8', textTransform: 'uppercase' }}>
                <Activity size={13} color="#818cf8" /> Gross Link Budget
              </span>
              <span style={{ fontSize: '12px', fontFamily: 'var(--font-mono)', fontWeight: 700, color: '#a5b4fc' }}>
                {(grossRateBps / 1000).toFixed(1)} kbps
              </span>
            </div>
            <div className="rate-btn-group">
              {[500, 1000, 2000, 4000].map((rate) => {
                const isSelected = grossRateBps === rate;
                return (
                  <button
                    key={rate}
                    type="button"
                    className={`rate-btn ${isSelected ? 'active' : ''}`}
                    onClick={() => onChangeGrossRate(rate)}
                    disabled={isProcessing}
                  >
                    <span>{rate >= 1000 ? `${rate / 1000}k` : `${rate}`} bps</span>
                    {rate === 2000 && <span style={{ marginLeft: '4px', color: '#f59e0b' }}>★</span>}
                  </button>
                );
              })}
            </div>
          </div>

          {/* Channel Impairment Mode */}
          <div>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '8px' }}>
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', fontSize: '12px', fontWeight: 600, color: '#94a3b8', textTransform: 'uppercase' }}>
                <Layers size={13} color="#818cf8" /> Physical Impairment Mode
              </span>
              <span style={{ fontSize: '11px', fontFamily: 'var(--font-mono)', color: '#64748b' }}>RF DSP</span>
            </div>
            <select
              value={channelMode}
              onChange={(e) => onChangeChannelMode(e.target.value)}
              disabled={isProcessing}
              className="styled-select"
            >
              <option value="bpsk_awgn">BPSK + AWGN Noise + Latency/Jitter</option>
              <option value="rayleigh_bpsk">Flat Rayleigh Fading + AWGN</option>
              <option value="abstract_packet">Abstract Packet Loss & Jitter</option>
              <option value="abstract_bit">Abstract Independent Bit Errors</option>
            </select>
          </div>

          {/* Sliders Grid: Eb/N0 and Packet Loss */}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: '20px' }}>
            {/* Eb/N0 SNR */}
            <div className="glass-card" style={{ padding: '16px', borderRadius: '16px' }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', fontSize: '12px', fontWeight: 600, color: '#94a3b8' }}>
                  <Sliders size={13} /> BPSK SNR (Eb/N0)
                </span>
                <span style={{ fontSize: '12px', fontFamily: 'var(--font-mono)', fontWeight: 700, color: ebN0Db <= 3 ? '#f59e0b' : '#34d399' }}>
                  {ebN0Db.toFixed(1)} dB
                </span>
              </div>
              <input
                type="range"
                min="-3.0"
                max="15.0"
                step="0.5"
                value={ebN0Db}
                onChange={(e) => onChangeEbN0Db(parseFloat(e.target.value))}
                disabled={isProcessing}
                className="styled-slider"
                aria-label="Eb/N0 SNR in dB"
              />
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '10px', color: '#64748b', fontFamily: 'var(--font-mono)' }}>
                <span>-3 dB (Harsh)</span>
                <span>5 dB</span>
                <span>15 dB (Clean)</span>
              </div>
            </div>

            {/* Packet Loss */}
            <div className="glass-card" style={{ padding: '16px', borderRadius: '16px' }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', fontSize: '12px', fontWeight: 600, color: '#94a3b8' }}>
                  <Sliders size={13} /> Packet Loss Rate
                </span>
                <span style={{ fontSize: '12px', fontFamily: 'var(--font-mono)', fontWeight: 700, color: packetLossRate > 0.1 ? '#f43f5e' : '#34d399' }}>
                  {(packetLossRate * 100).toFixed(0)}%
                </span>
              </div>
              <input
                type="range"
                min="0.0"
                max="0.4"
                step="0.05"
                value={packetLossRate}
                onChange={(e) => onChangePacketLossRate(parseFloat(e.target.value))}
                disabled={isProcessing}
                className="styled-slider"
                aria-label="Packet loss rate percentage"
              />
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '10px', color: '#64748b', fontFamily: 'var(--font-mono)' }}>
                <span>0% (Ideal)</span>
                <span>20%</span>
                <span>40% (Extreme)</span>
              </div>
            </div>
          </div>

          {/* FEC Toggle & PRNG Seed */}
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '16px', flexWrap: 'wrap' }}>
            <label
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: '10px',
                cursor: isProcessing ? 'not-allowed' : 'pointer',
                background: useFec ? 'rgba(16,185,129,0.1)' : 'rgba(15,23,42,0.6)',
                border: `1px solid ${useFec ? 'rgba(16,185,129,0.4)' : 'rgba(255,255,255,0.08)'}`,
                borderRadius: '14px',
                padding: '10px 16px',
                transition: 'all 0.2s ease'
              }}
            >
              <input
                type="checkbox"
                checked={useFec}
                onChange={(e) => onToggleFec(e.target.checked)}
                disabled={isProcessing}
                style={{ display: 'none' }}
              />
              <ShieldCheck size={16} color={useFec ? '#10b981' : '#64748b'} />
              <span style={{ fontSize: '13px', fontWeight: 600, color: useFec ? '#ffffff' : '#94a3b8' }}>
                XOR(4,3) Parity FEC
              </span>
              <span
                style={{
                  fontSize: '10px',
                  fontFamily: 'var(--font-mono)',
                  padding: '2px 8px',
                  borderRadius: '999px',
                  background: useFec ? 'rgba(16,185,129,0.2)' : 'rgba(255,255,255,0.06)',
                  color: useFec ? '#34d399' : '#64748b'
                }}
              >
                {useFec ? 'PROTECTED' : 'OFF'}
              </span>
            </label>

            <div style={{ display: 'inline-flex', alignItems: 'center', gap: '8px', background: 'rgba(15,23,42,0.6)', border: '1px solid rgba(255,255,255,0.08)', borderRadius: '14px', padding: '8px 16px' }}>
              <Disc3 size={14} color="#818cf8" />
              <span style={{ fontSize: '12px', color: '#94a3b8' }}>PRNG Seed:</span>
              <input
                type="number"
                value={seed}
                onChange={(e) => onChangeSeed(parseInt(e.target.value) || 1)}
                disabled={isProcessing}
                style={{
                  width: '70px',
                  background: 'transparent',
                  border: 'none',
                  color: '#ffffff',
                  fontFamily: 'var(--font-mono)',
                  fontSize: '13px',
                  fontWeight: 600,
                  outline: 'none'
                }}
                title="Deterministic PRNG seed for channel noise generation"
              />
            </div>
          </div>

          {/* Real-Time Link Telemetry */}
          <div className="telemetry-grid">
            <div className="metric-box">
              <span className="metric-label">Actual Wire Rate</span>
              <span className="metric-value">{actualWireBps ? `${actualWireBps}` : '--'} bps</span>
              <span className="metric-foot">R_wire = bits / duration</span>
            </div>

            <div className="metric-box">
              <span className="metric-label">Transmitted Bits</span>
              <span className="metric-value">{onAirBits ? `${onAirBits}` : '--'}</span>
              <span className="metric-foot">Header + Payload + FEC</span>
            </div>

            <div className="metric-box">
              <span className="metric-label">Measured BER</span>
              <span
                className="metric-value"
                style={{ color: measuredBer > 0 ? '#f59e0b' : '#34d399' }}
              >
                {(measuredBer * 100).toFixed(2)}%
              </span>
              <span className="metric-foot">Pre-FEC bit errors</span>
            </div>

            <div className="metric-box">
              <span className="metric-label">Packet Loss (PER)</span>
              <span
                className="metric-value"
                style={{ color: measuredPer > 0 ? '#f43f5e' : '#34d399' }}
              >
                {(measuredPer * 100).toFixed(1)}%
              </span>
              <span className="metric-foot">Post-channel packet drop</span>
            </div>
          </div>

          {/* Real-Time Packet Delivery Trace */}
          <div className="packet-trace-card">
            <div className="packet-trace-header">
              <span style={{ fontSize: '12px', fontWeight: 600, color: '#f1f5f9', letterSpacing: '0.04em', textTransform: 'uppercase' }}>
                Packet Delivery Trace
              </span>
              <span style={{ fontSize: '11px', fontFamily: 'var(--font-mono)', color: '#818cf8' }}>
                {deliveryEvents.length} frames logged
              </span>
            </div>

            <div className="packet-trace-list">
              {deliveryEvents.length === 0 ? (
                <div style={{ padding: '24px 0', textAlign: 'center', color: '#64748b', fontSize: '12px' }}>
                  <span>Transmit audio to observe simulated on-air RF packet frames</span>
                </div>
              ) : (
                deliveryEvents.map((ev, i) => (
                  <div key={i} className={`packet-row packet-${ev.status}`}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                      <span style={{ color: '#818cf8', fontWeight: 600 }}>#{ev.sequence}</span>
                      <span style={{ fontSize: '10px', padding: '1px 6px', borderRadius: '4px', background: ev.is_parity ? 'rgba(168,85,247,0.2)' : 'rgba(56,189,248,0.2)', color: ev.is_parity ? '#c084fc' : '#38bdf8' }}>
                        {ev.is_parity ? 'PARITY' : 'DATA'}
                      </span>
                      <span style={{ color: '#94a3b8' }}>{ev.on_air_bits}b</span>
                    </div>

                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                      <span style={{ color: '#64748b' }}>{ev.delivery_time_ms} ms</span>
                      <span style={{ fontSize: '10px', fontWeight: 700, padding: '2px 8px', borderRadius: '999px', background: ev.status === 'delivered' ? 'rgba(16,185,129,0.2)' : ev.status === 'recovered' ? 'rgba(0,240,255,0.2)' : ev.status === 'crc_fail' ? 'rgba(245,158,11,0.2)' : 'rgba(244,63,94,0.2)', color: ev.status === 'delivered' ? '#34d399' : ev.status === 'recovered' ? '#00f0ff' : ev.status === 'crc_fail' ? '#fbbf24' : '#fb7185' }}>
                        {ev.status === 'crc_fail' ? 'CRC FAIL' : ev.status.toUpperCase()}
                      </span>
                    </div>
                  </div>
                ))
              )}
            </div>
          </div>
        </div>
      </div>
    </section>
  );
};
