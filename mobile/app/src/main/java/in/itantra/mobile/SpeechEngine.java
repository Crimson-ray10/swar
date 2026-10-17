package in.itantra.mobile;

/**
 * ============================================================================
 * LinC 100% On-Device Neural Voice Pipeline
 * ============================================================================
 *
 * NOTE: The production implementation of SpeechEngine has been compiled from
 * {@link in.itantra.mobile.SpeechEngine SpeechEngine.kt} directly into DEX.
 *
 * Key Architectural Highlights:
 * 1. ZERO Google Speech Services:
 *    - Completely eliminated android.speech.SpeechRecognizer and android.speech.tts.TextToSpeech.
 * 2. STT:
 *    - Powered by AI4Bharat IndicConformer (80-dimensional log-mel filterbanks,
 *      CTC greedy search decoding via Sherpa-ONNX native JNI libsherpa-onnx-jni.so).
 * 3. TTS:
 *    - Powered by AI4Bharat Indic-TTS (FastPitch mel spectrogram generation +
 *      HiFi-GAN vocoder) via ONNX Runtime, with low-latency synthetic waveform fallback.
 * 4. Zero-Drop Continuous VAD (Fix for Slide-to-Lock Idle Bug):
 *    - In hands-free 'locked' and 'conversation' modes, silence detection dispatches
 *      completed speech chunks asynchronously to a background worker pool for CTC decode.
 *    - AudioRecord remains continuously active and never transitions to Idle or drops audio frames.
 * 5. Dynamic Storage Optimization:
 *    - Hindi (hi-IN) and Indian English (en-IN) are pre-bundled on device.
 *    - Regional languages (Marathi, Gujarati, Tamil, Telugu, Kannada, Bengali)
 *      download on-demand from Hugging Face Hub (helo-ayush/itantra-models) and remain
 *      100% offline once downloaded.
 *
 * See {@code SpeechEngine.kt} in this package for the complete Kotlin implementation.
 */
final class SpeechEngineReference {
    private SpeechEngineReference() {}
}
