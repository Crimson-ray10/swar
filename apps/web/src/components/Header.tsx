import React from 'react';
import { Cpu, HardDrive } from 'lucide-react';

interface HeaderProps {
  sessionId: string;
  hardwareStatus: string;
  isStreaming: boolean;
}

export const Header: React.FC<HeaderProps> = ({
  sessionId,
  hardwareStatus,
  isStreaming,
}) => {
  return (
    <header className="top-bar">
      <div className="brand">
        <span className="brand-icon">📻</span>
        <div>
          <h1 className="brand-title">iTantra</h1>
          <p style={{ fontSize: '11px', color: 'var(--text-muted)' }}>
            Indian Multilingual Semantic Radio Transceiver (0.5 – 2.0 kbps)
          </p>
        </div>
        <span className="brand-badge">MVP text_v1</span>
      </div>

      <div className="system-status">
        <div style={{ display: 'flex', alignItems: 'center' }}>
          <span className="status-indicator" />
          <span>{isStreaming ? 'TRANSMITTING' : 'RADIO READY'}</span>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
          <Cpu size={14} color="var(--accent-cyan)" />
          <span>{hardwareStatus}</span>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
          <HardDrive size={14} color="var(--accent-purple)" />
          <span>LOCAL SIMULATED CHANNEL</span>
        </div>

        <div style={{ color: 'var(--text-muted)' }}>
          SESSION: <span style={{ color: 'var(--text-primary)' }}>{sessionId.slice(0, 8)}</span>
        </div>
      </div>
    </header>
  );
};
