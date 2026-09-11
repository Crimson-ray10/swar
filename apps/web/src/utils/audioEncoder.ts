/**
 * Encode an AudioBuffer into standard 16 kHz Mono 16-bit PCM WAV.
 * Fully compatible with Python soundfile (sf.read).
 */
export async function convertTo16kHzMonoWav(audioBlob: Blob): Promise<Blob> {
  const arrayBuffer = await audioBlob.arrayBuffer();
  const AudioCtx = window.AudioContext || (window as any).webkitAudioContext;
  const audioCtx = new AudioCtx();

  try {
    const decodedBuffer = await audioCtx.decodeAudioData(arrayBuffer);

    const targetSampleRate = 16000;
    // Downmix channels to mono by rendering to 1-channel OfflineAudioContext
    const length = Math.round(decodedBuffer.duration * targetSampleRate);

    // Create an offline audio context to resample to 16 kHz mono
    const offlineCtx = new OfflineAudioContext(1, length, targetSampleRate);
    const source = offlineCtx.createBufferSource();
    source.buffer = decodedBuffer;
    source.connect(offlineCtx.destination);
    source.start(0);

    const renderedBuffer = await offlineCtx.startRendering();
    const channelData = renderedBuffer.getChannelData(0);

    // Build 44-byte standard RIFF/WAVE header
    const numSamples = channelData.length;
    const buffer = new ArrayBuffer(44 + numSamples * 2);
    const view = new DataView(buffer);

    const writeString = (offset: number, str: string) => {
      for (let i = 0; i < str.length; i++) {
        view.setUint8(offset + i, str.charCodeAt(i));
      }
    };

    writeString(0, 'RIFF');
    view.setUint32(4, 36 + numSamples * 2, true);
    writeString(8, 'WAVE');
    writeString(12, 'fmt ');
    view.setUint32(16, 16, true); // Subchunk1Size (16 for PCM)
    view.setUint16(20, 1, true); // AudioFormat (1 for PCM)
    view.setUint16(22, 1, true); // NumChannels (1 = mono)
    view.setUint32(24, targetSampleRate, true); // SampleRate (16000)
    view.setUint32(28, targetSampleRate * 2, true); // ByteRate (16000 * 1 * 2)
    view.setUint16(32, 2, true); // BlockAlign (1 * 2)
    view.setUint16(34, 16, true); // BitsPerSample (16-bit)
    writeString(36, 'data');
    view.setUint32(40, numSamples * 2, true); // Subchunk2Size

    // Write PCM 16-bit samples with clipping protection
    let offset = 44;
    for (let i = 0; i < numSamples; i++) {
      const s = Math.max(-1, Math.min(1, channelData[i]));
      view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7fff, true);
      offset += 2;
    }

    return new Blob([buffer], { type: 'audio/wav' });
  } catch (err) {
    console.warn('WAV conversion fallback; returning original blob:', err);
    return audioBlob;
  } finally {
    if (audioCtx.state !== 'closed') {
      try {
        await audioCtx.close();
      } catch (e) {
        // ignore
      }
    }
  }
}
