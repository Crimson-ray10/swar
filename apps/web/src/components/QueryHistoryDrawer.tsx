import React from 'react';
import { History, X, Clock, ShieldCheck, ChevronRight, MessageSquare, Trash2, Radio } from 'lucide-react';
import { RunReport } from '../types';

export interface HistoryItem {
  id: string;
  timestamp: string;
  report: RunReport;
}

interface QueryHistoryDrawerProps {
  isOpen: boolean;
  onClose: () => void;
  history: HistoryItem[];
  onSelectHistoryItem: (item: HistoryItem) => void;
  onClearHistory: () => void;
}

export const QueryHistoryDrawer: React.FC<QueryHistoryDrawerProps> = ({
  isOpen,
  onClose,
  history,
  onSelectHistoryItem,
  onClearHistory
}) => {
  if (!isOpen) return null;

  return (
    <div className="drawer-backdrop" onClick={onClose}>
      <div
        className="drawer-panel"
        onClick={(e) => e.stopPropagation()}
        aria-label="Transmission Session History"
      >
        {/* Header */}
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', paddingBottom: '16px', borderBottom: '1px solid rgba(255,255,255,0.08)', marginBottom: '20px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
            <div style={{ padding: '8px', borderRadius: '12px', background: 'rgba(99,102,241,0.2)', color: '#818cf8', border: '1px solid rgba(99,102,241,0.3)' }}>
              <History size={18} />
            </div>
            <div>
              <h2 style={{ fontSize: '16px', fontWeight: 700, color: '#ffffff' }}>Transmission History</h2>
              <p style={{ fontSize: '11px', color: '#94a3b8' }}>Session audio transmissions & channel runs</p>
            </div>
          </div>

          <button
            onClick={onClose}
            style={{ padding: '8px', background: 'none', border: 'none', color: '#94a3b8', cursor: 'pointer', borderRadius: '8px' }}
            title="Close drawer"
          >
            <X size={18} />
          </button>
        </div>

        {/* History Item List */}
        <div style={{ flex: 1, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '10px', paddingRight: '4px' }}>
          {history.length === 0 ? (
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: '60px 20px', textAlign: 'center', color: '#64748b' }}>
              <MessageSquare size={36} style={{ strokeWidth: 1.5, marginBottom: '12px', opacity: 0.5 }} />
              <p style={{ fontSize: '14px', fontWeight: 500, color: '#94a3b8' }}>No transmissions yet</p>
              <p style={{ fontSize: '12px', color: '#64748b', marginTop: '4px', maxWidth: '240px' }}>
                Transmit speech via the microphone or sample buttons to record session runs.
              </p>
            </div>
          ) : (
            history.map((item) => {
              const rep = item.report;
              const isExact = rep.receiver.status === 'exact';
              const isPartial = rep.receiver.status === 'partial';

              return (
                <div
                  key={item.id}
                  onClick={() => {
                    onSelectHistoryItem(item);
                    onClose();
                  }}
                  className="glass-panel"
                  style={{
                    borderRadius: '16px',
                    padding: '14px',
                    cursor: 'pointer',
                    transition: 'all 0.2s ease',
                    border: '1px solid rgba(255,255,255,0.07)'
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: '8px', marginBottom: '8px' }}>
                    <span style={{ fontSize: '13px', fontWeight: 600, color: '#f8fafc', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      "{rep.transcript.raw || rep.transcript.normalized || 'Audio Transmission'}"
                    </span>
                    <ChevronRight size={16} color="#64748b" style={{ flexShrink: 0, marginTop: '2px' }} />
                  </div>

                  {rep.receiver.text && (
                    <p style={{ fontSize: '12px', color: '#94a3b8', lineHeight: 1.4, marginBottom: '10px', overflow: 'hidden', display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical' }}>
                      Rx: {rep.receiver.text}
                    </p>
                  )}

                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', fontSize: '11px', fontFamily: 'var(--font-mono)', color: '#64748b', paddingTop: '8px', borderTop: '1px solid rgba(255,255,255,0.06)' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                      <span style={{ color: '#818cf8', display: 'flex', alignItems: 'center', gap: '4px' }}>
                        <Radio size={11} />
                        {rep.transport.gross_rate_bps} bps
                      </span>
                      <span style={{ color: isExact ? '#34d399' : isPartial ? '#fbbf24' : '#f87171', display: 'flex', alignItems: 'center', gap: '4px' }}>
                        <ShieldCheck size={11} />
                        {rep.receiver.status.toUpperCase()}
                      </span>
                    </div>

                    <span style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
                      <Clock size={11} />
                      {item.timestamp}
                    </span>
                  </div>
                </div>
              );
            })
          )}
        </div>

        {/* Footer */}
        {history.length > 0 && (
          <div style={{ paddingTop: '16px', marginTop: '16px', borderTop: '1px solid rgba(255,255,255,0.08)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <span style={{ fontSize: '11px', fontFamily: 'var(--font-mono)', color: '#64748b' }}>
              {history.length} runs saved
            </span>
            <button
              onClick={onClearHistory}
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: '6px',
                padding: '6px 12px',
                background: 'rgba(244,63,94,0.1)',
                border: '1px solid rgba(244,63,94,0.3)',
                borderRadius: '8px',
                color: '#fb7185',
                fontSize: '12px',
                cursor: 'pointer'
              }}
            >
              <Trash2 size={13} />
              <span>Clear History</span>
            </button>
          </div>
        )}
      </div>
    </div>
  );
};
