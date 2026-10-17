package `in`.itantra.mobile.transport.p2p

interface WifiDirectListener {
    fun onGroupFormed(isGroupOwner: Boolean, goAddress: String, connectedClients: List<String>)
    fun onGroupTerminated(reason: String)
    fun onDataReceived(senderId: String, bytes: ByteArray)
    fun onConnectionFailed(reason: String)
}

interface IWifiDirectController {
    fun setListener(listener: WifiDirectListener?)
    fun initialize(): Boolean
    fun connectPeer(p2pDeviceAddress: String, isHighCapabilityOrPluggedIn: Boolean)
    fun disconnectGroup()
    fun sendData(peerId: String, bytes: ByteArray): Boolean
    fun broadcastData(bytes: ByteArray): Boolean
    fun isConnected(): Boolean
    fun isGroupOwner(): Boolean
    fun getGroupOwnerAddress(): String?
    fun close()
}
