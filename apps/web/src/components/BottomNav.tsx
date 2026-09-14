import React from 'react';
import { Home, Radio, Download, Cpu, Mic } from 'lucide-react';

export type TabType = 'home' | 'transceiver' | 'models' | 'architecture';

interface BottomNavProps {
  activeTab: TabType;
  onSelectTab: (tab: TabType) => void;
  onPttPress?: () => void;
  isPttActive?: boolean;
}

export const BottomNav: React.FC<BottomNavProps> = ({
  activeTab,
  onSelectTab,
  onPttPress,
  isPttActive = false,
}) => {
  return (
    <div className="bottom-nav-dock select-none">
      <div className="bottom-nav-container">
        {/* Tab 1: Home */}
        <button
          type="button"
          onClick={() => onSelectTab('home')}
          className={`nav-dock-btn ${activeTab === 'home' ? 'active' : ''}`}
          title="Home"
        >
          <Home size={20} strokeWidth={activeTab === 'home' ? 2.5 : 1.8} />
          <span className="nav-dock-label">Home</span>
        </button>

        {/* Tab 2: Radar / Transceiver */}
        <button
          type="button"
          onClick={() => onSelectTab('transceiver')}
          className={`nav-dock-btn ${activeTab === 'transceiver' ? 'active' : ''}`}
          title="Radio Transceiver"
        >
          <Radio size={20} strokeWidth={activeTab === 'transceiver' ? 2.5 : 1.8} />
          <span className="nav-dock-label">Radar</span>
        </button>

        {/* Tab 3: Center Elevated Black PTT Button */}
        <div className="center-ptt-slot">
          <button
            type="button"
            onClick={() => {
              if (activeTab !== 'transceiver') {
                onSelectTab('transceiver');
              } else if (onPttPress) {
                onPttPress();
              }
            }}
            className={`center-ptt-btn ${isPttActive ? 'is-transmitting' : ''}`}
            title="Push-To-Talk (Walkie-Talkie)"
          >
            <div className="ptt-waves-icon">
              <Mic size={20} color="#ffffff" strokeWidth={2.4} />
            </div>
          </button>
        </div>

        {/* Tab 4: Neural Models */}
        <button
          type="button"
          onClick={() => onSelectTab('models')}
          className={`nav-dock-btn ${activeTab === 'models' ? 'active' : ''}`}
          title="Neural Models"
        >
          <Download size={20} strokeWidth={activeTab === 'models' ? 2.5 : 1.8} />
          <span className="nav-dock-label">Downloads</span>
        </button>

        {/* Tab 5: Architecture */}
        <button
          type="button"
          onClick={() => onSelectTab('architecture')}
          className={`nav-dock-btn ${activeTab === 'architecture' ? 'active' : ''}`}
          title="System Architecture"
        >
          <Cpu size={20} strokeWidth={activeTab === 'architecture' ? 2.5 : 1.8} />
          <span className="nav-dock-label">Specs</span>
        </button>
      </div>
    </div>
  );
};
