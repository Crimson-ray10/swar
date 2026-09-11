import React, { useState, useRef, useEffect, useCallback } from 'react';
import { Mic, Square, Loader2, Volume2, AlertCircle, Sparkles, Zap, Radio, RefreshCw } from 'lucide-react';
import { WaveformVisualizer } from './WaveformVisualizer';
import { convertTo16kHzMonoWav } from '../utils/audioEncoder';

export type MicState = 'Idle' | 'Listening' | 'Processing' | 'Generating' | 'Complete' | 'Error';

interface MicButtonProps {
  state: MicState;
  selectedLanguage: string;
  onAudioRecorded: (blob: Blob, liveTranscript?: string) => void;
  onStateChange: (newState: MicState) => void;
  onLiveTranscriptChange?: (text: string) => void;
}

export const MicButton: React.FC<MicButtonProps> = ({
  state,
  selectedLanguage,
  onAudioRecorded,
  onStateChange,
  onLiveTranscriptChange
}) => {
  const [activeStream, setActiveStream] = useState<MediaStream | null>(null);
  const [liveTranscript, setLiveTranscript] = useState<string>('');
  const [vadEnabled, setVadEnabled] = useState<boolean>(true);
  const [isVoiceActive, setIsVoiceActive] = useState<boolean>(false);
  const [silenceCountdown, setSilenceCountdown] = useState<number | null>(null);

  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const liveTranscriptRef = useRef<string>('');
  const recognitionRef = useRef<any>(null);
  const audioChunks = useRef<Blob[]>([]);
  const vadIntervalRef = useRef<number | null>(null);
  const audioContextRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const hasSpokenRef = useRef<boolean>(false);
  const lastSpeechTimeRef = useRef<number>(Date.now());
  const recordingStartTimeRef = useRef<number>(Date.now());
  const noiseFloorRef = useRef<number>(16);

  const stopVadMonitoring = useCallback(() => {
    if (vadIntervalRef.current) {
      window.clearInterval(vadIntervalRef.current);
      vadIntervalRef.current = null;
    }
    setIsVoiceActive(false);
    setSilenceCountdown(null);
  }, []);

  const cleanupAudio = useCallback(() => {
    stopVadMonitoring();

    if (recognitionRef.current) {
      try {
        recognitionRef.current.stop();
      } catch (e) {
        // ignore
      }
      recognitionRef.current = null;
    }

    if (streamRef.current) {
      streamRef.current.getTracks().forEach((track) => {
        try {
          track.stop();
        } catch (e) {
          // ignore
        }
      });
      streamRef.current = null;
      setActiveStream(null);
    }

    if (audioContextRef.current && audioContextRef.current.state !== 'closed') {
      try {
        audioContextRef.current.close();
      } catch (e) {
        // ignore
      }
      audioContextRef.current = null;
    }
  }, [stopVadMonitoring]);

  useEffect(() => {
    return () => {
      cleanupAudio();
    };
  }, [cleanupAudio]);

  const stopRecording = useCallback(() => {
    stopVadMonitoring();
    onStateChange('Processing');

    if (recognitionRef.current) {
      try {
        recognitionRef.current.stop();
      } catch (e) {
        console.warn(e);
      }
    }

    if (mediaRecorderRef.current && mediaRecorderRef.current.state !== 'inactive') {
      try {
        mediaRecorderRef.current.stop();
      } catch (e) {
        console.warn('Error stopping media recorder:', e);
      }
    }
  }, [stopVadMonitoring, onStateChange]);

  const startVadMonitoring = useCallback((stream: MediaStream) => {
    try {
      const AudioCtx = window.AudioContext || (window as any).webkitAudioContext;
      if (!AudioCtx) return;

      const audioCtx = new AudioCtx();
      audioContextRef.current = audioCtx;

      const analyser = audioCtx.createAnalyser();
      analyser.fftSize = 256;
      analyser.smoothingTimeConstant = 0.4;
      analyserRef.current = analyser;

      const source = audioCtx.createMediaStreamSource(stream);
      source.connect(analyser);

      const bufferLength = analyser.frequencyBinCount;
      const dataArray = new Uint8Array(bufferLength);

      hasSpokenRef.current = false;
      lastSpeechTimeRef.current = Date.now();
      recordingStartTimeRef.current = Date.now();
      noiseFloorRef.current = 16;

      const SILENCE_TIMEOUT_MS = 1400; // 1.4s of silence after speech -> auto transmit
      const INITIAL_MAX_SILENCE_MS = 8000; // 8s initial timeout

      // Human speech frequency bandpass: 300Hz to 3400Hz
      const sampleRate = audioCtx.sampleRate || 48000;
      const binHz = (sampleRate / 2) / bufferLength;
      const startBin = Math.max(1, Math.floor(300 / binHz));
      const endBin = Math.min(bufferLength - 1, Math.ceil(3400 / binHz));
      const numVocalBins = endBin - startBin + 1;

      vadIntervalRef.current = window.setInterval(() => {
        if (!analyserRef.current) return;

        analyserRef.current.getByteFrequencyData(dataArray);

        let vocalSum = 0;
        for (let i = startBin; i <= endBin; i++) {
          vocalSum += dataArray[i];
        }
        const avgVocalEnergy = vocalSum / numVocalBins;
        const now = Date.now();

        // Dynamically adjust noise floor baseline
        if (avgVocalEnergy < noiseFloorRef.current + 8) {
          noiseFloorRef.current = 0.92 * noiseFloorRef.current + 0.08 * avgVocalEnergy;
        }

        const dynamicSpeechThreshold = Math.max(24, noiseFloorRef.current + 12);
        const isCurrentlySpeaking = avgVocalEnergy > dynamicSpeechThreshold;

        if (isCurrentlySpeaking) {
          hasSpokenRef.current = true;
          lastSpeechTimeRef.current = now;
          setIsVoiceActive(true);
          setSilenceCountdown(null);
        } else {
          setIsVoiceActive(false);

          if (!vadEnabled) return;

          if (hasSpokenRef.current) {
            const silentDuration = now - lastSpeechTimeRef.current;
            const remainingMs = SILENCE_TIMEOUT_MS - silentDuration;

            if (remainingMs > 0 && remainingMs <= 1100) {
              setSilenceCountdown(parseFloat((remainingMs / 1000).toFixed(1)));
            } else if (silentDuration >= SILENCE_TIMEOUT_MS) {
              stopVadMonitoring();
              stopRecording();
            }
          } else {
            if (now - recordingStartTimeRef.current >= INITIAL_MAX_SILENCE_MS) {
              stopVadMonitoring();
              stopRecording();
            }
          }
        }
      }, 100);
    } catch (err) {
      console.warn('VAD monitoring initialization warning:', err);
    }
  }, [vadEnabled, stopRecording, stopVadMonitoring]);

  const startRecording = async () => {
    cleanupAudio();

    try {
      setLiveTranscript('');
      liveTranscriptRef.current = '';
      if (onLiveTranscriptChange) onLiveTranscriptChange('');

      // Browser Web Speech recognition for interim live preview
      const SpeechRecognition = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
      if (SpeechRecognition) {
        try {
          const recognition = new SpeechRecognition();
          recognition.continuous = true;
          recognition.interimResults = true;
          // Set language code based on selectedLanguage
          const langMap: Record<string, string> = {
            hi: 'hi-IN',
            ta: 'ta-IN',
            te: 'te-IN',
            mr: 'mr-IN',
            bn: 'bn-IN',
            kn: 'kn-IN',
            en: 'en-IN'
          };
          recognition.lang = langMap[selectedLanguage] || 'hi-IN';

          recognition.onresult = (event: any) => {
            let rawTranscript = '';
            for (let i = 0; i < event.results.length; i++) {
              rawTranscript += event.results[i][0].transcript;
            }
            setLiveTranscript(rawTranscript);
            liveTranscriptRef.current = rawTranscript;

            if (rawTranscript.trim().length > 0) {
              hasSpokenRef.current = true;
              lastSpeechTimeRef.current = Date.now();
            }

            if (onLiveTranscriptChange) {
              onLiveTranscriptChange(rawTranscript);
            }
          };

          recognition.onerror = (err: any) => {
            console.warn('Speech recognition notice:', err.error || err);
          };

          recognition.start();
          recognitionRef.current = recognition;
        } catch (speechErr) {
          console.warn('Web Speech API fallback:', speechErr);
        }
      }

      let stream: MediaStream;
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          audio: {
            echoCancellation: true,
            noiseSuppression: true,
            autoGainControl: true,
            channelCount: 1
          }
        });
      } catch (constraintErr) {
        stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      }

      streamRef.current = stream;
      setActiveStream(stream);

      const recorder = new MediaRecorder(stream);
      audioChunks.current = [];

      recorder.ondataavailable = (event) => {
        if (event.data && event.data.size > 0) {
          audioChunks.current.push(event.data);
        }
      };

      recorder.onstop = async () => {
        const rawBlob = new Blob(audioChunks.current, { type: recorder.mimeType || 'audio/webm' });
        const finalTranscript = liveTranscriptRef.current.trim();

        // Convert audio into standard 16 kHz Mono 16-bit PCM WAV for iTantra backend
        const wavBlob = await convertTo16kHzMonoWav(rawBlob);
        onAudioRecorded(wavBlob, finalTranscript);
        cleanupAudio();
      };

      recorder.start(100);
      mediaRecorderRef.current = recorder;
      onStateChange('Listening');

      startVadMonitoring(stream);
    } catch (err) {
      console.error('Microphone access error:', err);
      cleanupAudio();
      onStateChange('Error');
    }
  };

  // Push-to-Talk keyboard shortcut (Spacebar) matching July
  const spacebarDownRef = useRef(false);
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement;
      if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) {
        return;
      }

      if (e.code === 'Space' && !e.repeat && !spacebarDownRef.current) {
        if (state === 'Idle' || state === 'Complete') {
          e.preventDefault();
          spacebarDownRef.current = true;
          const studioEl = document.getElementById('studio');
          if (studioEl) {
            studioEl.scrollIntoView({ behavior: 'smooth', block: 'center' });
          }
          startRecording();
        } else if (state === 'Listening') {
          e.preventDefault();
          stopRecording();
        }
      }
    };

    const handleKeyUp = (e: KeyboardEvent) => {
      if (e.code === 'Space' && spacebarDownRef.current) {
        e.preventDefault();
        spacebarDownRef.current = false;
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    window.addEventListener('keyup', handleKeyUp);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
      window.removeEventListener('keyup', handleKeyUp);
    };
  }, [state, startRecording, stopRecording]);

  const handleClick = () => {
    if (state === 'Listening') {
      stopRecording();
    } else if (state === 'Idle' || state === 'Complete' || state === 'Error') {
      startRecording();
    }
  };

  const getButtonClass = () => {
    switch (state) {
      case 'Listening':
        return 'mic-listening';
      case 'Processing':
      case 'Generating':
        return 'mic-processing';
      case 'Error':
        return 'mic-error';
      default:
        return 'mic-idle';
    }
  };

  return (
    <div className="mic-container">
      {/* Central Interactive Mic Button (Exact July Dimensions: 112px round) */}
      <button
        onClick={handleClick}
        disabled={state === 'Processing' || state === 'Generating'}
        className={`mic-button-round ${getButtonClass()}`}
        aria-label="Microphone Query"
      >
        {state === 'Listening' ? (
          <Square size={40} fill="currentColor" />
        ) : state === 'Processing' || state === 'Generating' ? (
          <Loader2 size={48} className="animate-spin" />
        ) : state === 'Error' ? (
          <AlertCircle size={40} />
        ) : (
          <Mic size={44} />
        )}
      </button>

      {/* Live Audio Waveform Bars & VAD Status */}
      {state === 'Listening' && (
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '8px', marginTop: '16px' }}>
          <div className="waveform-pill-card">
            <Volume2 size={16} color={isVoiceActive ? '#34d399' : '#f43f5e'} className={isVoiceActive ? 'animate-pulse' : ''} />
            <WaveformVisualizer stream={activeStream} isRecording={state === 'Listening'} />
            <span style={{ fontSize: '12px', fontFamily: 'var(--font-mono)', minWidth: '96px' }}>
              {isVoiceActive ? (
                <span style={{ color: '#6ee7b7', display: 'flex', alignItems: 'center', gap: '6px' }}>
                  <span className="animate-ping" style={{ width: '8px', height: '8px', borderRadius: '50%', backgroundColor: '#10b981', display: 'inline-block' }} />
                  Speaking
                </span>
              ) : silenceCountdown !== null ? (
                <span style={{ color: '#fbbf24', display: 'flex', alignItems: 'center', gap: '5px', fontSize: '11px' }}>
                  <Radio size={12} color="#fbbf24" className="animate-pulse" />
                  Auto-send in {silenceCountdown}s...
                </span>
              ) : (
                <span style={{ color: '#fda4af' }}>Listening...</span>
              )}
            </span>
          </div>

          {/* VAD Auto-Stop Mode Pill */}
          <button
            onClick={() => setVadEnabled(!vadEnabled)}
            className={`vad-pill-btn ${!vadEnabled ? 'inactive' : ''}`}
            title="Toggle automatic silence detection"
          >
            <Zap size={11} color={vadEnabled ? '#10b981' : '#94a3b8'} />
            <span>Noise Filter & Auto-Stop: {vadEnabled ? 'ACTIVE' : 'OFF'}</span>
          </button>
        </div>
      )}

      {/* Real-time Speech Transcript Display Box */}
      {state === 'Listening' && (
        <div className="transcript-preview-box animate-fadeIn">
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '8px', fontSize: '11px', fontWeight: 600, color: '#f43f5e', textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: '8px' }}>
            <Sparkles size={14} className="animate-spin" />
            <span>Listening in Real Time</span>
          </div>
          <p style={{ fontSize: '14px', fontWeight: 500, color: '#f8fafc', minHeight: '1.5rem', lineHeight: 1.5 }}>
            {liveTranscript ? `"${liveTranscript}"` : <span style={{ color: '#64748b', fontStyle: 'italic' }}>Start speaking in selected language...</span>}
          </p>
        </div>
      )}

      {/* Status Label & Error Recovery */}
      <div className="status-instruction-text">
        {state === 'Listening' && (vadEnabled ? 'Speak naturally — will auto-transmit on silence' : 'Tap square button to stop & transmit')}
        {state === 'Processing' && 'Transmitting over low-bitrate simulated channel...'}
        {state === 'Generating' && 'Resynthesizing 24 kHz speech at receiver...'}
        {state === 'Idle' && 'Tap to speak & transmit over semantic radio'}
        {state === 'Complete' && 'Ready for next transmission'}
        {state === 'Error' && (
          <span
            onClick={startRecording}
            style={{ color: '#f59e0b', fontWeight: 600, display: 'inline-flex', alignItems: 'center', gap: '6px', cursor: 'pointer' }}
          >
            <RefreshCw size={13} />
            <span>Microphone access failed. Tap here to retry</span>
          </span>
        )}
      </div>
    </div>
  );
};
