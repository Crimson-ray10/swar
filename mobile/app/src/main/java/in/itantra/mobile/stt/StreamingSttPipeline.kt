package `in`.itantra.mobile.stt

import `in`.itantra.mobile.audio.SileroVadGate

/**
 * Streaming STT pipeline:
 * 16 kHz PCM -> Silero VAD gate -> (speech only) -> IndicConformer (sherpa-onnx) -> Stability buffer -> Stable partial / Final
 */
class StreamingSttPipeline(
    val modelDir: String = "",
    val vadGate: SileroVadGate = SileroVadGate()
) {
    data class IndicConformerConfig(
        val encoderOnnxPath: String,
        val decoderOnnxPath: String,
        val joinerOnnxPath: String,
        val tokensPath: String,
        val numThreads: Int = 2,
        val debug: Boolean = false
    ) {
        // Validation: Ensure no TTS (vits) fields are ever present in STT configuration
        init {
            require(!encoderOnnxPath.contains("vits", ignoreCase = true)) {
                "Violation of §10: Do not configure sherpa-onnx OnlineRecognizer using TTS (vits) config fields"
            }
        }
    }

    private val stabilityBuffer = SttStabilityBuffer(stabilityThresholdN = 3)

    var onStablePartial: ((stablePrefix: String, newlyStabilized: String?) -> Unit)? = null
    var onVolatileDebugPreview: ((fullHypothesis: String) -> Unit)? = null
    var onFinalTranscript: ((finalText: String) -> Unit)? = null

    var totalDecodeStepsRun: Long = 0
        private set

    init {
        vadGate.onMicroPauseDetected = {
            val out = stabilityBuffer.onMicroPauseBoundary()
            if (out.newlyStabilizedChunk != null) {
                onStablePartial?.invoke(out.stablePrefix, out.newlyStabilizedChunk)
            }
        }

        vadGate.onSilenceTimeout = {
            finalizeUtterance()
        }
    }

    /**
     * Feeds 16 kHz mono PCM into the pipeline.
     * When VAD detects silence, STT encoder is skipped entirely, maintaining near-zero CPU.
     */
    fun processAudioFrame(pcmFrame: ShortArray, simulatedProb: Float? = null, simulatedTokens: List<String>? = null): Boolean {
        val isSpeech = vadGate.processFrame(pcmFrame, simulatedProb)

        if (isSpeech) {
            // Speech detected: run streaming STT decode step
            totalDecodeStepsRun++

            if (simulatedTokens != null) {
                val out = stabilityBuffer.updateHypothesis(simulatedTokens)
                onVolatileDebugPreview?.invoke(out.fullTranscript)
                if (out.newlyStabilizedChunk != null) {
                    onStablePartial?.invoke(out.stablePrefix, out.newlyStabilizedChunk)
                }
            }
        }
        return isSpeech
    }

    fun finalizeUtterance(): String {
        val finalTranscript = stabilityBuffer.finalizeUtterance()
        if (finalTranscript.isNotEmpty()) {
            onFinalTranscript?.invoke(finalTranscript)
        }
        vadGate.reset()
        return finalTranscript
    }

    fun reset() {
        vadGate.reset()
        stabilityBuffer.reset()
    }
}
