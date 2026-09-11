import React, { useEffect, useRef } from 'react';

interface WaveformVisualizerProps {
  stream: MediaStream | null;
  isRecording: boolean;
  className?: string;
}

export const WaveformVisualizer: React.FC<WaveformVisualizerProps> = ({
  stream,
  isRecording,
  className = ''
}) => {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const animationFrameRef = useRef<number | null>(null);
  const audioContextRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const sourceRef = useRef<MediaStreamAudioSourceNode | null>(null);

  useEffect(() => {
    if (!isRecording || !stream) {
      if (animationFrameRef.current) {
        cancelAnimationFrame(animationFrameRef.current);
      }
      if (audioContextRef.current && audioContextRef.current.state !== 'closed') {
        try {
          audioContextRef.current.close();
        } catch (e) {
          console.warn('AudioContext close error:', e);
        }
      }
      return;
    }

    try {
      const AudioCtx = window.AudioContext || (window as any).webkitAudioContext;
      const audioCtx = new AudioCtx();
      audioContextRef.current = audioCtx;

      const analyser = audioCtx.createAnalyser();
      analyser.fftSize = 64;
      analyser.smoothingTimeConstant = 0.8;
      analyserRef.current = analyser;

      const source = audioCtx.createMediaStreamSource(stream);
      source.connect(analyser);
      sourceRef.current = source;

      const canvas = canvasRef.current;
      if (!canvas) return;
      const ctx = canvas.getContext('2d');
      if (!ctx) return;

      const bufferLength = analyser.frequencyBinCount;
      const dataArray = new Uint8Array(bufferLength);

      const draw = () => {
        if (!isRecording) return;
        animationFrameRef.current = requestAnimationFrame(draw);

        analyser.getByteFrequencyData(dataArray);

        const width = canvas.width;
        const height = canvas.height;

        ctx.clearRect(0, 0, width, height);

        const numBars = 16;
        const barWidth = (width / numBars) - 2;
        let x = 1;

        for (let i = 0; i < numBars; i++) {
          const dataIndex = Math.floor((i / numBars) * bufferLength);
          const rawVal = dataArray[dataIndex] || 0;
          // Scale bar height dynamically between 15% and 95%
          const barHeight = Math.max(6, (rawVal / 255) * (height - 4));

          // Gradient color: coral rose -> glowing violet
          const grad = ctx.createLinearGradient(0, height - barHeight, 0, height);
          grad.addColorStop(0, '#f43f5e'); // rose-500
          grad.addColorStop(1, '#8b5cf6'); // violet-500

          ctx.fillStyle = grad;
          ctx.beginPath();
          ctx.roundRect(x, height - barHeight, barWidth, barHeight, [3, 3, 0, 0]);
          ctx.fill();

          x += barWidth + 3;
        }
      };

      draw();
    } catch (err) {
      console.warn('Web Audio Waveform initialization warning:', err);
    }

    return () => {
      if (animationFrameRef.current) {
        cancelAnimationFrame(animationFrameRef.current);
      }
      if (audioContextRef.current && audioContextRef.current.state !== 'closed') {
        try {
          audioContextRef.current.close();
        } catch (e) {
          console.warn('AudioContext cleanup error:', e);
        }
      }
    };
  }, [stream, isRecording]);

  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center' }} className={className}>
      <canvas
        ref={canvasRef}
        width={160}
        height={36}
        style={{ width: '160px', height: '36px', borderRadius: '8px' }}
      />
    </div>
  );
};
