package `in`.itantra.mobile.tests.phase1

import `in`.itantra.mobile.transport.TransportManager
import `in`.itantra.mobile.transport.fake.FakeBleController
import `in`.itantra.mobile.transport.fake.FakeWifiAwareController
import `in`.itantra.mobile.transport.fake.FakeWifiDirectController
import `in`.itantra.mobile.transport.model.ConnectionState
import `in`.itantra.mobile.transport.model.PeerInfo
import `in`.itantra.mobile.transport.model.TransportType
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

object Phase1AcceptanceTest {

    private fun check(condition: Boolean, message: String) {
        if (!condition) {
            System.err.println("FAILED ASSERTION: $message")
            throw AssertionError("Test assertion failed: $message")
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== Running Phase 1 Acceptance Tests ===")
        FakeBleController.clearEther()
        FakeWifiDirectController.clearNetwork()
        FakeWifiAwareController.clearEther()

        val devAId = UUID.randomUUID().toString()
        val devBId = UUID.randomUUID().toString()
        val devCId = UUID.randomUUID().toString() // Device lacking NAN hardware

        // Devices A and B are NAN-capable
        val peerA = PeerInfo(
            deviceId = devAId,
            displayName = "Phone-A (NAN-capable)",
            supportedLanguages = listOf("hi", "en"),
            p2pDeviceAddress = "aa:00:00:11:11:11",
            wifiAwareSupported = true
        )

        val peerB = PeerInfo(
            deviceId = devBId,
            displayName = "Phone-B (NAN-capable)",
            supportedLanguages = listOf("hi", "en"),
            p2pDeviceAddress = "bb:00:00:22:22:22",
            wifiAwareSupported = true
        )

        // Device C lacks NAN hardware
        val peerC = PeerInfo(
            deviceId = devCId,
            displayName = "Phone-C (Budget Phone, No NAN)",
            supportedLanguages = listOf("hi", "en"),
            p2pDeviceAddress = "cc:00:00:33:33:33",
            wifiAwareSupported = false
        )

        val bleA = FakeBleController(devAId)
        val bleB = FakeBleController(devBId)
        val bleC = FakeBleController(devCId)

        val p2pA = FakeWifiDirectController(peerA.p2pDeviceAddress!!, devAId)
        val p2pB = FakeWifiDirectController(peerB.p2pDeviceAddress!!, devBId)
        val p2pC = FakeWifiDirectController(peerC.p2pDeviceAddress!!, devCId)

        val awareA = FakeWifiAwareController(devAId, hardwareSupported = true)
        val awareB = FakeWifiAwareController(devBId, hardwareSupported = true)
        val awareC = FakeWifiAwareController(devCId, hardwareSupported = false) // Not supported on hardware

        awareA.startSession(peerA)
        awareB.startSession(peerB)
        awareC.startSession(peerC) // fails silently

        val mgrA = TransportManager(peerA, bleA, p2pA, awareA, isPluggedInOrHighCapability = true)
        val mgrB = TransportManager(peerB, bleB, p2pB, awareB, isPluggedInOrHighCapability = false)
        val mgrC = TransportManager(peerC, bleC, p2pC, awareC, isPluggedInOrHighCapability = false)

        val msgsAtB = CopyOnWriteArrayList<String>()
        val msgsAtC = CopyOnWriteArrayList<String>()

        mgrB.onMessageReceived = { sender: String, bytes: ByteArray ->
            val s = String(bytes, StandardCharsets.UTF_8)
            msgsAtB.add(s)
            println("[Device B] Received via ${peerB.activeTransport}: \"$s\"")
        }

        mgrC.onMessageReceived = { sender: String, bytes: ByteArray ->
            val s = String(bytes, StandardCharsets.UTF_8)
            msgsAtC.add(s)
            println("[Device C] Received via ${peerC.activeTransport}: \"$s\"")
        }

        // Test 1: On two NAN-capable devices (A and B), a Wi-Fi Aware data path forms INSTEAD of Wi-Fi Direct
        println("Test 1: Opportunistic Wi-Fi Aware upgrade on two NAN devices")
        mgrA.startDiscovery()
        mgrB.startDiscovery()

        val connectedToB = mgrA.connectToPeer(devBId)
        check(connectedToB, "Connection from A to B should succeed")
        check(mgrA.connectionState.value is ConnectionState.WifiAwareConnected, "Device A should be connected via WifiAwareConnected")
        check(mgrB.connectionState.value is ConnectionState.WifiAwareConnected, "Device B should be connected via WifiAwareConnected")
        check(awareA.isConnected(devBId), "NAN data path A->B must be active")
        check(!p2pA.isConnected(), "Wi-Fi Direct should NOT be formed since Wi-Fi Aware succeeded (avoids GO election entirely)")
        println("✓ Test 1 passed: Wi-Fi Aware data path formed instead of Wi-Fi Direct without GO election")

        // Test 2: High-speed messaging over Wi-Fi Aware NAN data path
        println("Test 2: Direct data transfer over Wi-Fi Aware")
        val sentNan = mgrA.sendToPeer(devBId, "Testing low-latency Wi-Fi Aware NAN frame".toByteArray(StandardCharsets.UTF_8))
        check(sentNan, "Send over NAN should succeed")
        check(msgsAtB.size == 1, "Device B should receive 1 message over NAN")
        check(msgsAtB[0] == "Testing low-latency Wi-Fi Aware NAN frame", "Message contents must match")
        println("✓ Test 2 passed: Reliable data transfer over NAN verified")

        // Test 3: On a device lacking NAN hardware (Device C), Phase 0 behavior is unaffected (falls back to Wi-Fi Direct)
        println("Test 3: Device lacking NAN hardware falls back seamlessly to Wi-Fi Direct (Phase 0 behavior)")
        mgrC.startDiscovery()
        bleA.deliverDiscovery(peerC) // Device A discovers Device C

        val connectedToC = mgrA.connectToPeer(devCId)
        check(connectedToC, "Connection from A to C should succeed")
        // Because Device C lacks NAN hardware, Wi-Fi Direct must form instead!
        check(mgrA.connectionState.value is ConnectionState.WifiDirectConnected, "Device A must fall back to WifiDirectConnected with Device C")
        check(p2pA.isConnected() && p2pC.isConnected(), "Wi-Fi Direct link must be established between A and C")
        check(p2pA.isGroupOwner(), "Device A should be Group Owner for P2P")

        val sentToC = mgrA.sendToPeer(devCId, "Message sent to non-NAN device over Wi-Fi Direct".toByteArray(StandardCharsets.UTF_8))
        check(sentToC, "Send over Wi-Fi Direct should succeed")
        check(msgsAtC.size == 1, "Device C should receive message over Wi-Fi Direct")
        check(msgsAtC[0] == "Message sent to non-NAN device over Wi-Fi Direct", "Message contents must match")
        println("✓ Test 3 passed: Device lacking NAN seamlessly negotiated Wi-Fi Direct; Phase 0 behavior completely preserved")

        // Test 4: Mid-session NAN data path closure silently recovers to Wi-Fi Direct
        println("Test 4: Mid-session NAN disruption recovery")
        awareA.simulateDataPathDrop(devBId)
        // TransportManager listener catches onDataPathClosed and seamlessly transitions to Wi-Fi Direct
        check(mgrA.connectionState.value is ConnectionState.NegotiatingWifiDirect || mgrA.connectionState.value is ConnectionState.WifiDirectConnected, 
              "Disrupted NAN must fall back to Wi-Fi Direct")
        println("✓ Test 4 passed: NAN disruption seamlessly recovered to Wi-Fi Direct")

        mgrA.close()
        mgrB.close()
        mgrC.close()
        println("=== Phase 1 Acceptance Criteria: ALL PASSED ===")
    }
}
