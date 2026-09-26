package `in`.itantra.offline.transport

import android.content.*
import android.net.wifi.p2p.*
import android.os.Looper
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/** Wi-Fi P2P discovery never owns peer presence. Addresses arrive from the BLE capability exchange. */
class WifiDirectController(
    private val context: Context,
    localId: UUID,
    private val scope: CoroutineScope,
    allowed: (String) -> Boolean,
    authenticate: (String, ByteArray) -> ByteArray,
    verify: (String, ByteArray, ByteArray) -> Boolean,
    received: (String, ByteArray) -> Unit,
    private val changed: () -> Unit,
    private val notice: (String) -> Unit
) : RadioController {
    override val radio = Radio.WIFI_DIRECT
    private val manager = context.getSystemService(WifiP2pManager::class.java)
    private val channel = manager?.initialize(context, Looper.getMainLooper()) { notice("Wi-Fi Direct channel lost; BLE remains available") }
    private var links = SocketLinks(localId, scope, allowed, authenticate, verify, received, changed)
    private var server: ServerSocket? = null
    private var connecting: Job? = null
    private var timeout: Job? = null
    private var registered = false
    private var closed = false
    var localAddress: String? = null; private set
    var isGroupOwner = false; private set
    private var groupAddress: String? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION) refreshAddress()
            if (intent?.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) inspectConnection()
        }
    }
    private fun action(onSuccess: () -> Unit = {}) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = onSuccess.invoke()
        override fun onFailure(reason: Int) { notice("Wi-Fi Direct unavailable ($reason); using BLE") }
    }
    override fun startDiscovery() {
        if (registered || closed || manager == null || channel == null) return
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        })
        registered = true; refreshAddress(); inspectConnection()
    }
    fun refreshAddress() {
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (manager == null || channel == null) return
        runCatching { manager.requestDeviceInfo(channel!!) { device ->
            localAddress = device?.deviceAddress?.takeUnless { it == "02:00:00:00:00:00" || it.isBlank() }
        } }.onFailure { notice("Wi-Fi Direct address unavailable; BLE remains active") }
    }
    fun connect(address: String, capable: Boolean) {
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) { notice("Wi-Fi location permission unavailable; using BLE"); return }
        require(Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}").matches(address))
        if (manager == null || channel == null || closed) return
        startDiscovery()
        if (groupAddress != null && !isGroupOwner) { startClient(groupAddress!!); return }
        runCatching { manager.connect(channel, WifiP2pConfig().apply {
            deviceAddress = address; groupOwnerIntent = if (capable) 14 else 1
        }, action()) }.onFailure { notice("Wi-Fi Direct permission or radio unavailable; using BLE") }
        timeout?.cancel()
        timeout = scope.launch { delay(10_000); if (groupAddress == null) {
            runCatching { manager.cancelConnect(channel, action()) }; notice("Wi-Fi Direct timed out; BLE fallback remains active")
        } }
    }
    fun createGroup() {
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (manager == null || channel == null || closed) return
        startDiscovery(); runCatching { manager.createGroup(channel, action()) }
    }
    private fun inspectConnection() {
        if (manager == null || channel == null || closed) return
        runCatching { manager.requestConnectionInfo(channel) { info ->
            if (!info.groupFormed) { groupAddress = null; isGroupOwner = false; server?.close(); server = null; connecting?.cancel(); links.disconnectAll(); changed(); return@requestConnectionInfo }
            timeout?.cancel()
            isGroupOwner = info.isGroupOwner
            val address = info.groupOwnerAddress?.hostAddress ?: return@requestConnectionInfo
            if (address == groupAddress) return@requestConnectionInfo
            groupAddress = address
            if (isGroupOwner) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val listener = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }; server = listener
                        while (isActive && !closed && server === listener) links.attach(listener.accept())
                    } catch (_: Exception) { changed() }
                }
            } else {
                startClient(address)
            }
        } }
    }
    private fun startClient(address: String) {
        if (connecting?.isActive == true) return
        connecting = scope.launch(Dispatchers.IO) {
            repeat(3) {
                val socket = Socket()
                try { socket.connect(InetSocketAddress(address, PORT), 3000); links.attach(socket); return@launch }
                catch (_: Exception) { socket.close(); delay(500) }
            }
            notice("Wi-Fi socket unavailable; using BLE")
        }
    }
    override fun available(peerId: String) = links.available(peerId)
    override fun send(peerId: String, bytes: ByteArray) = links.send(peerId, bytes)
    override fun stopDiscovery() { if (registered) { context.unregisterReceiver(receiver); registered = false } }
    override fun close() {
        closed = true; timeout?.cancel(); connecting?.cancel(); runCatching { server?.close() }; links.close(); stopDiscovery()
        if (manager != null && channel != null) { runCatching { manager.removeGroup(channel, action()) }; channel.close() }
    }
    companion object { const val PORT = 38988 }
}
