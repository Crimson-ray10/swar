package `in`.itantra.mobile.stt

class SttStabilityBuffer(
    val stabilityThresholdN: Int = 3
) {
    data class CandidateToken(
        var text: String,
        var consecutiveCount: Int,
        var isStable: Boolean = false
    )

    data class StabilityOutput(
        val stablePrefix: String,
        val volatileSuffix: String,
        val fullTranscript: String,
        val newlyStabilizedChunk: String?
    )

    private val tokens = ArrayList<CandidateToken>()
    private var lastEmittedStableText = ""

    /**
     * Receives a raw hypothesis from streaming ASR (list of words/tokens).
     * Tracks token stability across N consecutive decode steps.
     */
    fun updateHypothesis(rawTokens: List<String>): StabilityOutput {
        if (rawTokens.isEmpty()) {
            return StabilityOutput(lastEmittedStableText, "", lastEmittedStableText, null)
        }

        // Align candidate tokens with incoming hypothesis
        val minLen = Math.min(tokens.size, rawTokens.size)
        for (i in 0 until minLen) {
            val candidate = tokens[i]
            val incoming = rawTokens[i]
            if (candidate.isStable) {
                // Stable tokens are immutable; keep stable text
                continue
            }
            if (candidate.text == incoming) {
                candidate.consecutiveCount++
                if (candidate.consecutiveCount >= stabilityThresholdN) {
                    candidate.isStable = true
                }
            } else {
                candidate.text = incoming
                candidate.consecutiveCount = 1
                candidate.isStable = false
            }
        }

        // If incoming has more tokens than candidate list
        if (rawTokens.size > tokens.size) {
            for (i in tokens.size until rawTokens.size) {
                tokens.add(CandidateToken(rawTokens[i], consecutiveCount = 1, isStable = false))
            }
        } else if (rawTokens.size < tokens.size) {
            // Trim volatile tail
            var removeIdx = tokens.size - 1
            while (removeIdx >= rawTokens.size && !tokens[removeIdx].isStable) {
                tokens.removeAt(removeIdx)
                removeIdx--
            }
        }

        // Build stable prefix vs volatile suffix
        val stableList = ArrayList<String>()
        val volatileList = ArrayList<String>()
        var stillInStablePrefix = true

        for (token in tokens) {
            if (token.isStable && stillInStablePrefix) {
                stableList.add(token.text)
            } else {
                stillInStablePrefix = false
                volatileList.add(token.text)
            }
        }

        val currentStableText = stableList.joinToString(" ").trim()
        val volatileText = volatileList.joinToString(" ").trim()
        val fullText = (if (currentStableText.isEmpty()) volatileText else if (volatileText.isEmpty()) currentStableText else "$currentStableText $volatileText").trim()

        var newlyStabilized: String? = null
        if (currentStableText.length > lastEmittedStableText.length) {
            val diff = currentStableText.removePrefix(lastEmittedStableText).trim()
            if (diff.isNotEmpty()) {
                newlyStabilized = diff
                lastEmittedStableText = currentStableText
            }
        }

        return StabilityOutput(
            stablePrefix = currentStableText,
            volatileSuffix = volatileText,
            fullTranscript = fullText,
            newlyStabilizedChunk = newlyStabilized
        )
    }

    /**
     * VAD micro-pause detected: confirms utterance boundary, immediately promoting all
     * candidate tokens to stable to minimize phrase-boundary latency.
     */
    fun onMicroPauseBoundary(): StabilityOutput {
        for (token in tokens) {
            token.isStable = true
        }
        val allStable = tokens.map { it.text }.joinToString(" ").trim()
        var newlyStabilized: String? = null
        if (allStable.length > lastEmittedStableText.length) {
            val diff = allStable.removePrefix(lastEmittedStableText).trim()
            if (diff.isNotEmpty()) {
                newlyStabilized = diff
                lastEmittedStableText = allStable
            }
        }
        return StabilityOutput(
            stablePrefix = allStable,
            volatileSuffix = "",
            fullTranscript = allStable,
            newlyStabilizedChunk = newlyStabilized
        )
    }

    /**
     * End of utterance (PTT release or silence timeout):
     * Flushes recognizer, marks all tokens stable, returns final transcript, and resets buffer.
     */
    fun finalizeUtterance(): String {
        val finalTranscript = tokens.map { it.text }.joinToString(" ").trim()
        reset()
        return finalTranscript
    }

    fun reset() {
        tokens.clear()
        lastEmittedStableText = ""
    }
}
