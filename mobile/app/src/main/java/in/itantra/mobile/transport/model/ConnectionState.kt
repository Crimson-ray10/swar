package `in`.itantra.mobile.transport.model

sealed class ConnectionState {
    object Disconnected : ConnectionState() {
        override fun toString(): String = "Disconnected"
    }

    object Scanning : ConnectionState() {
        override fun toString(): String = "Scanning"
    }

    data class BleConnected(
        val peer: PeerInfo,
        val degraded: Boolean = true
    ) : ConnectionState()

    data class NegotiatingWifiDirect(
        val peer: PeerInfo
    ) : ConnectionState()

    data class WifiDirectConnected(
        val peer: PeerInfo,
        val isGroupOwner: Boolean,
        val groupOwnerAddress: String
    ) : ConnectionState()

    data class WifiAwareConnected(
        val peer: PeerInfo
    ) : ConnectionState()

    data class FallbackToBle(
        val peer: PeerInfo,
        val reason: String
    ) : ConnectionState()
}
