package `in`.itantra.offline.transport

import android.content.Context
import android.content.pm.PackageManager
import android.net.*
import android.net.wifi.aware.*
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.util.UUID

/** Optional 1:1 NAN path, authenticated with a PSK derived from the confirmed BLE handshake. */
class WifiAwareController(
    private val context: Context,
    private val localId: UUID,
    private val scope: CoroutineScope,
    allowed: (String) -> Boolean,
    authenticate: (String, ByteArray) -> ByteArray,
    verify: (String, ByteArray, ByteArray) -> Boolean,
    received: (String, ByteArray) -> Unit,
    private val changed: () -> Unit,
    private val fallback: () -> Unit
) : RadioController {
    override val radio = Radio.WIFI_AWARE
    private val manager = context.getSystemService(WifiAwareManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val links = SocketLinks(localId, scope, allowed, authenticate, verify, received, changed)
    private var session: WifiAwareSession? = null
    private var discovery: DiscoverySession? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var server: ServerSocket? = null
    private var timeout: Job? = null
    private var peer: String? = null
    private var closed = false
    val supported: Boolean get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) && manager?.isAvailable == true
    override fun startDiscovery() { /* BLE exclusively owns user-visible discovery. NAN starts for a confirmed pair. */ }
    fun connect(peerId: String, passphrase: String) {
        if (closed || peer == peerId) return
        if (!supported) { fallback(); return }
        stopDiscovery(); peer = peerId
        val publisher = localId.toString() < peerId
        timeout = scope.launch { delay(8000); if (!available(peerId)) { stopDiscovery(); fallback() } }
        runCatching { manager!!.attach(object : AttachCallback() {
            override fun onAttachFailed() { stopDiscovery(); fallback() }
            override fun onAttached(attached: WifiAwareSession) {
                if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) { attached.close(); fallback(); return }
                if (closed || peer != peerId) { attached.close(); return }
                session = attached
                val name = "linc-${if (publisher) localId else peerId}"
                val listener = object : DiscoverySessionCallback() {
                    override fun onPublishStarted(value: PublishDiscoverySession) { discovery = value }
                    override fun onSubscribeStarted(value: SubscribeDiscoverySession) { discovery = value }
                    override fun onSessionConfigFailed() { stopDiscovery(); fallback() }
                    override fun onServiceDiscovered(handle: PeerHandle, serviceSpecificInfo: ByteArray, matchFilter: MutableList<ByteArray>) {
                        discovery?.sendMessage(handle, 1, localId.toString().toByteArray())
                    }
                    override fun onMessageReceived(handle: PeerHandle, message: ByteArray) {
                        if (String(message) != peerId || callback != null) return
                        if (publisher) discovery?.sendMessage(handle, 2, localId.toString().toByteArray())
                        requestPath(peerId, passphrase, handle, publisher)
                    }
                }
                if (publisher) attached.publish(PublishConfig.Builder().setServiceName(name).build(), listener, handler)
                else attached.subscribe(SubscribeConfig.Builder().setServiceName(name).build(), listener, handler)
            }
        }, handler) }.onFailure { stopDiscovery(); fallback() }
    }
    private fun requestPath(peerId: String, passphrase: String, handle: PeerHandle, publisher: Boolean) {
        val active = discovery ?: return
        try {
            val builder = WifiAwareNetworkSpecifier.Builder(active, handle).setPskPassphrase(passphrase)
            if (publisher) {
                val listener = ServerSocket(0); server = listener
                builder.setPort(listener.localPort).setTransportProtocol(6)
                scope.launch(Dispatchers.IO) {
                    try { links.attach(listener.accept(), peerId) } catch (_: Exception) { }
                }
            }
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(builder.build()).build()
            var connecting = false
            val listener = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (publisher || connecting) return
                    val info = capabilities.transportInfo as? WifiAwareNetworkInfo ?: return
                    val address = info.peerIpv6Addr ?: return
                    if (info.port <= 0) return
                    connecting = true
                    scope.launch(Dispatchers.IO) {
                        try {
                            val socket = network.socketFactory.createSocket()
                            socket.connect(java.net.InetSocketAddress(address, info.port), 3000)
                            links.attach(socket, peerId)
                        } catch (_: Exception) { handler.post { stopDiscovery(); fallback() } }
                    }
                }
                override fun onUnavailable() { handler.post { stopDiscovery(); fallback() } }
                override fun onLost(network: Network) { handler.post { stopDiscovery(); changed(); fallback() } }
            }
            callback = listener
            connectivity.requestNetwork(request, listener, handler, 8000)
        } catch (_: Exception) { stopDiscovery(); fallback() }
    }
    override fun available(peerId: String) = links.available(peerId)
    override fun send(peerId: String, bytes: ByteArray) = links.send(peerId, bytes)
    override fun stopDiscovery() {
        links.disconnectAll()
        timeout?.cancel(); timeout = null
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }; callback = null
        runCatching { server?.close() }; server = null
        discovery?.close(); discovery = null; session?.close(); session = null; peer = null
    }
    override fun close() { closed = true; stopDiscovery(); links.close() }
}
