import React, { useEffect, useRef } from 'react';

interface WaveformVisualizerProps {
  stream: MediaStream | null;
  analyser?: AnalyserNode | null;
  isRecording: boolean;
  className?: string;
}

export const WaveformVisualizer: React.FC<WaveformVisualizerProps> = ({
  stream,
  analyser: externalAnalyser,
  isRecording,
  className = ''
}) => {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const animationFrameRef = useRef<number | null>(null);
  const internalAudioCtxRef = useRef<AudioContext | null>(null);

  useEffect(() => {
    if (!isRecording || (!stream && !externalAnalyser)) {
      if (animationFrameRef.current) {
        cancelAnimationFrame(animationFrameRef.current);
        animationFrameRef.current = null;
      }
      if (internalAudioCtxRef.current && internalAudioCtxRef.current.state !== 'closed') {
        try {
          internalAudioCtxRef.current.close();
        } catch {
          // ignore
        }
        internalAudioCtxRef.current = null;
      }
      return;
    }

    let activeAnalyser: AnalyserNode | null = externalAnalyser || null;

    if (!activeAnalyser && stream) {
      try {
        const AudioCtx = window.AudioContext || (window as any).webkitAudioContext;
        if (AudioCtx) {
          const audioCtx = new AudioCtx();
          internalAudioCtxRef.current = audioCtx;
          if (audioCtx.state === 'suspended') {
            audioCtx.resume().catch(() => {});
          }

          const analyserNode = audioCtx.createAnalyser();
          analyserNode.fftSize = 64;
          analyserNode.smoothingTimeConstant = 0.7;

          const source = audioCtx.createMediaStreamSource(stream);
          source.connect(analyserNode);
          activeAnalyser = analyserNode;
        }
      } catch (err) {
        console.warn('Waveform audio context error:', err);
      }
    }

    if (!activeAnalyser) return;

    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const bufferLength = activeAnalyser.frequencyBinCount;
    const dataArray = new Uint8Array(bufferLength);

    const draw = () => {
      if (!isRecording) return;
      animationFrameRef.current = requestAnimationFrame(draw);

      if (activeAnalyser) {
        activeAnalyser.getByteFrequencyData(dataArray);
      }

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
        const barHeight = Math.max(5, (rawVal / 255) * (height - 4));

        // Gradient color: coral rose -> glowing violet
        const grad = ctx.createLinearGradient(0, height - barHeight, 0, height);
        grad.addColorStop(0, '#f43f5e');
        grad.addColorStop(1, '#8b5cf6');

        ctx.fillStyle = grad;
        ctx.beginPath();
        ctx.roundRect(x, height - barHeight, barWidth, barHeight, [3, 3, 0, 0]);
        ctx.fill();

        x += barWidth + 3;
      }
    };

    draw();

    return () => {
      if (animationFrameRef.current) {
        cancelAnimationFrame(animationFrameRef.current);
        animationFrameRef.current = null;
      }
      if (internalAudioCtxRef.current && internalAudioCtxRef.current.state !== 'closed') {
        try {
          internalAudioCtxRef.current.close();
        } catch {
          // ignore
        }
        internalAudioCtxRef.current = null;
      }
    };
  }, [isRecording, stream, externalAnalyser]);

  return (
    <canvas
      ref={canvasRef}
      width={120}
      height={32}
      className={`waveform-canvas ${className}`}
      style={{ display: 'block', verticalAlign: 'middle' }}
    />
  );
};
