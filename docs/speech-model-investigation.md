# Local speech model investigation

No AI4Bharat weights or labeled recordings were present in this repository. The existing Android recognizer and installed system voices are preserved; the backend's hint-based STT and harmonic WAV generator are not treated as model baselines. No model-quality benchmark or model selection based on synthetic values is claimed.

## Candidates and feasibility

| Candidate | Primary source | Assessment for this project |
|---|---|---|
| Android on-device recognizer / installed offline TTS | [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer) | Existing phone implementation; availability and language installation queried locally. Provider model size/RAM are not exposed. Test microphone PCM injection separately from model availability. |
| AI4Bharat IndicConformer | [Official repository](https://github.com/AI4Bharat/IndicConformerASR), [600M model](https://huggingface.co/ai4bharat/indic-conformer-600m-multilingual) | Official repository lists 22 Indian languages, monolingual checkpoints and a 600M multilingual model. Requires an actual export/runtime/weights integration and measured accuracy on target phones; the checked-in README alone is not integration. English/Hinglish must be assessed with a labeled corpus rather than inferred from Indic language coverage. |
| Whisper / whisper.cpp | [Official implementation](https://github.com/ggml-org/whisper.cpp) | Native C/C++ Android support and quantization make this a practical comparison candidate. Measure multilingual small/base checkpoints and quantized equivalents before selecting. No weights downloaded by this task. |
| faster-whisper | [Official implementation](https://github.com/SYSTRAN/faster-whisper) | CTranslate2 CPU/GPU inference with quantization; useful desktop reference benchmark. Python/CTranslate2 availability on desktop does not establish deployability in the Android application. |
| AI4Bharat IndicF5 | [Official model card](https://huggingface.co/ai4bharat/IndicF5) | Reference-conditioned TTS covering 11 Indian languages; prescribed runtime uses Python/PyTorch. Reference audio/text, weights, runtime conversion and device profiling are needed before embedding. Do not substitute a web inference service. |
| AI4Bharat Indic Parler-TTS | [Official model card](https://huggingface.co/ai4bharat/indic-parler-tts) | Prompt-conditioned multilingual speech; model card documents 21 languages. Evaluate local runtime, memory, decoder latency and pronunciation. Desktop generation examples are not Android benchmarks. |

## Required experiment

Use consented, locally stored, transcribed recordings for Hindi, English, Hinglish, plus Bengali, Tamil and Marathi. Record device/OS, checkpoint revision and checksum, runtime, thread count, quantization, sample rate, cold/warm initialization, latency, peak RSS/PSS, CPU time, thermal state and model file bytes. Separate code-switching written in Devanagari from Romanized Hinglish. Score corpus-level WER/CER; document normalization. Include quiet/noisy speech and different speakers, never train on the evaluation split.

Compare FP32/FP16/INT8 on the same utterances, measuring accuracy changes as well as memory and latency. ONNX conversion requires parity checks for preprocessing/tokenization and decoder output. Quantization or distillation is an experiment, not an automatic quality-neutral optimization. Evaluate TTS with native listeners using blinded naturalness/pronunciation ratings and record time to first audio, real-time factor, failures and peak memory. No naturalness value can be inferred from WAV validity.

`scripts/score-speech-benchmark.py` aggregates supplied observations to CSV/JSON without network calls. Example input fields: `model`, `language`, `device`, `reference`, `hypothesis`, `latencyMs`, `ramBytes`, `modelBytes`, `cpuMs`, `audioMs`, `offlineVerified`, optional `naturalnessScore`. Missing measurements remain null. The utility scores results; it does not masquerade as an inference engine.

## Readiness and preparation

ModelManager inventories locally reported STT languages and installed non-network TTS voices. OFFLINE_READY means required local providers report installed assets; it is not an accuracy or injected-PCM compatibility certification. Android 12 can still send typed acoustic messages, but the shared recognition input path requires Android 13+ and provider support for [EXTRA_AUDIO_SOURCE](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE). Unsupported capture fails visibly; no cloud recognizer factory is used. Model download is an explicit preparation command and is refused while an acoustic session is connected. System-managed model loading/unloading is owned by the recognition/TTS provider; pause/close cancels input and releases the app's provider bindings.
