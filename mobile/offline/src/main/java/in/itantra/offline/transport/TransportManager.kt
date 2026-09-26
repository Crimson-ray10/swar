package `in`.itantra.offline.transport

import `in`.itantra.offline.session.OperatingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Radio { BLE, WIFI_DIRECT, WIFI_AWARE }
enum class ConnectionState { STOPPED, DISCOVERING, CONNECTING, CONNECTED, DEGRADED, RECONNECTING }

interface RadioController : AutoCloseable {
    val radio: Radio
    fun startDiscovery()
    fun stopDiscovery()
    fun available(peerId: String): Boolean
    /** Enqueue without blocking another direction. False means no link or bounded queue full. */
    fun send(peerId: String, bytes: ByteArray): Boolean
}

/** Pure routing policy. Only encrypted application envelopes enter this public send API. */
class TransportManager(private val controllers: List<RadioController>) : AutoCloseable {
    private val state = MutableStateFlow(ConnectionState.STOPPED)
    val connectionState: StateFlow<ConnectionState> = state
    var mode = OperatingMode.WALKIE_TALKIE
    var onMessageReceived: (String, ByteArray) -> Unit = { _, _ -> }
    var onPeerListChanged: (Set<String>) -> Unit = {}
    private var peers = emptySet<String>()
    private var closed = false
    fun startDiscovery() {
        check(!closed)
        controllers.filter { it.radio == Radio.BLE }.forEach(RadioController::startDiscovery)
        state.value = ConnectionState.DISCOVERING
    }
    fun stopDiscovery() { controllers.forEach(RadioController::stopDiscovery); state.value = ConnectionState.STOPPED }
    fun updatePeers(value: Set<String>) { peers = value.toSet(); onPeerListChanged(peers); refresh() }
    fun preferred(peerId: String): RadioController? = controllers.filter { it.available(peerId) }
        .filter { mode == OperatingMode.CONVERSATION || it.radio != Radio.WIFI_AWARE }
        .minByOrNull { when (it.radio) { Radio.WIFI_AWARE -> 0; Radio.WIFI_DIRECT -> 1; Radio.BLE -> 2 } }
    fun sendToPeer(peerId: String, bytes: ByteArray): Boolean {
        require(bytes.firstOrNull() == 3.toByte()) { "Application traffic must be encrypted" }
        val preferred = preferred(peerId) ?: return false
        if (preferred.send(peerId, bytes)) return true
        return preferred.radio != Radio.BLE && controllers.firstOrNull { it.radio == Radio.BLE }?.send(peerId, bytes) == true
    }
    fun broadcast(encodeForPeer: (String) -> ByteArray): Map<String, Boolean> = peers.associateWith { sendToPeer(it, encodeForPeer(it)) }
    fun refresh() {
        if (closed) return
        state.value = when {
            peers.isEmpty() -> ConnectionState.DISCOVERING
            peers.any { preferred(it)?.radio in setOf(Radio.WIFI_DIRECT, Radio.WIFI_AWARE) } -> ConnectionState.CONNECTED
            peers.any { preferred(it)?.radio == Radio.BLE } -> ConnectionState.DEGRADED
            else -> ConnectionState.RECONNECTING
        }
    }
    override fun close() { closed = true; controllers.forEach(RadioController::close); peers = emptySet(); state.value = ConnectionState.STOPPED }
}
