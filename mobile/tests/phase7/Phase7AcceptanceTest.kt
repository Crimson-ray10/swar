package `in`.itantra.mobile.tests.phase7

import `in`.itantra.mobile.translation.IndicNlpNormalizer
import `in`.itantra.mobile.translation.IndicTrans2KvCacheTranslator
import `in`.itantra.mobile.tts.IndicTtsPipeline

object Phase7AcceptanceTest {

    private fun check(condition: Boolean, message: String) {
        if (!condition) {
            System.err.println("FAILED ASSERTION: $message")
            throw AssertionError("Test assertion failed: $message")
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== Running Phase 7 Acceptance Tests ===")

        val translator = IndicTrans2KvCacheTranslator(isBetaTranslationEnabled = false)
        val tts = IndicTtsPipeline(sampleRate = 16000)

        // -----------------------------------------------------------------
        // Test 1: Feature Flag & Language Pair Gating
        // -----------------------------------------------------------------
        println("Test 1: Gating checks (feature flag & supported language pairs)")
        val testText = "Emergency help needed at gate 3"

        // Beta disabled: must return null (bypassed)
        val resDisabled = translator.translateIfEligible(testText, "en", "hi")
        check(resDisabled == null, "When beta is disabled, translation must be bypassed completely")

        // Enable beta
        translator.isBetaTranslationEnabled = true

        // Other language pairs (e.g. Marathi -> Marathi, Tamil -> Telugu, Hindi -> Marathi):
        val resMrMr = translator.translateIfEligible("मदत हवी आहे", "mr", "mr")
        check(resMrMr == null, "Non-en/hi language pairs (mr->mr) must bypass translation completely")

        val resHiMr = translator.translateIfEligible("मदद चाहिए", "hi", "mr")
        check(resHiMr == null, "Non-en/hi pairs (hi->mr) must bypass translation completely")

        val resEnHi = translator.translateIfEligible(testText, "en", "hi")
        check(resEnHi != null, "en -> hi with beta enabled must be translated")
        println("  -> Translated en->hi: \"${resEnHi!!.translatedText}\"")

        val resHiEn = translator.translateIfEligible("हमें मुख्य द्वार पर मदद चाहिए।", "hi", "en")
        check(resHiEn != null, "hi -> en with beta enabled must be translated")
        println("  -> Translated hi->en: \"${resHiEn!!.translatedText}\"")
        println("✓ Test 1 passed: Strict feature-flag gating and en<->hi language pair restrictions verified")

        // -----------------------------------------------------------------
        // Test 2: AI4Bharat IndicNLP Normalization
        // -----------------------------------------------------------------
        println("Test 2: AI4Bharat IndicNLP text normalization before translation and before TTS")
        val rawInput = "कमरा नंबर १२३ में डॉक्टर और police को बुलाओ।। "
        val normalized = IndicNlpNormalizer.normalize(rawInput, "hi")
        println("  Raw:        \"$rawInput\"")
        println("  Normalized: \"$normalized\"")

        // Check numeral normalization: १२३ -> 123
        check(normalized.contains("123"), "Indic digits must be normalized to standard digits")
        // Check danda normalization: ।। -> .
        check(!normalized.contains("।।"), "Double danda must be normalized to standard punctuation")
        // Check loanword expansion
        check(normalized.contains("पुलिस"), "English loanword police should be normalized for Hindi TTS")
        println("✓ Test 2 passed: IndicNLP normalization verified")

        // -----------------------------------------------------------------
        // Test 3: KV-Cache Linear vs Quadratic Complexity Verification
        // -----------------------------------------------------------------
        println("Test 3: KV-Cache linear scaling benchmark (checking O(1) decode cost per step)")
        val longUtterance = "We require immediate reinforcement and ambulance dispatch at the northern perimeter checkpoint"
        val longTranslation = translator.translateIfEligible(longUtterance, "en", "hi")
        check(longTranslation != null, "Translation must complete")
        check(longTranslation!!.usedKvCache, "Must use explicit KV cache")

        val stepLatencies = longTranslation.decodeStepLatenciesMs
        println("  Decode step count: ${stepLatencies.size} tokens")
        println("  Sample step latencies: ${stepLatencies.take(10).joinToString(", ")} ms")

        // Verify that step latency does NOT increase quadratically with step index:
        // In quadratic O(N^2) no-KV-cache, the 10th step would be significantly slower than step 1.
        // In our O(N) KV-cache, decode step time remains constant O(1).
        val firstHalfAvg = stepLatencies.take(stepLatencies.size / 2).average()
        val secondHalfAvg = stepLatencies.drop(stepLatencies.size / 2).average()
        println("  First-half avg step latency: ${String.format("%.2f", firstHalfAvg)} ms | Second-half avg: ${String.format("%.2f", secondHalfAvg)} ms")
        check(Math.abs(firstHalfAvg - secondHalfAvg) < 5.0, "Decode cost per token must remain flat O(1), proving linear O(N) scaling")
        println("✓ Test 3 passed: Linear O(N) KV-cache scaling verified (no quadratic degradation)")

        // -----------------------------------------------------------------
        // Test 4: End-to-End Translated Speech Synthesis
        // -----------------------------------------------------------------
        println("Test 4: End-to-end translated text -> TTS speech synthesis")
        val synthResult = tts.synthesize(resEnHi.translatedText, "hi")
        println("  -> Synthesized translated Hindi speech: ${synthResult.audioSamples.size} audio samples (${synthResult.audioDurationMs} ms)")
        check(synthResult.audioSamples.isNotEmpty(), "Synthesized audio must not be empty")
        check(synthResult.rtf < 0.3, "TTS RTF for translated speech must remain < 0.3")
        println("✓ Test 4 passed: End-to-end translated speech synthesized within budget")

        println("=== Phase 7 Acceptance Criteria: ALL PASSED ===")
    }
}
