package `in`.itantra.mobile.tests.phase3

import `in`.itantra.mobile.audio.SileroVadGate
import `in`.itantra.mobile.stt.StreamingSttPipeline
import `in`.itantra.mobile.stt.SttStabilityBuffer
import java.util.concurrent.CopyOnWriteArrayList

object Phase3AcceptanceTest {

    private fun check(condition: Boolean, message: String) {
        if (!condition) {
            System.err.println("FAILED ASSERTION: $message")
            throw AssertionError("Test assertion failed: $message")
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== Running Phase 3 Acceptance Tests ===")

        val vadGate = SileroVadGate(sampleRate = 16000, frameSize = 512, speechThreshold = 0.5f)
        val pipeline = StreamingSttPipeline(vadGate = vadGate)

        val debugPreviews = CopyOnWriteArrayList<String>()
        val stablePartials = CopyOnWriteArrayList<String>()
        var finalTranscriptReceived: String? = null

        pipeline.onVolatileDebugPreview = { fullHypothesis ->
            debugPreviews.add(fullHypothesis)
            println("  [Debug UI Preview] $fullHypothesis")
        }

        pipeline.onStablePartial = { stablePrefix, newlyStabilized ->
            stablePartials.add(stablePrefix)
            println("  [Stable Partial Eligible for Network] prefix: \"$stablePrefix\" (new: \"$newlyStabilized\")")
        }

        pipeline.onFinalTranscript = { finalTranscript ->
            finalTranscriptReceived = finalTranscript
            println("  [Final Transcript] \"$finalTranscript\"")
        }

        // -----------------------------------------------------------------
        // Test 1: Idle CPU / Battery Check (VAD gate stays closed on silence)
        // -----------------------------------------------------------------
        println("Test 1: Idle CPU / VAD gating on silence")
        val silenceFrame = ShortArray(512) { 5 } // Near zero amplitude
        for (i in 0 until 100) { // 3.2 seconds of silence
            val fed = pipeline.processAudioFrame(silenceFrame, simulatedProb = 0.05f)
            check(!fed, "VAD gate must stay closed on silence")
        }

        check(pipeline.totalDecodeStepsRun == 0L, "Zero STT decode steps must run on silence (Idle CPU ~ 0%)")
        check(vadGate.speechFramesGatedIn == 0L, "Zero speech frames should be gated in")
        println("✓ Test 1 passed: 100% of silence frames gated out; STT decode steps = 0; Idle CPU near zero")

        // -----------------------------------------------------------------
        // Test 2: Speaking a sentence with stability buffer (N=3 decode steps)
        // -----------------------------------------------------------------
        println("Test 2: Streaming ASR with stability buffer (N=3 hypothesis tracking)")
        val speechFrame = ShortArray(512) { (1500 * Math.sin(it.toDouble())).toInt().toShort() }

        // Step 1: Raw ASR hypothesis: ["हम", "यहाँ"]
        pipeline.processAudioFrame(speechFrame, simulatedProb = 0.95f, simulatedTokens = listOf("हम", "यहाँ"))
        check(stablePartials.isEmpty(), "Step 1: Tokens appear for 1st time, must NOT be marked stable yet")
        check(debugPreviews.last() == "हम यहाँ", "Debug UI should see volatile hypothesis")

        // Step 2: Raw ASR hypothesis: ["हम", "यहाँ", "मद"]
        pipeline.processAudioFrame(speechFrame, simulatedProb = 0.95f, simulatedTokens = listOf("हम", "यहाँ", "मद"))
        check(stablePartials.isEmpty(), "Step 2: 'हम' and 'यहाँ' have count=2, must still NOT be marked stable")

        // Step 3: Raw ASR hypothesis: ["हम", "यहाँ", "मदद"]
        // 'हम' and 'यहाँ' have now appeared for N=3 consecutive steps -> MARK STABLE!
        pipeline.processAudioFrame(speechFrame, simulatedProb = 0.95f, simulatedTokens = listOf("हम", "यहाँ", "मदद"))
        check(stablePartials.isNotEmpty(), "Step 3: Tokens reached N=3 steps, must be marked stable!")
        check(stablePartials.last() == "हम यहाँ", "Stable prefix must be 'हम यहाँ'")
        check(debugPreviews.last() == "हम यहाँ मदद", "Debug preview shows volatile suffix 'मदद'")

        // Step 4: More speech arrives: ["हम", "यहाँ", "मदद", "के"]
        pipeline.processAudioFrame(speechFrame, simulatedProb = 0.95f, simulatedTokens = listOf("हम", "यहाँ", "मदद", "के"))
        // CRITICAL CHECK: "हम यहाँ" must NOT flicker or change!
        check(stablePartials.last().startsWith("हम यहाँ"), "Previously stabilized tokens must never flicker or revert")

        // Step 5: ["हम", "यहाँ", "मदद", "के", "लिए"]
        pipeline.processAudioFrame(speechFrame, simulatedProb = 0.95f, simulatedTokens = listOf("हम", "यहाँ", "मदद", "के", "लिए"))
        check(stablePartials.last().startsWith("हम यहाँ"), "Stable tokens remain locked")

        // Micro-pause boundary detected by VAD (speaker takes a natural breath)
        vadGate.processFrame(silenceFrame, simulatedProb = 0.05f) // silence starts
        // Advance silence to trigger micro-pause boundary
        for (i in 0 until 6) { // ~192ms
            vadGate.processFrame(silenceFrame, simulatedProb = 0.05f)
        }
        check(stablePartials.last() == "हम यहाँ मदद के लिए", "Micro-pause boundary must stabilize the phrase boundary")
        println("✓ Test 2 passed: Partial stability buffer verified (N=3 rule + micro-pause boundary, no flickering)")

        // -----------------------------------------------------------------
        // Test 3: Utterance Finalization on silence timeout
        // -----------------------------------------------------------------
        println("Test 3: Utterance finalization on silence timeout / PTT release")
        // Silence continues to 600ms -> triggers silence timeout
        for (i in 0 until 15) {
            vadGate.processFrame(silenceFrame, simulatedProb = 0.05f)
        }

        check(finalTranscriptReceived != null, "Final transcript must be emitted on end-of-utterance")
        check(finalTranscriptReceived == "हम यहाँ मदद के लिए", "Final transcript must match completed utterance")
        check(vadGate.currentState == SileroVadGate.State.SILENCE, "VAD gate must return to SILENCE state")
        println("✓ Test 3 passed: End-of-utterance finalization succeeded")

        // -----------------------------------------------------------------
        // Test 4: Sherpa-onnx NeMo FastConformer configuration validation
        // -----------------------------------------------------------------
        println("Test 4: IndicConformer config validation (§10 compliance)")
        var vitsErrorCaught = false
        try {
            StreamingSttPipeline.IndicConformerConfig(
                encoderOnnxPath = "/models/vits_model.onnx", // ILLEGAL vits config
                decoderOnnxPath = "/models/decoder.onnx",
                joinerOnnxPath = "/models/joiner.onnx",
                tokensPath = "/models/tokens.txt"
            )
        } catch (e: IllegalArgumentException) {
            vitsErrorCaught = true
        }
        check(vitsErrorCaught, "Config using vits fields must be strictly rejected per §10")

        val validConfig = StreamingSttPipeline.IndicConformerConfig(
            encoderOnnxPath = "/models/indic_conformer_encoder.int8.onnx",
            decoderOnnxPath = "/models/indic_conformer_decoder.int8.onnx",
            joinerOnnxPath = "/models/indic_conformer_joiner.int8.onnx",
            tokensPath = "/models/tokens.txt"
        )
        check(validConfig.encoderOnnxPath.endsWith(".onnx"), "Valid NeMo FastConformer transducer config created")
        println("✓ Test 4 passed: Strict prohibition of vits config fields validated")

        println("=== Phase 3 Acceptance Criteria: ALL PASSED ===")
    }
}
