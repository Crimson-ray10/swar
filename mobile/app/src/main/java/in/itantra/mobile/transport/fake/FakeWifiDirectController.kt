package `in`.itantra.mobile.transport.fake

import `in`.itantra.mobile.transport.p2p.IWifiDirectController
import `in`.itantra.mobile.transport.p2p.WifiDirectListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class FakeWifiDirectController(
    val localP2pAddress: String,
    val localDeviceId: String
) : IWifiDirectController {
    companion object {
        private val network = ConcurrentHashMap<String, FakeWifiDirectController>()

        fun clearNetwork() {
            network.clear()
        }
    }

    private var listener: WifiDirectListener? = null
    private var connected = false
    private var isGo = false
    private var goAddress: String? = null
    private val connectedClients = CopyOnWriteArrayList<String>()
    private var wifiEnabled = true

    init {
        network[localP2pAddress] = this
    }

    override fun setListener(listener: WifiDirectListener?) {
        this.listener = listener
    }

    override fun initialize(): Boolean = true

    override fun connectPeer(p2pDeviceAddress: String, isHighCapabilityOrPluggedIn: Boolean) {
        if (!wifiEnabled) {
            listener?.onConnectionFailed("Wi-Fi is disabled")
            return
        }
        val target = network[p2pDeviceAddress]
        if (target == null || !target.wifiEnabled) {
            listener?.onConnectionFailed("Target peer unavailable on P2P")
            return
        }

        if (isHighCapabilityOrPluggedIn) {
            this.isGo = true
            this.goAddress = "192.168.49.1"
            this.connected = true
            this.connectedClients.add(target.localDeviceId)

            target.isGo = false
            target.goAddress = "192.168.49.1"
            target.connected = true
            target.connectedClients.clear()

            this.listener?.onGroupFormed(true, "192.168.49.1", listOf(target.localDeviceId))
            target.listener?.onGroupFormed(false, "192.168.49.1", emptyList())
        } else {
            target.isGo = true
            target.goAddress = "192.168.49.1"
            target.connected = true
            target.connectedClients.add(this.localDeviceId)

            this.isGo = false
            this.goAddress = "192.168.49.1"
            this.connected = true
            this.connectedClients.clear()

            target.listener?.onGroupFormed(true, "192.168.49.1", listOf(this.localDeviceId))
            this.listener?.onGroupFormed(false, "192.168.49.1", emptyList())
        }
    }

    override fun disconnectGroup() {
        connected = false
        isGo = false
        goAddress = null
        connectedClients.clear()
        listener?.onGroupTerminated("User disconnected group")
    }

    override fun sendData(peerId: String, bytes: ByteArray): Boolean {
        if (!wifiEnabled || !connected) return false
        for ((_, ctrl) in network) {
            if (ctrl.localDeviceId == peerId && ctrl.connected && ctrl.wifiEnabled) {
                ctrl.deliverData(this.localDeviceId, bytes.clone())
                return true
            }
        }
        return false
    }

    override fun broadcastData(bytes: ByteArray): Boolean {
        if (!wifiEnabled || !connected) return false
        var sentCount = 0
        for ((_, ctrl) in network) {
            if (ctrl.localDeviceId != this.localDeviceId && ctrl.connected && ctrl.wifiEnabled) {
                ctrl.deliverData(this.localDeviceId, bytes.clone())
                sentCount++
            }
        }
        return sentCount > 0
    }

    override fun isConnected(): Boolean = connected && wifiEnabled
    override fun isGroupOwner(): Boolean = isGo
    override fun getGroupOwnerAddress(): String? = goAddress

    override fun close() {
        disconnectGroup()
        network.remove(localP2pAddress)
    }

    fun deliverData(senderId: String, bytes: ByteArray) {
        listener?.onDataReceived(senderId, bytes)
    }

    fun simulateWifiToggle(enabled: Boolean) {
        this.wifiEnabled = enabled
        if (!enabled && connected) {
            connected = false
            listener?.onGroupTerminated("Wi-Fi radio turned off")
        }
    }

    fun simulateGoDisconnect() {
        if (connected) {
            connected = false
            listener?.onGroupTerminated("Group Owner link lost")
        }
    }
}
