package `in`.itantra.mobile.transport.fake

import `in`.itantra.mobile.transport.aware.IWifiAwareController
import `in`.itantra.mobile.transport.aware.WifiAwareListener
import `in`.itantra.mobile.transport.model.PeerInfo
import java.util.concurrent.ConcurrentHashMap

class FakeWifiAwareController(
    val localDeviceId: String,
    private val hardwareSupported: Boolean = true
) : IWifiAwareController {

    companion object {
        // Shared virtual NAN ether
        private val awareEther = ConcurrentHashMap<String, FakeWifiAwareController>()

        fun clearEther() {
            awareEther.clear()
        }
    }

    private var listener: WifiAwareListener? = null
    private var sessionActive = false
    private val activeDataPaths = ConcurrentHashMap<String, String>() // peerId -> virtual local address

    init {
        if (hardwareSupported) {
            awareEther[localDeviceId] = this
        }
    }

    override fun isSupported(): Boolean = hardwareSupported

    override fun setListener(listener: WifiAwareListener?) {
        this.listener = listener
    }

    override fun startSession(localPeer: PeerInfo): Boolean {
        if (!hardwareSupported) return false
        sessionActive = true
        listener?.onAwareSessionStarted()
        return true
    }

    override fun stopSession() {
        sessionActive = false
        activeDataPaths.clear()
    }

    override fun openDataPath(targetPeer: PeerInfo): Boolean {
        if (!hardwareSupported || !sessionActive) return false
        val target = awareEther[targetPeer.deviceId]
        if (target == null || !target.hardwareSupported || !target.sessionActive) {
            listener?.onAwareFailed("Target peer does not support or have active Wi-Fi Aware session")
            return false
        }

        // Establish pairwise NAN data path without Group Owner election
        val localAddr = "nan6:fe80::$localDeviceId"
        val remoteAddr = "nan6:fe80::${targetPeer.deviceId}"

        activeDataPaths[targetPeer.deviceId] = localAddr
        target.activeDataPaths[localDeviceId] = remoteAddr

        this.listener?.onDataPathOpened(targetPeer.deviceId, localAddr, isServer = true)
        target.listener?.onDataPathOpened(localDeviceId, remoteAddr, isServer = false)
        return true
    }

    override fun closeDataPath(peerId: String) {
        activeDataPaths.remove(peerId)
        awareEther[peerId]?.activeDataPaths?.remove(localDeviceId)
        listener?.onDataPathClosed(peerId, "Data path closed by peer")
        awareEther[peerId]?.listener?.onDataPathClosed(localDeviceId, "Data path closed by peer")
    }

    override fun sendData(peerId: String, bytes: ByteArray): Boolean {
        if (!hardwareSupported || !sessionActive || !activeDataPaths.containsKey(peerId)) return false
        val target = awareEther[peerId] ?: return false
        target.deliverData(localDeviceId, bytes.clone())
        return true
    }

    override fun isConnected(peerId: String): Boolean {
        return hardwareSupported && sessionActive && activeDataPaths.containsKey(peerId)
    }

    override fun close() {
        stopSession()
        awareEther.remove(localDeviceId)
    }

    fun deliverData(senderId: String, bytes: ByteArray) {
        listener?.onDataReceived(senderId, bytes)
    }

    fun simulateDataPathDrop(peerId: String) {
        closeDataPath(peerId)
    }
}
