package `in`.itantra.mobile.transport

import `in`.itantra.mobile.transport.aware.IWifiAwareController
import `in`.itantra.mobile.transport.aware.WifiAwareListener
import `in`.itantra.mobile.transport.ble.BleListener
import `in`.itantra.mobile.transport.ble.IBleController
import `in`.itantra.mobile.transport.model.ConnectionState
import `in`.itantra.mobile.transport.model.PeerInfo
import `in`.itantra.mobile.transport.model.TransportType
import `in`.itantra.mobile.transport.p2p.IWifiDirectController
import `in`.itantra.mobile.transport.p2p.WifiDirectListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

class TransportManager(
    val localPeer: PeerInfo,
    private val bleController: IBleController,
    private val wifiDirectController: IWifiDirectController,
    private val wifiAwareController: IWifiAwareController? = null,
    private val isPluggedInOrHighCapability: Boolean = true
) {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val nearbyPeers = ConcurrentHashMap<String, PeerInfo>()

    var onMessageReceived: ((peerId: String, bytes: ByteArray) -> Unit)? = null
    var onPeerListChanged: ((peers: List<PeerInfo>) -> Unit)? = null

    @Volatile
    private var isDiscovering = false

    @Volatile
    private var activePeer: PeerInfo? = null

    @Volatile
    private var preferWifiAwareIfAvailable = true

    init {
        setupBleListener()
        setupWifiDirectListener()
        setupWifiAwareListener()
    }

    fun getNearbyPeers(): List<PeerInfo> = nearbyPeers.values.toList()

    fun startDiscovery() {
        isDiscovering = true
        if (_connectionState.value is ConnectionState.Disconnected) {
            _connectionState.value = ConnectionState.Scanning
        }
        bleController.startAdvertising(localPeer)
        bleController.startScanning()
    }

    fun stopDiscovery() {
        isDiscovering = false
        bleController.stopAdvertising()
        bleController.stopScanning()
    }

    fun connectToPeer(peerId: String): Boolean {
        val peer = nearbyPeers[peerId] ?: return false
        activePeer = peer

        // Opportunistic Wi-Fi Aware upgrade (Phase 1):
        if (preferWifiAwareIfAvailable && localPeer.wifiAwareSupported && peer.wifiAwareSupported && wifiAwareController != null && wifiAwareController.isSupported()) {
            val opened = wifiAwareController.openDataPath(peer)
            if (opened) {
                _connectionState.value = ConnectionState.WifiAwareConnected(peer)
                return true
            }
        }

        // Standard Primary Data Plane: Wi-Fi Direct using direct BLE-exchanged P2P address
        if (!peer.p2pDeviceAddress.isNullOrBlank()) {
            _connectionState.value = ConnectionState.NegotiatingWifiDirect(peer)
            wifiDirectController.connectPeer(peer.p2pDeviceAddress, isPluggedInOrHighCapability)
            return true
        }

        // If P2P address not yet available or unsupported, fall back to BLE GATT connection
        val bleOk = bleController.connectGatt(peerId)
        if (bleOk) {
            _connectionState.value = ConnectionState.BleConnected(peer, degraded = true)
            return true
        }

        return false
    }

    fun sendToPeer(peerId: String, bytes: ByteArray): Boolean {
        val currentState = _connectionState.value
        val peer = nearbyPeers[peerId] ?: activePeer

        // 1. If Wi-Fi Aware is connected and active:
        if (currentState is ConnectionState.WifiAwareConnected && wifiAwareController != null && wifiAwareController.isConnected(peerId)) {
            val sent = wifiAwareController.sendData(peerId, bytes)
            if (sent) return true
        }

        // 2. Primary Data Plane: Wi-Fi Direct
        if ((currentState is ConnectionState.WifiDirectConnected || wifiDirectController.isConnected()) && wifiDirectController.isConnected()) {
            val sent = wifiDirectController.sendData(peerId, bytes)
            if (sent) return true
            // If Wi-Fi Direct send failed, fall back to BLE degraded mode immediately
            if (peer != null) {
                _connectionState.value = ConnectionState.FallbackToBle(peer, "Wi-Fi Direct link send failed")
            }
        }

        // 3. Degraded Fallback: BLE GATT characteristic transfer
        if (peer != null) {
            val bleSent = bleController.sendBytesOverGatt(peerId, bytes)
            if (bleSent) {
                if (currentState !is ConnectionState.FallbackToBle && currentState !is ConnectionState.BleConnected) {
                    _connectionState.value = ConnectionState.FallbackToBle(peer, "Sent via BLE fallback")
                }
                return true
            }
        }

        return false
    }

    fun broadcast(bytes: ByteArray): Boolean {
        val currentState = _connectionState.value
        var success = false

        // 1. Wi-Fi Direct broadcast (hub / Group Owner acts as coordinator)
        if (currentState is ConnectionState.WifiDirectConnected || wifiDirectController.isConnected()) {
            if (wifiDirectController.broadcastData(bytes)) {
                success = true
            }
        }

        // 2. Wi-Fi Aware or BLE fallback broadcast to all known active peers
        if (!success) {
            val peers = nearbyPeers.values.toList()
            for (p in peers) {
                val ok = sendToPeer(p.deviceId, bytes)
                if (ok) success = true
            }
        }

        return success
    }

    fun disconnect() {
        activePeer?.let { peer ->
            wifiDirectController.disconnectGroup()
            wifiAwareController?.closeDataPath(peer.deviceId)
            bleController.disconnectGatt(peer.deviceId)
        }
        activePeer = null
        _connectionState.value = if (isDiscovering) ConnectionState.Scanning else ConnectionState.Disconnected
    }

    fun close() {
        stopDiscovery()
        disconnect()
        bleController.close()
        wifiDirectController.close()
        wifiAwareController?.close()
    }

    private fun setupBleListener() {
        bleController.setListener(object : BleListener {
            override fun onPeerDiscovered(peer: PeerInfo) {
                updatePeer(peer)
            }

            override fun onPeerLost(peerId: String) {
                val lost = nearbyPeers.remove(peerId)
                if (lost != null) {
                    onPeerListChanged?.invoke(getNearbyPeers())
                    if (activePeer?.deviceId == peerId) {
                        disconnect()
                    }
                }
            }

            override fun onHandshakeReceived(peer: PeerInfo) {
                updatePeer(peer)
                if (activePeer == null && isDiscovering && !peer.p2pDeviceAddress.isNullOrBlank()) {
                    connectToPeer(peer.deviceId)
                }
            }

            override fun onHeartbeat(peerId: String) {
                val peer = nearbyPeers[peerId]
                if (peer != null) {
                    peer.lastSeenEpochMs = System.currentTimeMillis()
                    val state = _connectionState.value
                    if (state is ConnectionState.FallbackToBle && !wifiDirectController.isConnected() && !peer.p2pDeviceAddress.isNullOrBlank()) {
                        _connectionState.value = ConnectionState.NegotiatingWifiDirect(peer)
                        wifiDirectController.connectPeer(peer.p2pDeviceAddress, isPluggedInOrHighCapability)
                    }
                }
            }

            override fun onBytesReceived(peerId: String, bytes: ByteArray) {
                onMessageReceived?.invoke(peerId, bytes)
            }

            override fun onConnectionStateChanged(peerId: String, connected: Boolean) {
                if (!connected && activePeer?.deviceId == peerId) {
                    val current = _connectionState.value
                    if (current is ConnectionState.BleConnected || current is ConnectionState.FallbackToBle) {
                        _connectionState.value = if (isDiscovering) ConnectionState.Scanning else ConnectionState.Disconnected
                    }
                }
            }
        })
    }

    private fun setupWifiDirectListener() {
        wifiDirectController.setListener(object : WifiDirectListener {
            override fun onGroupFormed(isGroupOwner: Boolean, goAddress: String, connectedClients: List<String>) {
                val peer = activePeer ?: nearbyPeers.values.firstOrNull()
                if (peer != null) {
                    peer.activeTransport = TransportType.WIFI_DIRECT
                    _connectionState.value = ConnectionState.WifiDirectConnected(peer, isGroupOwner, goAddress)
                }
            }

            override fun onGroupTerminated(reason: String) {
                val peer = activePeer
                if (peer != null && nearbyPeers.containsKey(peer.deviceId)) {
                    peer.activeTransport = TransportType.BLE
                    _connectionState.value = ConnectionState.FallbackToBle(peer, "Wi-Fi Direct group terminated: $reason")
                } else {
                    _connectionState.value = if (isDiscovering) ConnectionState.Scanning else ConnectionState.Disconnected
                }
            }

            override fun onDataReceived(senderId: String, bytes: ByteArray) {
                onMessageReceived?.invoke(senderId, bytes)
            }

            override fun onConnectionFailed(reason: String) {
                val peer = activePeer
                if (peer != null && nearbyPeers.containsKey(peer.deviceId)) {
                    peer.activeTransport = TransportType.BLE
                    _connectionState.value = ConnectionState.FallbackToBle(peer, "Wi-Fi Direct connect failed: $reason")
                }
            }
        })
    }

    private fun setupWifiAwareListener() {
        wifiAwareController?.setListener(object : WifiAwareListener {
            override fun onAwareSessionStarted() {}

            override fun onDataPathOpened(peerId: String, localAddress: String, isServer: Boolean) {
                val peer = nearbyPeers[peerId] ?: activePeer
                if (peer != null) {
                    peer.activeTransport = TransportType.WIFI_AWARE
                    _connectionState.value = ConnectionState.WifiAwareConnected(peer)
                }
            }

            override fun onDataPathClosed(peerId: String, reason: String) {
                val peer = nearbyPeers[peerId] ?: activePeer
                if (peer != null && !peer.p2pDeviceAddress.isNullOrBlank()) {
                    _connectionState.value = ConnectionState.NegotiatingWifiDirect(peer)
                    wifiDirectController.connectPeer(peer.p2pDeviceAddress, isPluggedInOrHighCapability)
                }
            }

            override fun onDataReceived(senderId: String, bytes: ByteArray) {
                onMessageReceived?.invoke(senderId, bytes)
            }

            override fun onAwareFailed(reason: String) {
                val peer = activePeer
                if (peer != null && !peer.p2pDeviceAddress.isNullOrBlank()) {
                    _connectionState.value = ConnectionState.NegotiatingWifiDirect(peer)
                    wifiDirectController.connectPeer(peer.p2pDeviceAddress, isPluggedInOrHighCapability)
                }
            }
        })
    }

    private fun updatePeer(peer: PeerInfo) {
        val existing = nearbyPeers[peer.deviceId]
        if (existing == null) {
            nearbyPeers[peer.deviceId] = peer
            onPeerListChanged?.invoke(getNearbyPeers())
        } else {
            existing.lastSeenEpochMs = System.currentTimeMillis()
            existing.rssi = peer.rssi
            if (!peer.p2pDeviceAddress.isNullOrBlank()) {
                existing.activeTransport = peer.activeTransport
            }
            onPeerListChanged?.invoke(getNearbyPeers())
        }
    }
}
