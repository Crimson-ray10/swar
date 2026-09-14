import React from 'react';
import { ArrowRight, Cpu, Radio, Shield, Globe, Smartphone } from 'lucide-react';

interface HomeViewProps {
  onGetStarted: () => void;
  onOpenProfile: () => void;
  onOpenArchitecture: () => void;
  callsign?: string;
  connectedNodesCount?: number;
}

export const HomeView: React.FC<HomeViewProps> = ({
  onGetStarted,
  onOpenProfile,
  onOpenArchitecture,
  callsign = 'Sarthak Patil',
  connectedNodesCount = 1,
}) => {
  const initials = callsign
    .split(' ')
    .map((n) => n[0])
    .join('')
    .toUpperCase()
    .slice(0, 2) || 'SA';

  return (
    <div className="view-screen-container home-view-container animate-fade-in">
      {/* Top App Bar with Avatar */}
      <div className="screen-top-bar">
        <div className="flex items-center gap-2">
          <span className="node-status-dot pulse-dot"></span>
          <span className="text-xs font-mono text-neutral-500 font-semibold uppercase tracking-wider">
            {connectedNodesCount > 1 ? `${connectedNodesCount} Nodes Online` : 'Offline Mesh Ready'}
          </span>
        </div>

        {/* User Avatar Circle */}
        <button
          type="button"
          onClick={onOpenProfile}
          className="avatar-circle-btn"
          title={`Callsign: ${callsign} (Click to open Node Identity)`}
        >
          <span>{initials}</span>
        </button>
      </div>

      {/* Main Branding Section */}
      <div className="home-hero-content">
        <h1 className="home-brand-title">
          iTantra<span className="text-black">.</span>
        </h1>

        <p className="home-brand-subtitle">
          iTantra is a fully offline, AI-powered multilingual voice communication application designed for use in zero-connectivity environments.
        </p>

        {/* Get Started Solid Black Button */}
        <div className="pt-2 pb-4">
          <button
            type="button"
            onClick={onGetStarted}
            className="black-pill-btn shadow-md hover:scale-102 transition-transform"
          >
            <span>Get Started</span>
            <ArrowRight size={16} />
          </button>
        </div>

        {/* Clean Vector Line Art Illustration */}
        <div className="home-illustration-box">
          <svg
            viewBox="0 0 320 220"
            fill="none"
            xmlns="http://www.w3.org/2000/svg"
            className="w-full max-w-[260px] h-auto mx-auto"
          >
            {/* Ambient sound wave rings */}
            <circle cx="160" cy="110" r="95" stroke="#f1f5f9" strokeWidth="2" strokeDasharray="4 4" />
            <circle cx="160" cy="110" r="75" stroke="#e2e8f0" strokeWidth="1.5" />
            <circle cx="160" cy="110" r="55" stroke="#cbd5e1" strokeWidth="1" strokeDasharray="3 3" />

            {/* Operator body outline */}
            <path
              d="M120 210 C120 170, 140 155, 160 155 C180 155, 200 170, 200 210"
              stroke="#0f172a"
              strokeWidth="2.5"
              strokeLinecap="round"
            />
            {/* Shoulders & Jacket detail */}
            <path d="M142 165 L142 205" stroke="#0f172a" strokeWidth="1.5" />
            <path d="M178 165 L178 205" stroke="#0f172a" strokeWidth="1.5" />

            {/* Head & Face */}
            <path
              d="M146 110 C146 95, 174 95, 174 110 C174 126, 146 126, 146 110 Z"
              fill="#ffffff"
              stroke="#0f172a"
              strokeWidth="2.5"
            />
            {/* Hair */}
            <path
              d="M144 105 C146 90, 172 88, 176 102 C172 96, 155 96, 144 105 Z"
              fill="#0f172a"
            />
            {/* Glasses / headset line */}
            <path d="M149 108 L159 108 M161 108 L171 108" stroke="#0f172a" strokeWidth="1.5" />
            {/* Smile / mouth */}
            <path d="M156 120 Q160 123 164 120" stroke="#0f172a" strokeWidth="1.2" strokeLinecap="round" />

            {/* Raised Hand holding Mobile Device */}
            <path
              d="M185 190 L195 155 L215 150 L212 180"
              stroke="#0f172a"
              strokeWidth="2.2"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
            {/* Mobile Transceiver Phone */}
            <rect
              x="202"
              y="125"
              width="28"
              height="50"
              rx="5"
              fill="#0f172a"
              stroke="#0f172a"
              strokeWidth="1.5"
            />
            <rect x="205" y="130" width="22" height="38" rx="2" fill="#ffffff" />
            {/* Antenna / signal waves */}
            <path d="M216 125 L216 114" stroke="#0f172a" strokeWidth="2" strokeLinecap="round" />
            <path d="M222 118 Q226 114 222 110" stroke="#0f172a" strokeWidth="1.5" strokeLinecap="round" />
            <path d="M226 121 Q232 114 226 107" stroke="#0f172a" strokeWidth="1.5" strokeLinecap="round" />

            {/* Micro voice waves from phone screen */}
            <line x1="210" y1="145" x2="222" y2="145" stroke="#0f172a" strokeWidth="2" strokeLinecap="round" />
            <line x1="213" y1="149" x2="219" y2="149" stroke="#0f172a" strokeWidth="2" strokeLinecap="round" />
          </svg>
        </div>
      </div>

      {/* System Architecture Overview Cards */}
      <div className="system-arch-preview-section">
        <div className="flex items-center justify-between mb-3 px-1">
          <span className="text-xs font-bold text-neutral-900 uppercase tracking-wider flex items-center gap-1.5">
            <Cpu size={14} className="text-neutral-700" />
            <span>System Architecture</span>
          </span>
          <button
            type="button"
            onClick={onOpenArchitecture}
            className="text-[11px] font-mono text-neutral-500 hover:text-black font-semibold flex items-center gap-1"
          >
            <span>PS-0073 Specifications</span>
            <ArrowRight size={12} />
          </button>
        </div>

        <div className="grid grid-cols-2 gap-2.5">
          <div
            onClick={onOpenArchitecture}
            className="arch-mini-card cursor-pointer hover:border-neutral-400 transition"
          >
            <div className="flex items-center gap-1.5 text-xs font-bold text-neutral-900 mb-1">
              <Smartphone size={13} className="text-neutral-700" />
              <span>&lt; 4GB RAM Target</span>
            </div>
            <p className="text-[11px] text-neutral-500 leading-tight">
              Optimized for low & mid-range phones without thermal throttling.
            </p>
          </div>

          <div
            onClick={onOpenArchitecture}
            className="arch-mini-card cursor-pointer hover:border-neutral-400 transition"
          >
            <div className="flex items-center gap-1.5 text-xs font-bold text-neutral-900 mb-1">
              <Globe size={13} className="text-neutral-700" />
              <span>10 Indic Languages</span>
            </div>
            <p className="text-[11px] text-neutral-500 leading-tight">
              Full Indic script support with offline acoustic synthesis.
            </p>
          </div>

          <div
            onClick={onOpenArchitecture}
            className="arch-mini-card cursor-pointer hover:border-neutral-400 transition"
          >
            <div className="flex items-center gap-1.5 text-xs font-bold text-neutral-900 mb-1">
              <Radio size={13} className="text-neutral-700" />
              <span>Dual-Mesh Transport</span>
            </div>
            <p className="text-[11px] text-neutral-500 leading-tight">
              Wi-Fi Direct P2P + Bluetooth 5.x RFCOMM stream.
            </p>
          </div>

          <div
            onClick={onOpenArchitecture}
            className="arch-mini-card cursor-pointer hover:border-neutral-400 transition"
          >
            <div className="flex items-center gap-1.5 text-xs font-bold text-neutral-900 mb-1">
              <Shield size={13} className="text-neutral-700" />
              <span>100% Offline</span>
            </div>
            <p className="text-[11px] text-neutral-500 leading-tight">
              Zero cloud dependency. Silero VAD + IndicConformer STT.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};
