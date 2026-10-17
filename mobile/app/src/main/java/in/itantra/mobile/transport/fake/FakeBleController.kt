package `in`.itantra.mobile.transport.fake

import `in`.itantra.mobile.transport.ble.BleListener
import `in`.itantra.mobile.transport.ble.IBleController
import `in`.itantra.mobile.transport.model.PeerInfo
import java.util.concurrent.ConcurrentHashMap

class FakeBleController(val localDeviceId: String) : IBleController {
    companion object {
        private val ether = ConcurrentHashMap<String, FakeBleController>()

        fun clearEther() {
            ether.clear()
        }
    }

    private var listener: BleListener? = null
    private var advertisingPeer: PeerInfo? = null
    private var scanning = false
    private val connectedPeers = ConcurrentHashMap<String, PeerInfo>()

    init {
        ether[localDeviceId] = this
    }

    override fun setListener(listener: BleListener?) {
        this.listener = listener
    }

    override fun startAdvertising(localPeer: PeerInfo) {
        this.advertisingPeer = localPeer
        for ((id, other) in ether) {
            if (id != localDeviceId && other.scanning) {
                other.deliverDiscovery(localPeer)
            }
        }
    }

    override fun stopAdvertising() {
        advertisingPeer = null
    }

    override fun startScanning() {
        scanning = true
        for ((id, other) in ether) {
            val peer = other.advertisingPeer
            if (id != localDeviceId && peer != null) {
                deliverDiscovery(peer)
            }
        }
    }

    override fun stopScanning() {
        scanning = false
    }

    override fun connectGatt(peerId: String): Boolean {
        val target = ether[peerId] ?: return false
        val peerInfo = target.advertisingPeer ?: return false
        connectedPeers[peerId] = peerInfo
        target.connectedPeers[localDeviceId] = this.advertisingPeer ?: PeerInfo(localDeviceId, "Dev-$localDeviceId")
        listener?.onConnectionStateChanged(peerId, true)
        target.listener?.onConnectionStateChanged(localDeviceId, true)
        return true
    }

    override fun disconnectGatt(peerId: String) {
        connectedPeers.remove(peerId)
        ether[peerId]?.connectedPeers?.remove(localDeviceId)
        listener?.onConnectionStateChanged(peerId, false)
        ether[peerId]?.listener?.onConnectionStateChanged(localDeviceId, false)
    }

    override fun sendBytesOverGatt(peerId: String, bytes: ByteArray): Boolean {
        val target = ether[peerId] ?: return false
        target.deliverBytes(localDeviceId, bytes.clone())
        return true
    }

    override fun isAdvertising(): Boolean = advertisingPeer != null
    override fun isScanning(): Boolean = scanning
    override fun getConnectedPeers(): List<PeerInfo> = connectedPeers.values.toList()

    override fun close() {
        stopAdvertising()
        stopScanning()
        ether.remove(localDeviceId)
    }

    fun deliverDiscovery(peer: PeerInfo) {
        listener?.onPeerDiscovered(peer)
        listener?.onHandshakeReceived(peer)
    }

    fun deliverHeartbeat(peerId: String) {
        listener?.onHeartbeat(peerId)
    }

    fun deliverPeerLost(peerId: String) {
        listener?.onPeerLost(peerId)
    }

    fun deliverBytes(fromPeerId: String, bytes: ByteArray) {
        listener?.onBytesReceived(fromPeerId, bytes)
    }
}
