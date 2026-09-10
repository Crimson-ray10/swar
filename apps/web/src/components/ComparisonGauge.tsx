import React from 'react';
import { Download, Zap } from 'lucide-react';
import { RunReport } from '../types';

interface ComparisonGaugeProps {
  actualWireBps: number;
  compressionRatio: number;
  lastReport: RunReport | null;
}

export const ComparisonGauge: React.FC<ComparisonGaugeProps> = ({
  actualWireBps,
  compressionRatio,
  lastReport,
}) => {
  const pcmBps = 256000;
  // Percentage of 256 kbps consumed by iTantra (e.g. 1000 / 256000 = ~0.39%)
  const percentageOfPcm = actualWireBps > 0 ? (actualWireBps / pcmBps) * 100 : 0;

  const handleExportJson = () => {
    if (!lastReport) return;
    const blob = new Blob([JSON.stringify(lastReport, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `itantra_run_${lastReport.run_id.slice(0, 8)}.json`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <footer className="bottom-bar">
      <div className="comparison-gauge">
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <Zap size={18} color="var(--accent-emerald)" />
          <span style={{ fontWeight: 600, fontSize: '13px' }}>Bandwidth Comparison</span>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', flex: 1, gap: '4px' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '11px', color: 'var(--text-secondary)' }}>
            <span>iTantra On-Air Wire: <strong style={{ color: 'var(--accent-cyan)' }}>{actualWireBps ? `${actualWireBps} bps` : '--'}</strong></span>
            <span>Uncompressed PCM: <strong style={{ color: 'var(--text-muted)' }}>256,000 bps (16 kHz 16-bit)</strong></span>
          </div>

          <div className="gauge-bar-wrapper">
            <div
              className="gauge-fill"
              style={{ width: `${Math.max(1.5, Math.min(100, percentageOfPcm * 10))}%` }}
              title={`Consumes only ${percentageOfPcm.toFixed(2)}% of raw speech bandwidth`}
            />
          </div>
        </div>

        <div style={{ textAlign: 'right', minWidth: '130px' }}>
          <div style={{ fontSize: '18px', fontWeight: 700, color: 'var(--accent-emerald)', fontFamily: 'var(--font-mono)' }}>
            {compressionRatio > 0 ? `${compressionRatio.toFixed(0)}×` : '--'}
          </div>
          <div style={{ fontSize: '10px', color: 'var(--text-muted)', textTransform: 'uppercase' }}>
            Compression vs PCM
          </div>
        </div>
      </div>

      <div style={{ display: 'flex', gap: '8px' }}>
        <button
          className="btn-secondary"
          onClick={handleExportJson}
          disabled={!lastReport}
          title="Download reproducible run telemetry report"
        >
          <Download size={14} /> Export Report
        </button>
      </div>
    </footer>
  );
};
