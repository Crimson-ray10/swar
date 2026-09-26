@file:Suppress("DEPRECATION")
package `in`.itantra.offline.transport

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** All GATT operations are serialized on the main looper; each direction has its own queue. */
class BleController(
    private val context: Context,
    private val found: (String, String) -> Unit,
    private val connected: (String) -> Unit,
    private val disconnected: (String) -> Unit,
    private val received: (String, ByteArray) -> Unit,
    private val notice: (String) -> Unit
) : RadioController {
    override val radio = Radio.BLE
    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager?.adapter
    private var server: BluetoothGattServer? = null
    private val clients = mutableMapOf<String, BluetoothGatt>()
    private val ready = ConcurrentHashMap.newKeySet<String>()
    private val bindings = ConcurrentHashMap<String, String>()
    private val devices = mutableMapOf<String, BluetoothDevice>()
    private val queues = mutableMapOf<String, ArrayDeque<ByteArray>>()
    private val assemblers = mutableMapOf<String, FragmentAssembler>()
    private val busy = mutableSetOf<String>()
    private val pendingSince = mutableMapOf<String, Long>()
    private val mtus = mutableMapOf<String, Int>()
    private var nextId = 0
    private var running = false
    private var closed = false
    private var serverBusy: String? = null
    private var outgoing: BluetoothGattCharacteristic? = null
    private val advertiser = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { notice("BLE advertising unavailable ($errorCode); scanning remains available") }
    }
    private val scan = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { handler.post {
            val address = result.device.address
            devices[address] = result.device
            found(address, result.scanRecord?.deviceName ?: "Nearby LinC device")
        } }
        override fun onScanFailed(errorCode: Int) { notice("BLE scan failed ($errorCode)") }
    }
    fun bind(peerId: String, address: String) { bindings[peerId] = address }
    fun connect(address: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        if (closed || clients.containsKey(address) || address in ready) return
        val device = devices[address] ?: adapter?.getRemoteDevice(address) ?: return
        val gatt = device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
        clients[address] = gatt
        handler.postDelayed({ if (address !in ready && clients[address] === gatt) drop(address) }, 15_000)
    }
    private fun accept(address: String, bytes: ByteArray) {
        try { assemblers.getOrPut(address) { FragmentAssembler(SystemClock::elapsedRealtime) }.accept(bytes)?.let { received(address, it) } }
        catch (_: Exception) { assemblers.remove(address); notice("Rejected malformed BLE fragments") }
    }
    private fun markReady(address: String) { if (ready.add(address)) connected(address) }
    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) { handler.post {
            devices[device.address] = device
            if (newState == BluetoothProfile.STATE_DISCONNECTED) drop(device.address)
        } }
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) { handler.post { mtus[device.address] = mtu.coerceIn(23, 517) } }
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) { handler.post {
            if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return@post
            val valid = !preparedWrite && offset == 0 && descriptor.uuid == CCC && value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            if (responseNeeded) server?.sendResponse(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            if (valid) markReady(device.address)
        } }
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) { handler.post {
            if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return@post
            val valid = !preparedWrite && offset == 0 && characteristic.uuid == RX && device.address in ready && value.size in 13..512
            if (responseNeeded) server?.sendResponse(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            if (valid) accept(device.address, value)
        } }
        override fun onNotificationSent(device: BluetoothDevice, status: Int) { handler.post {
            if (serverBusy == device.address) serverBusy = null
            complete(device.address, status)
            pumpServer()
        } }
        override fun onServiceAdded(status: Int, service: BluetoothGattService) { handler.post {
            if (status != BluetoothGatt.GATT_SUCCESS) { notice("BLE service registration failed"); return@post }
            if (!running) return@post
            if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) return@post
            runCatching { adapter?.bluetoothLeAdvertiser?.startAdvertising(
                AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).setTimeout(0).build(),
                AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE)).build(), advertiser
            ) }.onFailure { notice("BLE advertising permission denied") }
        } }
    }
    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) { handler.post {
            if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return@post
            if (clients[gatt.device.address] !== gatt) return@post
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) drop(gatt.device.address)
        } }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) { handler.post {
            if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return@post
            if (status != BluetoothGatt.GATT_SUCCESS) { drop(gatt.device.address); return@post }
            val characteristic = gatt.getService(SERVICE)?.getCharacteristic(TX)
            val descriptor = characteristic?.getDescriptor(CCC)
            if (characteristic == null || descriptor == null) { drop(gatt.device.address); return@post }
            gatt.setCharacteristicNotification(characteristic, true)
            descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (!gatt.writeDescriptor(descriptor)) drop(gatt.device.address)
        } }
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) { handler.post {
            if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return@post
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (!gatt.requestMtu(185)) markReady(gatt.device.address)
                handler.postDelayed({ if (clients[gatt.device.address] === gatt) markReady(gatt.device.address) }, 2000)
            } else drop(gatt.device.address)
        } }
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) { handler.post {
            if (status == BluetoothGatt.GATT_SUCCESS) mtus[gatt.device.address] = mtu.coerceIn(23, 517)
            markReady(gatt.device.address)
        } }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { handler.post { complete(gatt.device.address, status) } }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val bytes = characteristic.value?.copyOf() ?: return
            handler.post { markReady(gatt.device.address); accept(gatt.device.address, bytes) }
        }
    }
    override fun startDiscovery() {
        if (Build.VERSION.SDK_INT >= 31 && (
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)) {
            notice("Bluetooth permissions are unavailable"); return
        }
        if (Build.VERSION.SDK_INT < 31 && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            notice("Location permission is required for BLE discovery on this Android version"); return
        }
        if (running || closed) return
        val bluetooth = adapter
        if (bluetooth == null || !bluetooth.isEnabled) { notice("Enable Bluetooth to discover nearby peers"); return }
        try {
            running = true
            server = manager.openGattServer(context, serverCallback)
            val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(BluetoothGattCharacteristic(RX, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
            outgoing = BluetoothGattCharacteristic(TX, BluetoothGattCharacteristic.PROPERTY_INDICATE, BluetoothGattCharacteristic.PERMISSION_READ).apply {
                addDescriptor(BluetoothGattDescriptor(CCC, BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ))
            }
            service.addCharacteristic(outgoing)
            check(server?.addService(service) == true)
            bluetooth.bluetoothLeScanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan)
            handler.post(watchdog)
        } catch (e: Exception) { stopDiscovery(); notice("BLE discovery unavailable: ${e.message}") }
    }
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            pendingSince.filterValues { SystemClock.elapsedRealtime() - it > 10_000 }.keys.toList().forEach(::drop)
            handler.postDelayed(this, 1000)
        }
    }
    override fun available(peerId: String) = (bindings[peerId] ?: peerId) in ready
    override fun send(peerId: String, bytes: ByteArray): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val address = bindings[peerId] ?: peerId
        if (address !in ready || bytes.isEmpty() || bytes.size > Fragmentation.LIMIT) return false
        val fragments = Fragmentation.split(nextId++, bytes, minOf((mtus[address] ?: 23) - 3, 512))
        val queue = queues.getOrPut(address) { ArrayDeque() }
        if (queue.size + fragments.size > 4096) return false
        queue.addAll(fragments); pump(address); return true
    }
    private fun pump(address: String) {
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        if (address in busy || address !in ready || queues[address].isNullOrEmpty()) return
        val gatt = clients[address]
        if (gatt == null) { pumpServer(); return }
        val characteristic = gatt.getService(SERVICE)?.getCharacteristic(RX) ?: return
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = queues[address]!!.first()
        busy += address; pendingSince[address] = SystemClock.elapsedRealtime()
        if (!gatt.writeCharacteristic(characteristic)) drop(address)
    }
    private fun pumpServer() {
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        if (serverBusy != null) return
        val address = queues.keys.firstOrNull { it in ready && it !in clients && it !in busy && !queues[it].isNullOrEmpty() } ?: return
        val device = devices[address] ?: return
        val characteristic = outgoing ?: return
        characteristic.value = queues[address]!!.first()
        busy += address; serverBusy = address; pendingSince[address] = SystemClock.elapsedRealtime()
        if (server?.notifyCharacteristicChanged(device, characteristic, true) != true) { serverBusy = null; drop(address) }
    }
    private fun complete(address: String, status: Int) {
        busy -= address; pendingSince.remove(address)
        if (status != BluetoothGatt.GATT_SUCCESS) { drop(address); return }
        queues[address]?.removeFirstOrNull(); pump(address)
    }
    fun drop(address: String) {
        val wasReady = ready.remove(address)
        clients.remove(address)?.let { try { it.disconnect(); it.close() } catch (_: SecurityException) { } }
        devices[address]?.let { try { server?.cancelConnection(it) } catch (_: SecurityException) { } }
        queues.remove(address); assemblers.remove(address); busy -= address; pendingSince.remove(address); mtus.remove(address)
        if (serverBusy == address) serverBusy = null
        if (wasReady) disconnected(address)
    }
    override fun stopDiscovery() {
        running = false; handler.removeCallbacks(watchdog)
        try { adapter?.bluetoothLeScanner?.stopScan(scan) } catch (_: SecurityException) { }
        try { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiser) } catch (_: SecurityException) { }
    }
    override fun close() {
        closed = true; stopDiscovery(); (ready.toSet() + clients.keys.toSet()).forEach(::drop)
        try { server?.close() } catch (_: SecurityException) { }; server = null; handler.removeCallbacksAndMessages(null)
    }
    companion object {
        val SERVICE: UUID = UUID.fromString("b8b19b10-9d64-4fbb-bd2b-4b544c494e43")
        private val RX: UUID = UUID.fromString("b8b19b11-9d64-4fbb-bd2b-4b544c494e43")
        private val TX: UUID = UUID.fromString("b8b19b12-9d64-4fbb-bd2b-4b544c494e43")
        private val CCC: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
