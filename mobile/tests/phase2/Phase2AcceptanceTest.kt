package `in`.itantra.mobile.tests.phase2

import `in`.itantra.mobile.protocol.*
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

object Phase2AcceptanceTest {

    private fun check(condition: Boolean, message: String) {
        if (!condition) {
            System.err.println("FAILED ASSERTION: $message")
            throw AssertionError("Test assertion failed: $message")
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== Running Phase 2 Acceptance Tests ===")

        val devAId = UUID.randomUUID().toString()
        val devBId = UUID.randomUUID().toString()
        val devCId = UUID.randomUUID().toString()

        // ----------------------------------------------------
        // Test 1: Captured traffic is NOT plaintext (X25519 + ChaCha20-Poly1305)
        // ----------------------------------------------------
        println("Test 1: Captured traffic is not plaintext (Encryption & AEAD)")
        // Generate X25519 keypairs for Device A and Device B
        val privA = PayloadSecurity.generateX25519PrivateKey()
        val pubA = PayloadSecurity.computeX25519PublicKey(privA)

        val privB = PayloadSecurity.generateX25519PrivateKey()
        val pubB = PayloadSecurity.computeX25519PublicKey(privB)

        // Key Agreement
        val sharedSecretA = PayloadSecurity.computeSharedSecret(privA, pubB)
        val sharedSecretB = PayloadSecurity.computeSharedSecret(privB, pubA)
        check(sharedSecretA.contentEquals(sharedSecretB), "X25519 shared secret agreement must match on both sides")

        // Key derivation
        val aeadKeyA = PayloadSecurity.deriveAeadKey(sharedSecretA)
        val aeadKeyB = PayloadSecurity.deriveAeadKey(sharedSecretB)
        check(aeadKeyA.contentEquals(aeadKeyB), "Derived AEAD key must match")

        val secretText = "अत्यधिक गोपनीय संदेश: Rescue team needed at sector 7!"
        val plainBytes = secretText.toByteArray(StandardCharsets.UTF_8)
        val nonce = ByteArray(12).also { it[0] = 1; it[11] = 42 }

        val dummyHeaderAad = ByteArray(VoicePayload.HEADER_SIZE) { 0x07 }
        val encryptedBytes = PayloadSecurity.encrypt(aeadKeyA, nonce, plainBytes, dummyHeaderAad)

        // CRITICAL CHECK: Captured traffic must NOT contain plaintext words or substrings
        val wireString = String(encryptedBytes, StandardCharsets.ISO_8859_1)
        check(!wireString.contains("Rescue"), "Captured wire bytes must not contain English plaintext")
        check(!wireString.contains("गोपनीय"), "Captured wire bytes must not contain Hindi plaintext")
        check(!encryptedBytes.contentEquals(plainBytes), "Ciphertext must not match plaintext")
        println("  -> Wire ciphertext length: ${encryptedBytes.size} bytes (plain: ${plainBytes.size} + 16B Poly1305 tag)")

        // Decryption at receiver B
        val decryptedBytes = PayloadSecurity.decrypt(aeadKeyB, nonce, encryptedBytes, dummyHeaderAad)
        val decryptedText = String(decryptedBytes, StandardCharsets.UTF_8)
        check(decryptedText == secretText, "Decrypted text must match original secret message")

        // Tamper test: If a bit is flipped on the wire, Poly1305 authentication MUST reject it
        val tamperedBytes = encryptedBytes.clone()
        tamperedBytes[5] = (tamperedBytes[5].toInt() xor 0x01).toByte()
        var caughtTamper = false
        try {
            PayloadSecurity.decrypt(aeadKeyB, nonce, tamperedBytes, dummyHeaderAad)
        } catch (e: SecurityException) {
            caughtTamper = true
        }
        check(caughtTamper, "Tampered ciphertext must fail Poly1305 authentication")
        println("✓ Test 1 passed: Encryption, X25519 key exchange, and AEAD tamper protection verified")

        // ----------------------------------------------------
        // Test 2: Dropped-final-message retransmission works under simulated packet loss
        // ----------------------------------------------------
        println("Test 2: stt_final ACK and bounded retransmission under simulated packet loss")
        val reliabilityA = ReliabilityEngine(devAId, maxRetries = 3, retransmitTimeoutMs = 50)

        val streamId = 101
        val finalSeq = reliabilityA.nextSequence()
        val finalPayload = VoicePayload(
            type = MessageType.STT_FINAL,
            senderDeviceId = devAId,
            streamId = streamId,
            sequenceNumber = finalSeq,
            languageCode = 1, // HI
            flags = 1, // isFinal
            payloadBytes = encryptedBytes
        )

        reliabilityA.registerFinalMessage(finalPayload)
        check(reliabilityA.hasPendingMessage(streamId, finalSeq), "Final message must be registered in unacked queue")

        // Simulate network with 66% drop rate (first 2 attempts dropped, 3rd succeeds)
        var networkAttemptCount = 0
        var deliveredToB: VoicePayload? = null

        val simulateSendOverNetwork: (VoicePayload) -> Boolean = { payload ->
            networkAttemptCount++
            println("  [Simulated Network] Transmitting attempt #$networkAttemptCount for seq ${payload.sequenceNumber}...")
            if (networkAttemptCount < 3) {
                println("  [Simulated Network] ❌ Packet dropped by interference!")
                false
            } else {
                println("  [Simulated Network] ✔ Packet successfully reached Device B!")
                deliveredToB = payload
                true
            }
        }

        // Initial transmission dropped
        simulateSendOverNetwork(finalPayload)
        check(deliveredToB == null, "Packet 1 should have been dropped")

        // Retransmit check #1 (dropped)
        Thread.sleep(60)
        val retransmitted1 = reliabilityA.checkAndRetransmit(simulateSendOverNetwork)
        check(retransmitted1 == 1, "Attempt 2 should have been retransmitted")
        check(deliveredToB == null, "Packet 2 should have been dropped")

        // Retransmit check #2 (delivered!)
        Thread.sleep(60)
        val retransmitted2 = reliabilityA.checkAndRetransmit(simulateSendOverNetwork)
        check(retransmitted2 == 1, "Attempt 3 should have been retransmitted")
        check(deliveredToB != null, "Packet 3 must be successfully received by Device B")

        // Receiver emits ACK
        val ackPayload = VoicePayload.createAck(devBId, deliveredToB!!.streamId, deliveredToB!!.sequenceNumber)
        val ackProcessed = reliabilityA.onAckReceived(ackPayload.streamId, ackPayload.sequenceNumber)
        check(ackProcessed, "ACK must clear pending message from queue")
        check(reliabilityA.getPendingCount() == 0, "No pending messages should remain after ACK")
        println("✓ Test 2 passed: stt_final survived 2 packet drops and succeeded via bounded retransmit")

        // ----------------------------------------------------
        // Test 3: Two simultaneous floor requests resolve deterministically
        // ----------------------------------------------------
        println("Test 3: Simultaneous floor requests resolve deterministically")
        val hubFloor = FloorController(devAId, isHub = true)
        val clientBFloor = FloorController(devBId, isHub = false)
        val clientCFloor = FloorController(devCId, isHub = false)

        val broadcastFrames = CopyOnWriteArrayList<VoicePayload>()
        val sendBroadcast: (VoicePayload) -> Unit = { payload ->
            broadcastFrames.add(payload)
            // Deliver broadcast to all participants
            hubFloor.handleIncomingPayload(payload, {})
            clientBFloor.handleIncomingPayload(payload, {})
            clientCFloor.handleIncomingPayload(payload, {})
        }

        // Client B and Client C request the floor at the exact same instant
        val reqB = VoicePayload.createFloorRequest(devBId)
        val reqC = VoicePayload.createFloorRequest(devCId)

        // Hub processes both simultaneous requests
        hubFloor.handleIncomingPayload(reqB, sendBroadcast)
        hubFloor.handleIncomingPayload(reqC, sendBroadcast)

        // Deterministic winner verification:
        val winnerDevId = if (devBId < devCId) devBId else devCId
        val loserDevId = if (devBId < devCId) devCId else devBId

        val winnerController = if (winnerDevId == devBId) clientBFloor else clientCFloor
        val loserController = if (winnerDevId == devBId) clientCFloor else clientBFloor

        check(winnerController.state is FloorController.FloorState.Granted, "Winner must be granted the floor")
        check(winnerController.isFloorHeldByMe, "Winner must have floorHeldByMe == true")
        check(!winnerController.shouldSuppressLocalMic, "Winner must NOT suppress mic")

        check(loserController.state is FloorController.FloorState.Busy, "Loser must see channel busy")
        check(!loserController.isFloorHeldByMe, "Loser must not have floor")
        check(loserController.shouldSuppressLocalMic, "Loser MUST suppress local mic capture while floor is held")

        println("  -> Winner was deterministically: $winnerDevId, Loser: $loserDevId (mic suppressed)")

        // Winner releases floor
        winnerController.releaseFloor(sendBroadcast)
        check(hubFloor.state is FloorController.FloorState.Open, "Hub floor must be Open after release")
        check(!loserController.shouldSuppressLocalMic, "Loser mic suppression must be lifted once floor is open")
        println("✓ Test 3 passed: Simultaneous floor requests resolved deterministically with mic suppression")

        println("=== Phase 2 Acceptance Criteria: ALL PASSED ===")
    }
}
