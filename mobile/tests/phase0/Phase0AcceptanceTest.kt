package `in`.itantra.mobile.tests.phase0

import `in`.itantra.mobile.transport.TransportManager
import `in`.itantra.mobile.transport.fake.FakeBleController
import `in`.itantra.mobile.transport.fake.FakeWifiDirectController
import `in`.itantra.mobile.transport.model.ConnectionState
import `in`.itantra.mobile.transport.model.PeerInfo
import `in`.itantra.mobile.transport.model.TransportType
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

object Phase0AcceptanceTest {

    private fun check(condition: Boolean, message: String) {
        if (!condition) {
            System.err.println("FAILED ASSERTION: $message")
            throw AssertionError("Test assertion failed: $message")
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== Running Phase 0 Acceptance Tests ===")
        FakeBleController.clearEther()
        FakeWifiDirectController.clearNetwork()

        val devAId = UUID.randomUUID().toString()
        val devBId = UUID.randomUUID().toString()

        val peerA = PeerInfo(
            deviceId = devAId,
            displayName = "Phone-A (Hub)",
            supportedLanguages = listOf("hi", "en", "mr"),
            p2pDeviceAddress = "aa:bb:cc:11:22:33",
            wifiAwareSupported = false
        )

        val peerB = PeerInfo(
            deviceId = devBId,
            displayName = "Phone-B (Client)",
            supportedLanguages = listOf("hi", "en"),
            p2pDeviceAddress = "dd:ee:ff:44:55:66",
            wifiAwareSupported = false
        )

        val bleA = FakeBleController(devAId)
        val bleB = FakeBleController(devBId)

        val p2pA = FakeWifiDirectController(peerA.p2pDeviceAddress!!, devAId)
        val p2pB = FakeWifiDirectController(peerB.p2pDeviceAddress!!, devBId)

        val mgrA = TransportManager(peerA, bleA, p2pA, isPluggedInOrHighCapability = true)
        val mgrB = TransportManager(peerB, bleB, p2pB, isPluggedInOrHighCapability = false)

        val msgsReceivedAtA = CopyOnWriteArrayList<Pair<String, String>>()
        val msgsReceivedAtB = CopyOnWriteArrayList<Pair<String, String>>()

        mgrA.onMessageReceived = { sender: String, bytes: ByteArray ->
            val str = String(bytes, StandardCharsets.UTF_8)
            msgsReceivedAtA.add(Pair(sender, str))
            println("[Device A] Received from $sender: \"$str\"")
        }

        mgrB.onMessageReceived = { sender: String, bytes: ByteArray ->
            val str = String(bytes, StandardCharsets.UTF_8)
            msgsReceivedAtB.add(Pair(sender, str))
            println("[Device B] Received from $sender: \"$str\"")
        }

        // Test 1: Handshake serialization roundtrip
        println("Test 1: BLE Handshake binary encoding/decoding")
        val handshakeBytes = peerA.toHandshakeBytes()
        val decodedPeerA = PeerInfo.fromHandshakeBytes(handshakeBytes)
        check(decodedPeerA != null, "Decoded peer should not be null")
        check(decodedPeerA!!.deviceId == peerA.deviceId, "UUID should match")
        check(decodedPeerA.displayName == peerA.displayName, "Display name should match")
        check(decodedPeerA.supportedLanguages == peerA.supportedLanguages, "Languages should match")
        check(decodedPeerA.p2pDeviceAddress == peerA.p2pDeviceAddress, "P2P address should match")
        println("✓ Test 1 passed: Handshake serialization verified")

        // Test 2: BLE Discovery and Handshake Exchange
        println("Test 2: BLE Discovery & Handshake")
        mgrA.startDiscovery()
        mgrB.startDiscovery()

        check(mgrA.connectionState.value !is ConnectionState.Disconnected, "Manager A should be scanning")
        check(mgrB.connectionState.value !is ConnectionState.Disconnected, "Manager B should be scanning")

        val peersKnownToA = mgrA.getNearbyPeers()
        check(peersKnownToA.any { it.deviceId == devBId }, "Device A should have discovered Device B")
        println("✓ Test 2 passed: Peer discovery over BLE completed")

        // Test 3: Wi-Fi Direct Negotiation & GO Election Biasing
        println("Test 3: Wi-Fi Direct Direct Negotiation & GO Election")
        check(p2pA.isConnected(), "Wi-Fi Direct on Device A should be connected")
        check(p2pB.isConnected(), "Wi-Fi Direct on Device B should be connected")
        check(p2pA.isGroupOwner(), "Device A (high capability) should be biased as Group Owner")
        check(!p2pB.isGroupOwner(), "Device B should be client")
        check(mgrA.connectionState.value is ConnectionState.WifiDirectConnected, "Device A state should be WifiDirectConnected")
        check(mgrB.connectionState.value is ConnectionState.WifiDirectConnected, "Device B state should be WifiDirectConnected")
        println("✓ Test 3 passed: Wi-Fi Direct negotiated with GO intent biasing")

        // Test 4: Reliable Data Exchange over Wi-Fi Direct
        println("Test 4: Reliable data plane message exchange")
        val sentAtoB = mgrA.sendToPeer(devBId, "Hello from Phone A over Wi-Fi Direct!".toByteArray(StandardCharsets.UTF_8))
        check(sentAtoB, "Send from A to B should succeed")
        check(msgsReceivedAtB.size == 1, "Device B should have received 1 message")
        check(msgsReceivedAtB[0].second == "Hello from Phone A over Wi-Fi Direct!", "Message content must match")

        val sentBtoA = mgrB.sendToPeer(devAId, "Namaste Phone A, Phone B received you clearly!".toByteArray(StandardCharsets.UTF_8))
        check(sentBtoA, "Send from B to A should succeed")
        check(msgsReceivedAtA.size == 1, "Device A should have received 1 message")
        check(msgsReceivedAtA[0].second == "Namaste Phone A, Phone B received you clearly!", "Message content must match")
        println("✓ Test 4 passed: Bidirectional Wi-Fi Direct messaging verified")

        // Test 5: Manual Wi-Fi Toggle -> BLE Degraded Fallback without session destruction
        println("Test 5: Wi-Fi toggle forcing BLE fallback")
        p2pB.simulateWifiToggle(false)
        check(!p2pB.isConnected(), "Wi-Fi Direct should be disconnected on Phone B")

        check(mgrB.getNearbyPeers().isNotEmpty(), "BLE presence must keep peer in list")
        check(mgrB.connectionState.value is ConnectionState.FallbackToBle, "Device B should fall back to BLE degraded mode")

        val sentDuringWifiOutage = mgrB.sendToPeer(devAId, "Emergency text sent over BLE fallback during Wi-Fi outage".toByteArray(StandardCharsets.UTF_8))
        check(sentDuringWifiOutage, "BLE fallback send should succeed")
        check(msgsReceivedAtA.size == 2, "Device A should have received message over BLE")
        check(msgsReceivedAtA[1].second == "Emergency text sent over BLE fallback during Wi-Fi outage", "Fallback message content must match")
        println("✓ Test 5 passed: BLE degraded fallback operational during Wi-Fi outage")

        // Test 6: Wi-Fi Restored & Automatic Re-negotiation via BLE presence heartbeat
        println("Test 6: Wi-Fi restored & auto re-negotiation")
        p2pB.simulateWifiToggle(true)
        bleB.deliverHeartbeat(devAId)
        check(p2pB.isConnected(), "Wi-Fi Direct should re-connect")
        check(mgrB.connectionState.value is ConnectionState.WifiDirectConnected, "Device B should return to WifiDirectConnected")

        val sentAfterReconnect = mgrB.sendToPeer(devAId, "Back on Wi-Fi Direct high-speed data plane!".toByteArray(StandardCharsets.UTF_8))
        check(sentAfterReconnect, "Send after reconnect should succeed")
        check(msgsReceivedAtA.size == 3, "Device A should receive reconnected message")
        println("✓ Test 6 passed: Auto-renegotiation back to Wi-Fi Direct verified")

        // Test 7: Group Owner Disconnect and Recovery
        println("Test 7: Group Owner disconnect and recovery")
        p2pA.simulateGoDisconnect()
        check(!p2pA.isConnected(), "GO link should be disconnected")
        check(mgrA.connectionState.value is ConnectionState.FallbackToBle, "Device A should fall back to BLE")
        
        // Re-establish
        p2pA.initialize()
        p2pB.initialize()
        mgrA.connectToPeer(devBId)
        check(p2pA.isConnected() && p2pB.isConnected(), "Wi-Fi Direct should re-form")
        println("✓ Test 7 passed: GO disconnect and recovery survived")

        // Cleanup
        mgrA.close()
        mgrB.close()
        println("=== Phase 0 Acceptance Criteria: ALL PASSED ===")
    }
}
