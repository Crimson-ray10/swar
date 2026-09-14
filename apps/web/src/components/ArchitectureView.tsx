import React from 'react';
import { Smartphone, Globe, Radio, Shield, CheckCircle2, Terminal } from 'lucide-react';

interface ArchitectureViewProps {
  callsign?: string;
}

export const ArchitectureView: React.FC<ArchitectureViewProps> = ({
  callsign = 'Sarthak Patil',
}) => {
  return (
    <div className="view-screen-container architecture-view-container animate-fade-in">
      {/* Screen Header matching Mockup */}
      <div className="screen-top-bar">
        <div>
          <h2 className="transceiver-title">System Architecture</h2>
          <p className="text-[11px] font-mono text-neutral-400">
            PS-0073 Specifications
          </p>
        </div>

        <div className="px-3 py-1 rounded-full bg-neutral-100 border border-neutral-200">
          <span className="text-[11px] font-mono text-neutral-700 font-semibold">100% Offline SLA</span>
        </div>
      </div>

      {/* 2-Column Grid Cards matching Screen 5 */}
      <div className="grid grid-cols-2 gap-3 mb-4">
        {/* Card 1: < 4GB RAM Target */}
        <div className="arch-spec-card">
          <div className="flex items-center gap-2 mb-2">
            <div className="w-7 h-7 rounded-lg bg-neutral-100 flex items-center justify-center text-black">
              <Smartphone size={15} />
            </div>
            <span className="text-xs font-bold text-neutral-900">&lt; 4GB RAM Target</span>
          </div>
          <p className="text-xs text-neutral-500 leading-relaxed">
            Optimized for low &amp; mid-range phones. Runs smoothly without thermal throttling on budget devices.
          </p>
        </div>

        {/* Card 2: 10 Indic Languages */}
        <div className="arch-spec-card">
          <div className="flex items-center gap-2 mb-2">
            <div className="w-7 h-7 rounded-lg bg-neutral-100 flex items-center justify-center text-black">
              <Globe size={15} />
            </div>
            <span className="text-xs font-bold text-neutral-900">10 Indic Languages</span>
          </div>
          <p className="text-xs text-neutral-500 leading-relaxed">
            10 Indic Scripts (Hindi, Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu, Odia, Bengali, English).
          </p>
        </div>

        {/* Card 3: Dual-Mesh Transport */}
        <div className="arch-spec-card">
          <div className="flex items-center gap-2 mb-2">
            <div className="w-7 h-7 rounded-lg bg-neutral-100 flex items-center justify-center text-black">
              <Radio size={15} />
            </div>
            <span className="text-xs font-bold text-neutral-900">Dual-Mesh Transport</span>
          </div>
          <p className="text-xs text-neutral-500 leading-relaxed">
            Wi-Fi Direct + Bluetooth. High-throughput P2P TCP socket (port 8990) with auto-fallback to RFCOMM.
          </p>
        </div>

        {/* Card 4: Neural Transceiver */}
        <div className="arch-spec-card">
          <div className="flex items-center gap-2 mb-2">
            <div className="w-7 h-7 rounded-lg bg-neutral-100 flex items-center justify-center text-black">
              <Terminal size={15} />
            </div>
            <span className="text-xs font-bold text-neutral-900">Neural Transceiver</span>
          </div>
          <p className="text-xs text-neutral-500 leading-relaxed">
            &lt; 200 Bytes / Packet. Silero VAD detects pauses; STT compresses speech into compact framed text packets.
          </p>
        </div>
      </div>

      {/* Card 5: Node Identity Banner */}
      <div className="node-identity-banner mb-4">
        <div className="flex items-center justify-between text-xs font-mono">
          <span className="font-bold text-neutral-900">Node: iTantra-SA1</span>
          <span className="text-neutral-500">Callsign: {callsign}</span>
          <span className="text-emerald-600 font-semibold">8988 TCP</span>
        </div>
      </div>

      {/* Card 6: PS-0073 Mandate Compliance */}
      <div className="mandate-compliance-card">
        <div className="flex items-center gap-2 mb-2 text-xs font-bold text-neutral-900">
          <Shield size={16} className="text-emerald-600" />
          <span>PS-0073 Mandate Compliance</span>
        </div>

        <ul className="text-xs text-neutral-600 space-y-1.5 list-none pl-0">
          <li className="flex items-center gap-2">
            <CheckCircle2 size={13} className="text-emerald-500 flex-shrink-0" />
            <span>100% Offline • Zero Cloud Dependencies</span>
          </li>
          <li className="flex items-center gap-2">
            <CheckCircle2 size={13} className="text-emerald-500 flex-shrink-0" />
            <span>Open Source Small Footprint Models (Silero VAD, IndicConformer, Piper)</span>
          </li>
          <li className="flex items-center gap-2">
            <CheckCircle2 size={13} className="text-emerald-500 flex-shrink-0" />
            <span>Multi-Hop P2P Mesh with Local Speech-to-Text &amp; Voice Synthesis</span>
          </li>
          <li className="flex items-center gap-2">
            <CheckCircle2 size={13} className="text-emerald-500 flex-shrink-0" />
            <span>Low-Latency Sub-Second Speech Turnaround (&lt; 200ms)</span>
          </li>
        </ul>
      </div>
    </div>
  );
};
