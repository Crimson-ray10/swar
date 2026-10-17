/**
 * Voice Bridge AudioCapture & RingBuffer
 * Receives 16 kHz mono 16-bit PCM frames (10-30 ms frames) and feeds them to VAD & STT.
 */
export class AudioCapture {
  private sampleRate: number;
  private frameSizeSamples: number; // e.g. 320 samples = 20ms at 16kHz
  private ringBuffer: Int16Array;
  private writePos: number = 0;
  private totalSamplesRecorded: number = 0;

  constructor(sampleRate: number = 16000, frameDurationMs: number = 20, ringBufferCapacityMs: number = 2000) {
    this.sampleRate = sampleRate;
    this.frameSizeSamples = Math.floor((sampleRate * frameDurationMs) / 1000);
    const capacitySamples = Math.floor((sampleRate * ringBufferCapacityMs) / 1000);
    this.ringBuffer = new Int16Array(capacitySamples);
  }

  public getSampleRate(): number {
    return this.sampleRate;
  }

  public getFrameSizeSamples(): number {
    return this.frameSizeSamples;
  }

  /**
   * Push incoming PCM buffer (Int16Array or Buffer with 16-bit signed LE samples)
   */
  public pushChunk(chunk: Buffer | Int16Array): Int16Array[] {
    let samples: Int16Array;
    if (Buffer.isBuffer(chunk)) {
      const numSamples = Math.floor(chunk.length / 2);
      samples = new Int16Array(numSamples);
      for (let i = 0; i < numSamples; i++) {
        samples[i] = chunk.readInt16LE(i * 2);
      }
    } else {
      samples = chunk;
    }

    const frames: Int16Array[] = [];
    const cap = this.ringBuffer.length;

    for (let i = 0; i < samples.length; i++) {
      this.ringBuffer[this.writePos] = samples[i];
      this.writePos = (this.writePos + 1) % cap;
      this.totalSamplesRecorded++;

      if (this.totalSamplesRecorded % this.frameSizeSamples === 0) {
        // Extract 1 complete frame
        const frame = new Int16Array(this.frameSizeSamples);
        const startPos = (this.writePos - this.frameSizeSamples + cap) % cap;
        for (let j = 0; j < this.frameSizeSamples; j++) {
          frame[j] = this.ringBuffer[(startPos + j) % cap];
        }
        frames.push(frame);
      }
    }

    return frames;
  }

  public reset(): void {
    this.ringBuffer.fill(0);
    this.writePos = 0;
    this.totalSamplesRecorded = 0;
  }
}
