package `in`.itantra.mobile.transport.ble

import `in`.itantra.mobile.transport.model.PeerInfo

interface BleListener {
    fun onPeerDiscovered(peer: PeerInfo)
    fun onPeerLost(peerId: String)
    fun onHandshakeReceived(peer: PeerInfo)
    fun onHeartbeat(peerId: String)
    fun onBytesReceived(peerId: String, bytes: ByteArray)
    fun onConnectionStateChanged(peerId: String, connected: Boolean)
}

interface IBleController {
    fun setListener(listener: BleListener?)
    fun startAdvertising(localPeer: PeerInfo)
    fun stopAdvertising()
    fun startScanning()
    fun stopScanning()
    fun connectGatt(peerId: String): Boolean
    fun disconnectGatt(peerId: String)
    fun sendBytesOverGatt(peerId: String, bytes: ByteArray): Boolean
    fun isAdvertising(): Boolean
    fun isScanning(): Boolean
    fun getConnectedPeers(): List<PeerInfo>
    fun close()
}
