package `in`.itantra.mobile.transport.aware

import `in`.itantra.mobile.transport.model.PeerInfo

interface WifiAwareListener {
    fun onAwareSessionStarted()
    fun onDataPathOpened(peerId: String, localAddress: String, isServer: Boolean)
    fun onDataPathClosed(peerId: String, reason: String)
    fun onDataReceived(senderId: String, bytes: ByteArray)
    fun onAwareFailed(reason: String)
}

interface IWifiAwareController {
    fun isSupported(): Boolean
    fun setListener(listener: WifiAwareListener?)
    fun startSession(localPeer: PeerInfo): Boolean
    fun stopSession()
    fun openDataPath(targetPeer: PeerInfo): Boolean
    fun closeDataPath(peerId: String)
    fun sendData(peerId: String, bytes: ByteArray): Boolean
    fun isConnected(peerId: String): Boolean
    fun close()
}
