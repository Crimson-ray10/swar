package `in`.itantra.mobile.tests.phase6

import `in`.itantra.mobile.audio.SileroVadGate
import `in`.itantra.mobile.audio.WalkieTalkieBroadcastSession
import `in`.itantra.mobile.stt.StreamingSttPipeline
import `in`.itantra.mobile.transport.TransportManager
import `in`.itantra.mobile.transport.fake.FakeBleController
import `in`.itantra.mobile.transport.fake.FakeWifiDirectController
import `in`.itantra.mobile.transport.model.PeerInfo
import `in`.itantra.mobile.tts.IndicTtsPipeline
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

object Phase6AcceptanceTest {

    private fun check(condition: Boolean, message: String) {
        if (!condition) {
            System.err.println("FAILED ASSERTION: $message")
            throw AssertionError("Test assertion failed: $message")
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== Running Phase 6 Acceptance Tests ===")
        FakeBleController.clearEther()
        FakeWifiDirectController.clearNetwork()

        val devAId = UUID.randomUUID().toString()
        val devBId = UUID.randomUUID().toString()
        val devCId = UUID.randomUUID().toString()

        // 3 devices in group: Device A is Group Owner / Hub
        val peerA = PeerInfo(devAId, "Phone-A (GO-Hub)", listOf("hi", "en"), "11:11:11:11:11:11")
        val peerB = PeerInfo(devBId, "Phone-B (Client 1)", listOf("hi", "en"), "22:22:22:22:22:22")
        val peerC = PeerInfo(devCId, "Phone-C (Client 2)", listOf("hi", "en"), "33:33:33:33:33:33")

        val bleA = FakeBleController(devAId)
        val bleB = FakeBleController(devBId)
        val bleC = FakeBleController(devCId)

        val p2pA = FakeWifiDirectController(peerA.p2pDeviceAddress!!, devAId)
        val p2pB = FakeWifiDirectController(peerB.p2pDeviceAddress!!, devBId)
        val p2pC = FakeWifiDirectController(peerC.p2pDeviceAddress!!, devCId)

        val mgrA = TransportManager(peerA, bleA, p2pA, isPluggedInOrHighCapability = true)
        val mgrB = TransportManager(peerB, bleB, p2pB, isPluggedInOrHighCapability = false)
        val mgrC = TransportManager(peerC, bleC, p2pC, isPluggedInOrHighCapability = false)

        mgrA.startDiscovery()
        mgrB.startDiscovery()
        mgrC.startDiscovery()

        // Form group with A as GO
        mgrA.connectToPeer(devBId)
        mgrA.connectToPeer(devCId)

        val sessionA = WalkieTalkieBroadcastSession(devAId, isGroupOwnerHub = true, mgrA, StreamingSttPipeline(vadGate = SileroVadGate()), IndicTtsPipeline())
        val sessionB = WalkieTalkieBroadcastSession(devBId, isGroupOwnerHub = false, mgrB, StreamingSttPipeline(vadGate = SileroVadGate()), IndicTtsPipeline())
        val sessionC = WalkieTalkieBroadcastSession(devCId, isGroupOwnerHub = false, mgrC, StreamingSttPipeline(vadGate = SileroVadGate()), IndicTtsPipeline())

        val heardAtA = CopyOnWriteArrayList<String>()
        val heardAtB = CopyOnWriteArrayList<String>()
        val heardAtC = CopyOnWriteArrayList<String>()

        sessionA.onSpeechPlayed = { sender, text ->
            heardAtA.add(text)
            println("  [Speaker Device A (Hub)] Heard from $sender: \"$text\"")
        }

        sessionB.onSpeechPlayed = { sender, text ->
            heardAtB.add(text)
            println("  [Speaker Device B (Client 1)] Heard from $sender: \"$text\"")
        }

        sessionC.onSpeechPlayed = { sender, text ->
            heardAtC.add(text)
            println("  [Speaker Device C (Client 2)] Heard from $sender: \"$text\"")
        }

        // -----------------------------------------------------------------
        // Test 1: One sender at a time, all receivers hear audio (3 devices)
        // -----------------------------------------------------------------
        println("Test 1: Device B acquires floor and broadcasts to all group members (A and C)")
        val pttGrantedB = sessionB.pressPushToTalk()
        check(pttGrantedB, "Device B should acquire the floor")
        check(sessionB.floorController.isFloorHeldByMe, "Floor must be held by Device B")

        val pcm = ShortArray(512) { 1500 }
        sessionB.onMicPcmChunk(pcm, simulatedTokens = listOf("Team", "Alpha", "moving", "to", "sector", "4"))
        sessionB.releasePushToTalk()

        check(heardAtA.isNotEmpty(), "Device A (Hub) must hear audio from Device B")
        check(heardAtC.isNotEmpty(), "Device C (Client 2) must hear audio forwarded by Hub from Device B")
        check(heardAtA.last() == "Team Alpha moving to sector 4", "Message at Device A must match utterance")
        check(heardAtC.last() == "Team Alpha moving to sector 4", "Message at Device C must match utterance")
        println("✓ Test 1 passed: 1-to-many broadcast delivered audio to all receivers via Hub")

        // -----------------------------------------------------------------
        // Test 2: Second sender attempting to talk while floor is held is BLOCKED / SUPPRESSED
        // -----------------------------------------------------------------
        println("Test 2: Device C attempts to talk while Device B holds floor -> BLOCKED")
        // Device B re-acquires floor
        sessionB.pressPushToTalk()
        check(sessionB.floorController.isFloorHeldByMe, "Device B holds floor")

        // While B is speaking, Device C attempts to press PTT
        val pttGrantedCWhileBusy = sessionC.pressPushToTalk()
        check(!pttGrantedCWhileBusy, "Device C PTT press must be REJECTED/BLOCKED while channel is busy")
        check(sessionC.floorController.shouldSuppressLocalMic, "Device C must suppress local mic capture")

        // Attempting to feed mic chunk on Device C must be blocked:
        val micFedC = sessionC.onMicPcmChunk(pcm, simulatedTokens = listOf("Interruption", "attempt"))
        check(!micFedC, "Device C mic capture must be suppressed (cannot transmit)")

        check(sessionC.floorController.isChannelBusy, "Device C must show Channel Busy indicator")
        println("✓ Test 2 passed: Second sender blocked, mic suppressed, channel busy indicator active")

        // -----------------------------------------------------------------
        // Test 3: Device B releases floor -> Device C can now transmit
        // -----------------------------------------------------------------
        println("Test 3: Device B releases floor -> Device C acquires floor and broadcasts")
        sessionB.releasePushToTalk()

        check(!sessionC.floorController.shouldSuppressLocalMic, "Mic suppression on C should be lifted after floor release")

        val pttGrantedCAfterRelease = sessionC.pressPushToTalk()
        check(pttGrantedCAfterRelease, "Device C should now be granted the floor")

        sessionC.onMicPcmChunk(pcm, simulatedTokens = listOf("Team", "Bravo", "acknowledging"))
        sessionC.releasePushToTalk()

        check(heardAtA.last() == "Team Bravo acknowledging", "Device A must hear Device C")
        check(heardAtB.last() == "Team Bravo acknowledging", "Device B must hear Device C")
        println("✓ Test 3 passed: Floor released and acquired by next sender; all receivers heard audio")

        mgrA.close()
        mgrB.close()
        mgrC.close()
        println("=== Phase 6 Acceptance Criteria: ALL PASSED ===")
    }
}
