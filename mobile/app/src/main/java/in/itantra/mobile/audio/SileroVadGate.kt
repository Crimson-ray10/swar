package `in`.itantra.mobile.audio

class SileroVadGate(
    val sampleRate: Int = 16000,
    val frameSize: Int = 512, // 32ms at 16kHz
    val speechThreshold: Float = 0.5f,
    val silenceTimeoutMs: Long = 600,
    val microPauseMs: Long = 180
) {
    enum class State {
        SILENCE,
        SPEECH,
        MICRO_PAUSE
    }

    var currentState: State = State.SILENCE
        private set

    var onSpeechStateChanged: ((State) -> Unit)? = null
    var onMicroPauseDetected: (() -> Unit)? = null
    var onSilenceTimeout: (() -> Unit)? = null

    private var consecutiveSilenceMs: Long = 0
    private var consecutiveSpeechMs: Long = 0
    private var inSpeech = false
    private var microPauseReported = false

    var totalFramesProcessed: Long = 0
        private set
    var speechFramesGatedIn: Long = 0
        private set

    /**
     * Estimates voice activity on a 512-sample PCM frame.
     * Returns true if frame contains speech (should be fed to STT), false if silence (STT skipped).
     */
    fun processFrame(pcmFrame: ShortArray, simulatedProb: Float? = null): Boolean {
        totalFramesProcessed++
        val frameDurationMs = (frameSize * 1000L) / sampleRate

        val prob = simulatedProb ?: computeFrameProbability(pcmFrame)

        if (prob >= speechThreshold) {
            consecutiveSpeechMs += frameDurationMs
            consecutiveSilenceMs = 0
            microPauseReported = false

            if (!inSpeech && consecutiveSpeechMs >= 64) { // speech onset debounce
                inSpeech = true
                currentState = State.SPEECH
                onSpeechStateChanged?.invoke(State.SPEECH)
            }
            speechFramesGatedIn++
            return true
        } else {
            consecutiveSilenceMs += frameDurationMs
            consecutiveSpeechMs = 0

            if (inSpeech) {
                if (consecutiveSilenceMs >= microPauseMs && !microPauseReported && consecutiveSilenceMs < silenceTimeoutMs) {
                    microPauseReported = true
                    currentState = State.MICRO_PAUSE
                    onMicroPauseDetected?.invoke()
                } else if (consecutiveSilenceMs >= silenceTimeoutMs) {
                    inSpeech = false
                    currentState = State.SILENCE
                    onSpeechStateChanged?.invoke(State.SILENCE)
                    onSilenceTimeout?.invoke()
                }
            }
            return false // Gate closed: do not run STT on silence
        }
    }

    fun reset() {
        currentState = State.SILENCE
        consecutiveSilenceMs = 0
        consecutiveSpeechMs = 0
        inSpeech = false
        microPauseReported = false
    }

    private fun computeFrameProbability(pcm: ShortArray): Float {
        if (pcm.isEmpty()) return 0f
        var sumSquares = 0.0
        for (sample in pcm) {
            val norm = sample.toDouble() / 32768.0
            sumSquares += norm * norm
        }
        val rms = Math.sqrt(sumSquares / pcm.size)
        // Normalized logistic sigmoid curve around energy floor (-36 dBFS ~ 0.0158)
        val energyFloor = 0.018
        val k = 180.0
        val sigmoid = 1.0 / (1.0 + Math.exp(-k * (rms - energyFloor)))
        return sigmoid.toFloat()
    }
}
