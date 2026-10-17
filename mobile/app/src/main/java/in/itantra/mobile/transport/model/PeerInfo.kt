package `in`.itantra.mobile.transport.model

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

data class PeerInfo(
    val deviceId: String,
    val displayName: String,
    val supportedLanguages: List<String> = listOf("hi", "en"),
    val p2pDeviceAddress: String? = null,
    val wifiAwareSupported: Boolean = false,
    var lastSeenEpochMs: Long = System.currentTimeMillis(),
    var rssi: Int = 0,
    var activeTransport: TransportType = TransportType.BLE
) {
    fun toHandshakeBytes(): ByteArray {
        val uuid = try { UUID.fromString(deviceId) } catch (e: Exception) { UUID.nameUUIDFromBytes(deviceId.toByteArray()) }
        val nameBytes = displayName.toByteArray(StandardCharsets.UTF_8).coerceAtMost(32)
        val p2pBytes = (p2pDeviceAddress ?: "").toByteArray(StandardCharsets.UTF_8)
        
        var total = 16 + 1 + nameBytes.size + 1
        val langByteList = supportedLanguages.take(8).map { it.toByteArray(StandardCharsets.UTF_8) }
        total += langByteList.sumOf { 1 + it.size }
        total += 1 + p2pBytes.size + 1 // p2p + flags
        
        val buf = ByteBuffer.allocate(total)
        buf.putLong(uuid.mostSignificantBits)
        buf.putLong(uuid.leastSignificantBits)
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        buf.put(langByteList.size.toByte())
        for (lb in langByteList) {
            buf.put(lb.size.toByte())
            buf.put(lb)
        }
        buf.put(p2pBytes.size.toByte())
        if (p2pBytes.isNotEmpty()) buf.put(p2pBytes)
        val flags = if (wifiAwareSupported) 1.toByte() else 0.toByte()
        buf.put(flags)
        return buf.array()
    }

    companion object {
        private fun ByteArray.coerceAtMost(max: Int): ByteArray {
            return if (this.size <= max) this else this.copyOf(max)
        }

        fun fromHandshakeBytes(bytes: ByteArray, rssi: Int = 0): PeerInfo? {
            if (bytes.size < 18) return null
            return try {
                val buf = ByteBuffer.wrap(bytes)
                val msb = buf.long
                val lsb = buf.long
                val devId = UUID(msb, lsb).toString()
                
                val nameLen = buf.get().toInt() and 0xFF
                val nameBytes = ByteArray(nameLen)
                buf.get(nameBytes)
                val name = String(nameBytes, StandardCharsets.UTF_8)
                
                val langCount = buf.get().toInt() and 0xFF
                val langs = ArrayList<String>()
                for (i in 0 until langCount) {
                    val lLen = buf.get().toInt() and 0xFF
                    val lBytes = ByteArray(lLen)
                    buf.get(lBytes)
                    langs.add(String(lBytes, StandardCharsets.UTF_8))
                }
                
                val p2pLen = buf.get().toInt() and 0xFF
                val p2pAddr = if (p2pLen > 0) {
                    val pBytes = ByteArray(p2pLen)
                    buf.get(pBytes)
                    String(pBytes, StandardCharsets.UTF_8)
                } else null
                
                val flags = if (buf.hasRemaining()) buf.get().toInt() else 0
                val nanSupport = (flags and 1) != 0
                
                PeerInfo(
                    deviceId = devId,
                    displayName = name,
                    supportedLanguages = langs,
                    p2pDeviceAddress = p2pAddr,
                    wifiAwareSupported = nanSupport,
                    lastSeenEpochMs = System.currentTimeMillis(),
                    rssi = rssi,
                    activeTransport = TransportType.BLE
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
