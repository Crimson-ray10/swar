import React, { useEffect, useState } from 'react';

interface HeroProps {
  onGetStarted: () => void;
}

export const Hero: React.FC<HeroProps> = ({ onGetStarted }) => {
  // Count-up animation state for stats footer
  const [stats, setStats] = useState({
    wireRate: 0,
    fidelity: 0,
    compression: 0,
    stages: 0
  });

  useEffect(() => {
    const duration = 1500;
    const startTime = performance.now();

    const updateStats = (currentTime: number) => {
      const elapsed = currentTime - startTime;
      const progress = Math.min(elapsed / duration, 1);
      const easeOut = 1 - Math.pow(1 - progress, 3);

      setStats({
        wireRate: Math.round(2000 * easeOut),
        fidelity: parseFloat((99.2 * easeOut).toFixed(1)),
        compression: Math.round(256 * easeOut),
        stages: Math.round(8 * easeOut)
      });
    };

    let animationFrameId: number;
    const animate = (currentTime: number) => {
      updateStats(currentTime);
      if (currentTime - startTime < duration) {
        animationFrameId = requestAnimationFrame(animate);
      } else {
        setStats({
          wireRate: 2000,
          fidelity: 99.2,
          compression: 256,
          stages: 8
        });
      }
    };

    animationFrameId = requestAnimationFrame(animate);
    return () => cancelAnimationFrame(animationFrameId);
  }, []);

  return (
    <section id="hero" className="hero-section">
      <div className="hero-body">
        {/* Trust Row (Exact July Avatar Treatment with Indian Script Preview) */}
        <div className="trust-row">
          <div className="avatars">
            <div className="avatar-ring ring-1">
              <div className="avatar-inner">अ</div>
            </div>
            <div className="avatar-ring ring-2">
              <div className="avatar-inner">த</div>
            </div>
            <div className="avatar-ring ring-3">
              <div className="avatar-inner">14b</div>
            </div>
          </div>
          <div className="trust-pill">
            <span>6 Indian Languages • 0.5–2.0 kbps • Zero Hallucination</span>
          </div>
        </div>

        {/* Headline (Exact July Dot-Matrix Typography & Entrance Animation) */}
        <h1 className="headline">
          <span className="headline-line line-1">Meet iTantra</span>
        </h1>

        {/* Subhead */}
        <p className="subhead anim" style={{ '--d': '0.28s' } as React.CSSProperties}>
          iTantra is an Indian multilingual neural transceiver radio access platform engineered for real-time speech transcription, 14-bit semantic tokenization, channel impairment simulation, and 24 kHz synthetic voice resynthesis at sub-2 kbps wire budgets.
        </p>

        {/* Glowing CTA Button -> Smooth Scroll to #studio */}
        <div className="cta-wrapper anim-pulse" style={{ '--d': '0.4s' } as React.CSSProperties}>
          <button
            onClick={onGetStarted}
            className="cta-btn cursor-pointer"
          >
            Open Radio Studio
          </button>
        </div>
      </div>

      {/* Stats Footer (4 Metrics matching iTantra communication data) */}
      <footer className="stats-footer">
        <div className="stat-card anim" style={{ '--d': '0.5s' } as React.CSSProperties}>
          <div className="stat-icon">&lt;</div>
          <div className="stat-value">{stats.wireRate}<span className="stat-suffix">bps</span></div>
          <div className="stat-label">Wire Rate SLA</div>
        </div>

        <div className="stat-card anim" style={{ '--d': '0.58s' } as React.CSSProperties}>
          <div className="stat-icon">%</div>
          <div className="stat-value">{stats.fidelity}<span className="stat-suffix">%</span></div>
          <div className="stat-label">Word Recovery</div>
        </div>

        <div className="stat-card anim" style={{ '--d': '0.66s' } as React.CSSProperties}>
          <div className="stat-icon">*</div>
          <div className="stat-value">{stats.compression}<span className="stat-suffix">×</span></div>
          <div className="stat-label">vs 256k Raw PCM</div>
        </div>

        <div className="stat-card anim" style={{ '--d': '0.74s' } as React.CSSProperties}>
          <div className="stat-icon">#</div>
          <div className="stat-value">{stats.stages}</div>
          <div className="stat-label">Pipeline Stages</div>
        </div>
      </footer>
    </section>
  );
};
