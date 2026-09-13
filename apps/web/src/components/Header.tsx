import React, { useState } from 'react';
import { History, Copy, Check, RotateCcw, Cpu } from 'lucide-react';

interface HeaderProps {
  sessionId: string;
  hardwareStatus: string;
  isStreaming: boolean;
  activeSection: string;
  onNavigate: (section: string) => void;
  onOpenHistory: () => void;
  historyCount: number;
  onReset: () => void;
}

export const Header: React.FC<HeaderProps> = ({
  sessionId,
  hardwareStatus,
  isStreaming,
  activeSection,
  onNavigate,
  onOpenHistory,
  historyCount,
  onReset
}) => {
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [copied, setCopied] = useState(false);

  const handleCopySession = (e: React.MouseEvent) => {
    e.stopPropagation();
    navigator.clipboard.writeText(sessionId);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };

  const handleNavClick = (section: string) => {
    onNavigate(section);
    setMobileMenuOpen(false);
  };

  return (
    <div className="header-wrapper">
      <header className="header">
        {/* Logo Button (Exact July Circular Pill with Concentric Rings) */}
        <button
          onClick={() => handleNavClick('hero')}
          className="logo-btn"
          aria-label="Voice Bridge Home"
          title="Voice Bridge Offline Voice Communication"
        >
          <svg viewBox="0 0 100 100" style={{ width: '28px', height: '28px' }}>
            <circle cx="50" cy="50" r="46" fill="#ffffff" />
            <circle cx="50" cy="50" r="24" fill="none" stroke="#000000" strokeWidth="8" />
          </svg>
        </button>

        {/* Desktop Nav Pill (Exact July White Rounded-Full Container) */}
        <nav className="nav-pill" aria-label="Main Navigation">
          <button
            onClick={() => handleNavClick('hero')}
            className={`nav-link ${activeSection === 'hero' ? 'active' : ''}`}
          >
            Home
          </button>
          <button
            onClick={() => handleNavClick('studio')}
            className={`nav-link ${activeSection === 'studio' ? 'active' : ''}`}
          >
            Radio Studio
          </button>
          <button
            onClick={() => handleNavClick('channel')}
            className={`nav-link ${activeSection === 'channel' ? 'active' : ''}`}
          >
            RF Channel
          </button>
          <button
            onClick={() => handleNavClick('receiver')}
            className={`nav-link ${activeSection === 'receiver' ? 'active' : ''}`}
          >
            Receiver Sink
          </button>
          <button
            onClick={() => {
              onOpenHistory();
              setMobileMenuOpen(false);
            }}
            className="nav-link"
            title="View transmission session history"
          >
            <History size={13} />
            <span>History</span>
            {historyCount > 0 && (
              <span
                style={{
                  width: '18px',
                  height: '18px',
                  fontSize: '10px',
                  backgroundColor: '#6366f1',
                  color: '#ffffff',
                  borderRadius: '50%',
                  display: 'inline-flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  fontFamily: 'var(--font-mono)'
                }}
              >
                {historyCount}
              </span>
            )}
          </button>
        </nav>

        {/* Status & Session Pill (Exact July Dark Pill Treatment) */}
        <div
          className="signin-pill"
          onClick={handleCopySession}
          title={`Click to copy session: ${sessionId} | Hardware: ${hardwareStatus}`}
        >
          <span className={`status-dot-live ${isStreaming ? 'transmitting' : ''}`} />
          <span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', letterSpacing: '0.04em' }}>
            {isStreaming ? 'TRANSMITTING' : sessionId.slice(0, 8)}
          </span>
          {copied ? (
            <Check size={13} color="#10b981" />
          ) : (
            <Copy size={12} style={{ opacity: 0.6 }} />
          )}
          <button
            onClick={(e) => {
              e.stopPropagation();
              onReset();
            }}
            disabled={isStreaming}
            style={{
              background: 'none',
              border: 'none',
              color: 'inherit',
              cursor: isStreaming ? 'not-allowed' : 'pointer',
              display: 'flex',
              alignItems: 'center',
              marginLeft: '4px',
              opacity: 0.7
            }}
            title="Reset transceiver console"
          >
            <RotateCcw size={13} />
          </button>
        </div>

        {/* Mobile Hamburger Button (Exact July Morphing Bar Button) */}
        <button
          onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
          className={`burger-btn ${mobileMenuOpen ? 'open' : ''}`}
          aria-label="Toggle Navigation Menu"
          aria-expanded={mobileMenuOpen}
        >
          <span className="burger-bar" />
          <span className="burger-bar" />
          <span className="burger-bar" />
        </button>
      </header>

      {/* Mobile Menu Sheet (Exact July Floating White Card) */}
      {mobileMenuOpen && (
        <>
          <div
            className="mobile-overlay"
            onClick={() => setMobileMenuOpen(false)}
          />
          <div className="mobile-menu" aria-label="Mobile Navigation Menu">
            <nav className="mobile-nav">
              <button
                onClick={() => handleNavClick('hero')}
                className={`mobile-nav-link ${activeSection === 'hero' ? 'active' : ''}`}
              >
                Home
              </button>
              <button
                onClick={() => handleNavClick('studio')}
                className={`mobile-nav-link ${activeSection === 'studio' ? 'active' : ''}`}
              >
                Radio Studio
              </button>
              <button
                onClick={() => handleNavClick('channel')}
                className={`mobile-nav-link ${activeSection === 'channel' ? 'active' : ''}`}
              >
                RF Channel
              </button>
              <button
                onClick={() => handleNavClick('receiver')}
                className={`mobile-nav-link ${activeSection === 'receiver' ? 'active' : ''}`}
              >
                Receiver Sink
              </button>
              <button
                onClick={() => {
                  setMobileMenuOpen(false);
                  onOpenHistory();
                }}
                className="mobile-nav-link"
                style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', width: '100%' }}
              >
                <span>Transmission History</span>
                {historyCount > 0 && (
                  <span
                    style={{
                      fontSize: '11px',
                      backgroundColor: '#6366f1',
                      color: '#ffffff',
                      borderRadius: '999px',
                      padding: '2px 8px',
                      fontFamily: 'var(--font-mono)'
                    }}
                  >
                    {historyCount}
                  </span>
                )}
              </button>
              <div
                className="mobile-signin-btn"
                onClick={handleCopySession}
              >
                <Cpu size={14} style={{ marginRight: '6px' }} />
                <span>SESSION: {sessionId.slice(0, 8)}</span>
              </div>
            </nav>
          </div>
        </>
      )}
    </div>
  );
};
