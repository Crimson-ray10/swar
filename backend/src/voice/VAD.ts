import { EventEmitter } from 'node:events';

export interface VADConfig {
  sampleRate: number;
  energyThresholdMultiplier: number;
  silenceThresholdMs: number;
  minSpeechDurationMs: number;
}

/**
 * Voice Activity Detector (VAD)
 * Analyzes audio frames to detect speech onset and pause/silence boundaries.
 */
export class VAD extends EventEmitter {
  private config: VADConfig;
  private noiseFloorRms: number = 200;
  private isSpeechActive: boolean = false;
  private speechStartTime: number = 0;
  private lastSpeechTime: number = 0;
  private silenceTimer: NodeJS.Timeout | null = null;

  constructor(config?: Partial<VADConfig>) {
    super();
    this.config = {
      sampleRate: 16000,
      energyThresholdMultiplier: 2.5,
      silenceThresholdMs: 750,
      minSpeechDurationMs: 250,
      ...config,
    };
  }

  /**
   * Process a single audio frame (Int16Array)
   */
  public processFrame(frame: Int16Array): boolean {
    let sumSq = 0;
    let zeroCrossings = 0;

    for (let i = 0; i < frame.length; i++) {
      const sample = frame[i];
      sumSq += sample * sample;
      if (i > 0 && ((frame[i - 1] >= 0 && sample < 0) || (frame[i - 1] < 0 && sample >= 0))) {
        zeroCrossings++;
      }
    }

    const rms = Math.sqrt(sumSq / frame.length);
    const threshold = Math.max(350, this.noiseFloorRms * this.config.energyThresholdMultiplier);
    const isVoiced = rms > threshold;

    const now = Date.now();

    if (isVoiced) {
      this.lastSpeechTime = now;
      if (!this.isSpeechActive) {
        this.isSpeechActive = true;
        this.speechStartTime = now;
        this.emit('speechStart', { rms, timestamp: now });
      }
      this.emit('voicedFrame', { rms, frame });
    } else {
      // Slowly adapt noise floor during unvoiced segments
      this.noiseFloorRms = this.noiseFloorRms * 0.98 + rms * 0.02;

      if (this.isSpeechActive) {
        const silenceDuration = now - this.lastSpeechTime;
        const totalSpeechDuration = this.lastSpeechTime - this.speechStartTime;

        if (silenceDuration >= this.config.silenceThresholdMs) {
          this.isSpeechActive = false;
          if (totalSpeechDuration >= this.config.minSpeechDurationMs) {
            this.emit('speechEnd', {
              durationMs: totalSpeechDuration,
              silenceMs: silenceDuration,
              timestamp: now,
            });
          }
        }
      }
    }

    return this.isSpeechActive;
  }

  public getIsSpeechActive(): boolean {
    return this.isSpeechActive;
  }

  public reset(): void {
    this.isSpeechActive = false;
    this.speechStartTime = 0;
    this.lastSpeechTime = 0;
    if (this.silenceTimer) {
      clearTimeout(this.silenceTimer);
      this.silenceTimer = null;
    }
  }
}
