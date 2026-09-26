package `in`.itantra.offline

import `in`.itantra.offline.transport.*
import `in`.itantra.offline.session.OperatingMode
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TransportTest {
    private class Fake(override val radio: Radio, var up: Boolean = true) : RadioController {
        var accepts = true; var sent = 0
        override fun startDiscovery() = Unit
        override fun stopDiscovery() = Unit
        override fun available(peerId: String) = up
        override fun send(peerId: String, bytes: ByteArray): Boolean { if (accepts && up) sent++; return accepts && up }
        override fun close() { up = false }
    }
    @Test fun radioSelectionAndBackpressureFallbackAreIndependentOfHardware() {
        val ble = Fake(Radio.BLE); val wifi = Fake(Radio.WIFI_DIRECT); val aware = Fake(Radio.WIFI_AWARE)
        val manager = TransportManager(listOf(ble, wifi, aware)); manager.updatePeers(setOf("peer"))
        assertEquals(Radio.WIFI_DIRECT, manager.preferred("peer")!!.radio)
        manager.mode = OperatingMode.CONVERSATION
        assertEquals(Radio.WIFI_AWARE, manager.preferred("peer")!!.radio)
        aware.accepts = false
        assertTrue(manager.sendToPeer("peer", byteArrayOf(3, 4))); assertEquals(1, ble.sent)
        wifi.up = false; aware.up = false; manager.refresh()
        assertEquals(ConnectionState.DEGRADED, manager.connectionState.value)
        assertThrows(IllegalArgumentException::class.java) { manager.sendToPeer("peer", byteArrayOf(0)) }
    }
    @Test fun actualSocketsDeliverSimultaneousTrafficInBothDirections() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val aId = UUID.randomUUID(); val bId = UUID.randomUUID()
        val receivedA = ConcurrentHashMap.newKeySet<Int>(); val receivedB = ConcurrentHashMap.newKeySet<Int>()
        val a = SocketLinks(aId, scope, { it == bId.toString() }, { _, challenge -> challenge }, { _, challenge, proof -> challenge.contentEquals(proof) }, { _, frame -> receivedA.add(frame[0].toInt() and 255) }, {})
        val b = SocketLinks(bId, scope, { it == aId.toString() }, { _, challenge -> challenge }, { _, challenge, proof -> challenge.contentEquals(proof) }, { _, frame -> receivedB.add(frame[0].toInt() and 255) }, {})
        val server = ServerSocket(0)
        try {
            val accept = scope.launch { b.attach(server.accept()) }
            a.attach(Socket("127.0.0.1", server.localPort)); accept.join()
            withTimeout(5000) { while (!a.available(bId.toString()) || !b.available(aId.toString())) delay(10) }
            val sendA = launch { repeat(200) { n -> while (!a.send(bId.toString(), byteArrayOf(n.toByte()))) delay(1) } }
            val sendB = launch { repeat(200) { n -> while (!b.send(aId.toString(), byteArrayOf(n.toByte()))) delay(1) } }
            sendA.join(); sendB.join()
            withTimeout(5000) { while (receivedA.size != 200 || receivedB.size != 200) delay(10) }
            assertEquals((0 until 200).toSet(), receivedA); assertEquals((0 until 200).toSet(), receivedB)
        } finally { a.close(); b.close(); server.close(); scope.cancel() }
    }
}
