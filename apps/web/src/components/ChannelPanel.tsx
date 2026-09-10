import React from 'react';
import { ShieldCheck } from 'lucide-react';
import { DeliveryEvent } from '../types';

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
}) => {
  return (
    <div className="panel-card">
      <div className="panel-title">
        <span>Low-Bitrate Link & Channel</span>
        <span className="panel-title-badge">ITP/1 Over Simulated RF</span>
      </div>

      {/* Channel Mode */}
      <div className="control-group">
        <label className="control-label">Channel Impairment Mode</label>
        <select
          value={channelMode}
          onChange={(e) => onChangeChannelMode(e.target.value)}
          disabled={isProcessing}
        >
          <option value="bpsk_awgn">BPSK + AWGN Noise + Latency/Jitter</option>
          <option value="rayleigh_bpsk">Flat Rayleigh Fading + AWGN</option>
          <option value="abstract_packet">Abstract Packet Loss & Jitter</option>
          <option value="abstract_bit">Abstract Independent Bit Errors</option>
        </select>
      </div>

      {/* Gross Rate Selection */}
      <div className="control-group">
        <label className="control-label">
          <span>Gross Link Budget</span>
          <span className="control-value">{grossRateBps / 1000} kbps</span>
        </label>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '6px' }}>
          {[500, 1000, 2000, 4000].map((rate) => (
            <button
              key={rate}
              className="btn-secondary"
              style={{
                borderColor: grossRateBps === rate ? 'var(--accent-cyan)' : 'var(--border-color)',
                backgroundColor: grossRateBps === rate ? 'rgba(6, 182, 212, 0.15)' : 'var(--bg-card-subtle)',
                color: grossRateBps === rate ? 'var(--accent-cyan)' : 'var(--text-primary)',
                fontWeight: grossRateBps === rate ? 600 : 400,
                fontSize: '12px',
                padding: '6px 4px',
                justifyContent: 'center',
              }}
              onClick={() => onChangeGrossRate(rate)}
              disabled={isProcessing}
            >
              {rate >= 1000 ? `${rate / 1000}k` : `${rate}`} {rate === 2000 ? '★' : ''}
            </button>
          ))}
        </div>
      </div>

      {/* Impairment Sliders */}
      <div className="control-group">
        <label className="control-label">
          <span>BPSK AWGN SNR (Eb/N0)</span>
          <span className="control-value">{ebN0Db.toFixed(1)} dB</span>
        </label>
        <input
          type="range"
          min="-3.0"
          max="15.0"
          step="0.5"
          value={ebN0Db}
          onChange={(e) => onChangeEbN0Db(parseFloat(e.target.value))}
          disabled={isProcessing}
        />
      </div>

      <div className="control-group">
        <label className="control-label">
          <span>Packet Loss Rate</span>
          <span className="control-value">{(packetLossRate * 100).toFixed(0)}%</span>
        </label>
        <input
          type="range"
          min="0.0"
          max="0.4"
          step="0.05"
          value={packetLossRate}
          onChange={(e) => onChangePacketLossRate(parseFloat(e.target.value))}
          disabled={isProcessing}
        />
      </div>

      {/* FEC toggle & Seed */}
      <div style={{ display: 'flex', gap: '12px', alignItems: 'center' }}>
        <label style={{ display: 'flex', alignItems: 'center', gap: '8px', cursor: 'pointer', fontSize: '13px' }}>
          <input
            type="checkbox"
            checked={useFec}
            onChange={(e) => onToggleFec(e.target.checked)}
            disabled={isProcessing}
          />
          <ShieldCheck size={16} color="var(--accent-purple)" />
          <span>XOR(4,3) Parity FEC</span>
        </label>

        <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: '6px' }}>
          <span style={{ fontSize: '12px', color: 'var(--text-muted)' }}>Seed:</span>
          <input
            type="number"
            value={seed}
            onChange={(e) => onChangeSeed(parseInt(e.target.value) || 1)}
            style={{ width: '70px', padding: '4px 6px', fontSize: '12px' }}
            disabled={isProcessing}
          />
        </div>
      </div>

      {/* Telemetry Numbers */}
      <div className="telemetry-grid">
        <div className="metric-box">
          <span className="metric-label">Actual Wire Rate</span>
          <span className="metric-value">{actualWireBps ? `${actualWireBps} bps` : '--'}</span>
        </div>
        <div className="metric-box">
          <span className="metric-label">Transmitted Bits</span>
          <span className="metric-value">{onAirBits ? `${onAirBits} bits` : '--'}</span>
        </div>
        <div className="metric-box">
          <span className="metric-label">Measured BER</span>
          <span className="metric-value" style={{ color: measuredBer > 0 ? 'var(--accent-amber)' : 'var(--accent-cyan)' }}>
            {(measuredBer * 100).toFixed(2)}%
          </span>
        </div>
        <div className="metric-box">
          <span className="metric-label">Packet Loss (PER)</span>
          <span className="metric-value" style={{ color: measuredPer > 0 ? 'var(--accent-rose)' : 'var(--accent-emerald)' }}>
            {(measuredPer * 100).toFixed(1)}%
          </span>
        </div>
      </div>

      {/* Real-time Packet Timeline */}
      <div className="control-group">
        <div className="control-label">
          <span>Packet Delivery Trace</span>
          <span style={{ fontSize: '11px', color: 'var(--text-muted)' }}>
            {deliveryEvents.length} packets
          </span>
        </div>
        <div className="timeline-container">
          {deliveryEvents.length === 0 ? (
            <div style={{ color: 'var(--text-muted)', textAlign: 'center', margin: 'auto' }}>
              Transmit speech to observe simulated on-air packets
            </div>
          ) : (
            deliveryEvents.map((ev, i) => (
              <div key={i} className={`packet-pill packet-${ev.status}`}>
                <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
                  <span style={{ fontWeight: 600 }}>#{ev.sequence}</span>
                  <span>{ev.is_parity ? '[PARITY]' : '[DATA]'}</span>
                  <span>{ev.on_air_bits}b</span>
                </div>
                <div style={{ display: 'flex', gap: '10px', alignItems: 'center' }}>
                  <span>{ev.delivery_time_ms}ms</span>
                  <span style={{ textTransform: 'uppercase', fontWeight: 600, fontSize: '11px' }}>
                    {ev.status === 'crc_fail' ? 'CRC FAIL' : ev.status}
                  </span>
                </div>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
};
