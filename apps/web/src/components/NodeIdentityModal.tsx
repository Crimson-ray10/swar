import React, { useState } from 'react';
import { X, Check } from 'lucide-react';

interface NodeIdentityModalProps {
  isOpen: boolean;
  onClose: () => void;
  callsign: string;
  onSaveCallsign: (newCallsign: string) => void;
  localIp?: string;
  activeTransport?: string | null;
}

export const NodeIdentityModal: React.FC<NodeIdentityModalProps> = ({
  isOpen,
  onClose,
  callsign,
  onSaveCallsign,
  localIp = '192.168.49.1',
  activeTransport = 'Wi-Fi Direct + Bluetooth 5.x',
}) => {
  const [name, setName] = useState(callsign);
  const [saved, setSaved] = useState(false);

  if (!isOpen) return null;

  const initials = name
    .split(' ')
    .map((n) => n[0])
    .join('')
    .toUpperCase()
    .slice(0, 2) || 'SA';

  const handleSave = (e: React.FormEvent) => {
    e.preventDefault();
    onSaveCallsign(name.trim() || 'Sarthak Patil');
    setSaved(true);
    setTimeout(() => {
      setSaved(false);
      onClose();
    }, 400);
  };

  return (
    <div className="modal-backdrop-clean" onClick={onClose}>
      <div
        className="node-modal-sheet animate-sheet-up"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Modal Header */}
        <div className="flex items-center justify-between pb-3 border-b border-neutral-100">
          <h3 className="text-sm font-bold text-neutral-900">Node Identity &amp; Hardware</h3>
          <button
            type="button"
            onClick={onClose}
            className="w-7 h-7 rounded-full bg-neutral-100 text-neutral-500 hover:text-black flex items-center justify-center transition"
          >
            <X size={15} />
          </button>
        </div>

        {/* Profile Avatar */}
        <div className="flex flex-col items-center py-4">
          <div className="w-16 h-16 rounded-full bg-neutral-100 border border-neutral-200 flex items-center justify-center text-lg font-bold text-neutral-900 shadow-sm mb-1">
            {initials}
          </div>
          <span className="text-[11px] font-mono text-neutral-400">Node Call-Sign</span>
        </div>

        {/* Edit Callsign Form */}
        <form onSubmit={handleSave} className="flex flex-col gap-4">
          <div>
            <label className="text-xs font-semibold text-neutral-600 mb-1 block">
              Callsign / User Name
            </label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="e.g. Sarthak Patil"
              className="clean-text-input text-sm font-medium"
            />
          </div>

          {/* Device Specifications Box */}
          <div className="rounded-xl bg-neutral-50 border border-neutral-200 p-3.5 flex flex-col gap-2">
            <span className="text-[11px] font-bold uppercase tracking-wider text-neutral-500">
              Device Specifications
            </span>

            <div className="grid grid-cols-2 gap-y-2 text-xs font-mono pt-1">
              <div>
                <div className="text-[10px] text-neutral-400">Device Model</div>
                <div className="font-bold text-neutral-800">VIVO V2409</div>
              </div>

              <div>
                <div className="text-[10px] text-neutral-400">Build ID</div>
                <div className="font-bold text-neutral-800 truncate" title="DP2A.220705.001.A2_CS_V000L1">
                  DP2A.220705.001...
                </div>
              </div>

              <div>
                <div className="text-[10px] text-neutral-400">Node ID</div>
                <div className="font-bold text-neutral-800">iTantra - SA1</div>
              </div>

              <div>
                <div className="text-[10px] text-neutral-400">Android OS</div>
                <div className="font-bold text-neutral-800">Android 14 (API 34)</div>
              </div>

              <div>
                <div className="text-[10px] text-neutral-400">Radio Mesh</div>
                <div className="font-bold text-neutral-800 truncate">{activeTransport}</div>
              </div>

              <div>
                <div className="text-[10px] text-neutral-400">Protocol Stream</div>
                <div className="font-bold text-neutral-800">{localIp}:8988</div>
              </div>
            </div>
          </div>

          {/* Save Changes Solid Black Button */}
          <button
            type="submit"
            className="black-pill-btn w-full justify-center mt-1"
          >
            {saved ? (
              <>
                <Check size={16} className="text-emerald-400" />
                <span>Saved</span>
              </>
            ) : (
              <span>Save Changes</span>
            )}
          </button>
        </form>
      </div>
    </div>
  );
};
