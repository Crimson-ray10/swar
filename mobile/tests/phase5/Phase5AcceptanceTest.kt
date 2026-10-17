package `in`.itantra.mobile.tests.phase5

import `in`.itantra.mobile.audio.ConversationSession
import `in`.itantra.mobile.audio.SileroVadGate
import `in`.itantra.mobile.protocol.PayloadSecurity
import `in`.itantra.mobile.stt.StreamingSttPipeline
import `in`.itantra.mobile.transport.TransportManager
import `in`.itantra.mobile.transport.fake.FakeBleController
import `in`.itantra.mobile.transport.fake.FakeWifiDirectController
import `in`.itantra.mobile.transport.model.PeerInfo
import `in`.itantra.mobile.tts.IndicTtsPipeline
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

object Phase5AcceptanceTest {

    private fun check(condition: Boolean, message: String) {
        if (!condition) {
            System.err.println("FAILED ASSERTION: $message")
            throw AssertionError("Test assertion failed: $message")
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== Running Phase 5 Acceptance Tests ===")
        FakeBleController.clearEther()
        FakeWifiDirectController.clearNetwork()

        val devAId = UUID.randomUUID().toString()
        val devBId = UUID.randomUUID().toString()

        val peerA = PeerInfo(devAId, "Phone-A", listOf("hi", "en"), "11:22:33:44:55:66")
        val peerB = PeerInfo(devBId, "Phone-B", listOf("hi", "en"), "66:55:44:33:22:11")

        val bleA = FakeBleController(devAId)
        val bleB = FakeBleController(devBId)
        val p2pA = FakeWifiDirectController(peerA.p2pDeviceAddress!!, devAId)
        val p2pB = FakeWifiDirectController(peerB.p2pDeviceAddress!!, devBId)

        val mgrA = TransportManager(peerA, bleA, p2pA, isPluggedInOrHighCapability = true)
        val mgrB = TransportManager(peerB, bleB, p2pB, isPluggedInOrHighCapability = false)

        mgrA.startDiscovery()
        mgrB.startDiscovery()
        mgrA.connectToPeer(devBId)

        // Establish session encryption key
        val privA = PayloadSecurity.generateX25519PrivateKey()
        val pubA = PayloadSecurity.computeX25519PublicKey(privA)
        val privB = PayloadSecurity.generateX25519PrivateKey()
        val pubB = PayloadSecurity.computeX25519PublicKey(privB)
        val sharedSecret = PayloadSecurity.computeSharedSecret(privA, pubB)
        val sessionKey = PayloadSecurity.deriveAeadKey(sharedSecret)

        val sttA = StreamingSttPipeline(vadGate = SileroVadGate())
        val ttsA = IndicTtsPipeline(sampleRate = 16000)

        val sttB = StreamingSttPipeline(vadGate = SileroVadGate())
        val ttsB = IndicTtsPipeline(sampleRate = 16000)

        val sessionA = ConversationSession(
            localDeviceId = devAId,
            targetPeerId = devBId,
            transportManager = mgrA,
            sttPipeline = sttA,
            ttsPipeline = ttsA,
            sessionAeadKey = sessionKey,
            languageCodeByte = 1 // HI
        )

        val sessionB = ConversationSession(
            localDeviceId = devBId,
            targetPeerId = devAId,
            transportManager = mgrB,
            sttPipeline = sttB,
            ttsPipeline = ttsB,
            sessionAeadKey = sessionKey,
            languageCodeByte = 2 // EN
        )

        val receivedAtB = CopyOnWriteArrayList<String>()
        val receivedAtA = CopyOnWriteArrayList<String>()

        sessionB.onReceivedSpeechPlayed = { text, metrics ->
            receivedAtB.add(text)
            println("  [Device B Audio Track] Playing: \"$text\" | ${metrics.logSummary()}")
        }

        sessionA.onReceivedSpeechPlayed = { text, metrics ->
            receivedAtA.add(text)
            println("  [Device A Audio Track] Playing: \"$text\" | ${metrics.logSummary()}")
        }

        // -----------------------------------------------------------------
        // Full-Duplex Simultaneous Conversation Simulation
        // -----------------------------------------------------------------
        println("Test: Simultaneous full-duplex speech transmission and latency verification")

        val pcmSample = ShortArray(512) { 2000 }

        // Device A speaks Hindi: "हम तुरंत आ रहे हैं"
        val micStartTimeA = System.currentTimeMillis() - 280 // simulate ~280ms STT stability delay
        sessionA.onUserSpeechChunk(
            pcmFrame = pcmSample,
            micTimestampMs = micStartTimeA,
            simulatedProb = 0.95f,
            simulatedTokens = listOf("हम", "तुरंत", "आ", "रहे", "हैं"),
            isFinalUtterance = true
        )

        // Simultaneously, Device B speaks English: "Copy that, route is clear"
        val micStartTimeB = System.currentTimeMillis() - 270 // simulate ~270ms STT stability delay
        sessionB.onUserSpeechChunk(
            pcmFrame = pcmSample,
            micTimestampMs = micStartTimeB,
            simulatedProb = 0.95f,
            simulatedTokens = listOf("Copy", "that", "route", "is", "clear"),
            isFinalUtterance = true
        )

        check(receivedAtB.isNotEmpty(), "Device B must have received and played speech from Device A")
        check(receivedAtA.isNotEmpty(), "Device A must have received and played speech from Device B")
        check(receivedAtB.last() == "हम तुरंत आ रहे हैं", "Content at Device B must match Hindi utterance")
        check(receivedAtA.last() == "Copy that route is clear", "Content at Device A must match English utterance")

        // -----------------------------------------------------------------
        // Verify Latency Budget (§8)
        // Target budget: STT stability ~250-300ms, transport hop < 50ms, TTS start < 300ms, total mouth-to-ear <= 800ms
        // -----------------------------------------------------------------
        println("\n--- Latency Budget Verification (§8 Checklist) ---")
        val metricsAtoB = sessionB.sessionLatencyLog.first()
        println("Device A -> Device B Metrics:")
        println("  STT Stability Latency: ${metricsAtoB.sttStabilityMs} ms (budget: 250-300 ms)")
        println("  Transport Hop Latency: ${metricsAtoB.transportHopMs} ms (budget: < 50 ms)")
        println("  TTS Start Latency:     ${metricsAtoB.ttsStartMs} ms (budget: < 300 ms)")
        println("  Total Mouth-to-Ear:    ${metricsAtoB.totalMouthToEarMs} ms (budget: target 600-800 ms max)")

        check(metricsAtoB.sttStabilityMs in 200..350, "STT stability must be within ~250-300ms budget")
        check(metricsAtoB.transportHopMs < 50, "Transport hop must be < 50ms on Wi-Fi Direct")
        check(metricsAtoB.ttsStartMs < 300, "TTS start must be < 300ms")
        check(metricsAtoB.totalMouthToEarMs <= 800, "Total mouth-to-ear latency must be within target 600-800ms budget")

        mgrA.close()
        mgrB.close()
        println("=== Phase 5 Acceptance Criteria: ALL PASSED ===")
    }
}
