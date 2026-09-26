package `in`.itantra.mobile.tests.phase4

import `in`.itantra.mobile.tts.*

object Phase4AcceptanceTest {

    private fun check(condition: Boolean, message: String) {
        if (!condition) {
            System.err.println("FAILED ASSERTION: $message")
            throw AssertionError("Test assertion failed: $message")
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== Running Phase 4 Acceptance Tests ===")

        val tts = IndicTtsPipeline(sampleRate = 16000)

        // -----------------------------------------------------------------
        // Test 1: Indic-TTS Two-Stage Synthesis & RTF < 0.3 Verification
        // -----------------------------------------------------------------
        println("Test 1: Indic-TTS (FastPitch -> HiFi-GAN) Two-Stage Pipeline & RTF Benchmark")
        val testUtterances = listOf(
            Pair("नमस्ते, यह आवाज परीक्षण संदेश है।", "hi"),
            Pair("Immediate emergency response team required at North Gate.", "en"),
            Pair("हेल्प! मला येथे ताबडतोब मदत हवी आहे.", "mr")
        )

        for ((text, lang) in testUtterances) {
            println("  Synthesizing [$lang]: \"$text\"")
            val tokens = tts.tokenizeText(text, lang)
            check(tokens.isNotEmpty(), "Tokenizer must generate non-empty token IDs")

            val melSpec = tts.runFastPitch(tokens)
            check(melSpec.size == 80, "FastPitch must produce 80-channel mel-spectrogram")
            check(melSpec[0].isNotEmpty(), "Mel-spectrogram frames must be generated")

            val waveform = tts.runHiFiGan(melSpec)
            check(waveform.isNotEmpty(), "HiFi-GAN must produce audio waveform")

            val result = tts.synthesize(text, lang)
            println("    -> Generated ${result.audioSamples.size} samples (${result.audioDurationMs} ms audio)")
            println("    -> Synthesis time: ${result.synthesisTimeMs} ms | RTF: ${String.format("%.4f", result.rtf)}")

            // CRITICAL ACCEPTANCE CRITERION: RTF < 0.3
            check(result.rtf < 0.3, "RTF must be strictly < 0.3 (measured: ${result.rtf})")
            check(result.audioDurationMs > 0, "Audio duration must be > 0")
        }
        println("✓ Test 1 passed: Intelligible 16kHz audio synthesized across languages with RTF < 0.3")

        // -----------------------------------------------------------------
        // Test 2: Jitter Buffer Reordering & De-duplication
        // -----------------------------------------------------------------
        println("Test 2: Jitter buffer smoothing and sequence ordering")
        val jitterBuffer = JitterBuffer(targetDelayMs = 50)

        val streamId = 200
        // Packets arrive out of order: seq 3, seq 1, seq 2, and duplicate seq 2
        val item1 = JitterBufferItem(streamId, 1, "हम", 1, false)
        val item2 = JitterBufferItem(streamId, 2, "यहाँ", 1, false)
        val item3 = JitterBufferItem(streamId, 3, "हैं", 1, true)

        jitterBuffer.push(item3)
        jitterBuffer.push(item1)
        jitterBuffer.push(item2)
        jitterBuffer.push(item2) // Duplicate!

        Thread.sleep(60) // Let target delay elapse

        val out1 = jitterBuffer.pollReady()
        val out2 = jitterBuffer.pollReady()
        val out3 = jitterBuffer.pollReady()
        val out4 = jitterBuffer.pollReady()

        check(out1?.sequenceNumber == 1, "First emitted chunk must have sequenceNumber 1")
        check(out2?.sequenceNumber == 2, "Second emitted chunk must have sequenceNumber 2")
        check(out3?.sequenceNumber == 3, "Third emitted chunk must have sequenceNumber 3")
        check(out4 == null, "Duplicate packet must have been dropped; queue must be empty")
        println("✓ Test 2 passed: Jitter buffer successfully re-ordered packets and dropped duplicates")

        // -----------------------------------------------------------------
        // Test 3: Floor-Aware Sequential Playback (No Mixed Audio Overlap)
        // -----------------------------------------------------------------
        println("Test 3: Floor-aware playback queue (sequential queuing without mixing)")
        val audioQueue = FloorAwareAudioQueue()

        val chunkSenderA = PlaybackChunk("dev-A", ShortArray(1600) { 100 }, "Speech from A")
        val chunkSenderB = PlaybackChunk("dev-B", ShortArray(1600) { 200 }, "Speech from B")

        // Both chunks arrive concurrently
        audioQueue.enqueue(chunkSenderA)
        audioQueue.enqueue(chunkSenderB)

        check(audioQueue.getPendingCount() == 2, "Both chunks queued")

        // Play chunk 1
        val playing1 = audioQueue.pollNextChunk()
        check(playing1?.senderDeviceId == "dev-A", "First played chunk is from Sender A")
        check(audioQueue.getCurrentlyPlayingSender() == "dev-A", "Active player locked to Sender A")

        // Play chunk 2 (sequentially after A finishes)
        val playing2 = audioQueue.pollNextChunk()
        check(playing2?.senderDeviceId == "dev-B", "Second played chunk is from Sender B (not mixed with A)")
        check(audioQueue.getCurrentlyPlayingSender() == "dev-B", "Active player now locked to Sender B")

        println("✓ Test 3 passed: Audio from multiple senders queued sequentially without overlapping crosstalk")

        println("=== Phase 4 Acceptance Criteria: ALL PASSED ===")
    }
}
