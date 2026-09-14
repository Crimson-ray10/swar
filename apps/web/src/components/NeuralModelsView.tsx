import React from 'react';
import { HardDrive, CheckCircle2, Cpu, Mic, Volume2, Globe } from 'lucide-react';

export const NeuralModelsView: React.FC = () => {
  const modelPacks = [
    {
      name: 'Voice Activity Detector',
      detail: '2MB • Silero_VAD_v5.onnx',
      sub: 'Adaptive 20ms energy & zero-crossing filter',
      icon: Mic,
      status: 'ACTIVE',
    },
    {
      name: 'Speech-to-Text Engine',
      detail: '58 MB • IndicConformer_Multilingual_IndiCon',
      sub: '16 kHz mono acoustic offline neural model',
      icon: Cpu,
      status: 'ACTIVE',
    },
    {
      name: 'Language Auto-Detector',
      detail: '15.8 MB • FastText IndicLang 10',
      sub: 'Zero-latency script and phoneme classifier',
      icon: Globe,
      status: 'ACTIVE',
    },
    {
      name: 'Neural Speech Synthesizer',
      detail: '32 MB • Piper / IndicF5 Acoustic Engine',
      sub: '24 kHz neural acoustic wav generator',
      icon: Volume2,
      status: 'ACTIVE',
    },
    {
      name: 'Hindi Voice Pack',
      detail: '8.2 MB • Devanagari Script NFC',
      sub: 'Sample: मुझे तुरंत मदद चाहिए, यहाँ आग लगी है।',
      icon: Globe,
      status: 'ACTIVE',
    },
    {
      name: 'Tamil Voice Pack',
      detail: '7.8 MB • Tamil Script NFC',
      sub: 'Sample: எனக்கு உடனடி உதவி தேவை, இங்கே அவசரநிலை உள்ளது.',
      icon: Globe,
      status: 'ACTIVE',
    },
    {
      name: 'Telugu Voice Pack',
      detail: '7.9 MB • Telugu Script NFC',
      sub: 'Sample: నాకు తక్షణ సహాయం కావాలి, ఇక్కడ అగ్ని ప్రమాదం జరిగింది.',
      icon: Globe,
      status: 'ACTIVE',
    },
    {
      name: 'Marathi Voice Pack',
      detail: '8.0 MB • Devanagari Script NFC',
      sub: 'Sample: मला तातडीने मदतीची गरज आहे, येथे आग लागली आहे.',
      icon: Globe,
      status: 'ACTIVE',
    },
    {
      name: 'Bengali Voice Pack',
      detail: '8.1 MB • Bengali Script NFC',
      sub: 'Sample: আমার অবিলম্বে সাহায্য দরকার, এখানে আগুন লেগেছে।',
      icon: Globe,
      status: 'ACTIVE',
    },
    {
      name: 'English (India) Voice Pack',
      detail: '6.5 MB • Standard Latin Script',
      sub: 'Sample: I need immediate assistance, there is an emergency here.',
      icon: Globe,
      status: 'ACTIVE',
    },
  ];

  return (
    <div className="view-screen-container models-view-container animate-fade-in">
      {/* Screen Header matching Mockup */}
      <div className="screen-top-bar">
        <div>
          <h2 className="transceiver-title">Neural Models</h2>
          <p className="text-[11px] font-mono text-neutral-400">
            16.48 GB Free • 256MB Core RAM
          </p>
        </div>

        <div className="flex items-center gap-1.5 px-3 py-1 rounded-full bg-neutral-100 border border-neutral-200">
          <HardDrive size={13} className="text-neutral-700" />
          <span className="text-[11px] font-mono text-neutral-700 font-semibold">Offline Storage</span>
        </div>
      </div>

      {/* Main Card: Download The Pack */}
      <div className="download-pack-card mb-6">
        <div className="flex items-start justify-between gap-3 mb-2.5">
          <div className="flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-full bg-black text-white flex items-center justify-center">
              <CheckCircle2 size={18} className="text-emerald-400" />
            </div>
            <div>
              <h3 className="text-sm font-bold text-neutral-900">Download The Pack</h3>
              <p className="text-[11px] text-neutral-400">All 10 Language Models Included</p>
            </div>
          </div>

          <span className="installed-badge">
            INSTALLED
          </span>
        </div>

        <p className="text-xs text-neutral-600 leading-relaxed mb-3">
          Installs complete bundle: Silero VAD (2.0MB), AMB IndicConformer STT (58 MB), FastText Language Auto-detector (15.8 MB), and all 10 Indic Voice Packs (Hindi, Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu, Odia, Bengali, English) required for auto-detection and speech synthesis.
        </p>

        <div className="p-2.5 rounded-xl bg-neutral-50 border border-neutral-200 text-center">
          <span className="text-xs font-semibold text-neutral-900 flex items-center justify-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full bg-emerald-500"></span>
            <span>All Language Models and neural transceiver are fully active</span>
          </span>
        </div>
      </div>

      {/* Compulsory Packs Section */}
      <div className="mb-6">
        <div className="flex items-center justify-between mb-3 px-1">
          <span className="text-xs font-bold text-neutral-900 uppercase tracking-wider">
            Compulsory Language &amp; Transceiver Packs ({modelPacks.length})
          </span>
          <span className="text-[11px] font-mono text-neutral-400">On-Device RAM</span>
        </div>

        <div className="flex flex-col gap-2">
          {modelPacks.map((pack, idx) => {
            const Icon = pack.icon;
            return (
              <div key={idx} className="model-pack-row">
                <div className="flex items-start gap-3">
                  <div className="w-8 h-8 rounded-lg bg-neutral-100 flex items-center justify-center text-neutral-700 flex-shrink-0 mt-0.5">
                    <Icon size={16} />
                  </div>
                  <div>
                    <div className="text-xs font-bold text-neutral-900">{pack.name}</div>
                    <div className="text-[11px] font-mono text-neutral-500 font-semibold">{pack.detail}</div>
                    <div className="text-[10px] text-neutral-400 mt-0.5">{pack.sub}</div>
                  </div>
                </div>

                <div className="flex items-center gap-1 text-emerald-600 flex-shrink-0">
                  <CheckCircle2 size={16} />
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
};
