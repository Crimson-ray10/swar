package `in`.itantra.mobile.protocol

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

enum class MessageType(val code: Byte) {
    HANDSHAKE(0),
    STT_PARTIAL(1),
    STT_FINAL(2),
    ACK(3),
    FLOOR_REQUEST(4),
    FLOOR_GRANT(5),
    FLOOR_RELEASE(6);

    companion object {
        fun fromCode(code: Byte): MessageType = entries.firstOrNull { it.code == code } ?: HANDSHAKE
    }
}

data class VoicePayload(
    val type: MessageType,
    val senderDeviceId: String,
    val streamId: Int,
    val sequenceNumber: Int,
    val languageCode: Byte,
    val flags: Byte,
    val payloadBytes: ByteArray
) {
    val isFinal: Boolean get() = (flags.toInt() and 0x01) != 0
    val isTranslated: Boolean get() = (flags.toInt() and 0x02) != 0
    val isEncrypted: Boolean get() = (flags.toInt() and 0x04) != 0

    fun encode(): ByteArray {
        val uuid = try { UUID.fromString(senderDeviceId) } catch (e: Exception) { UUID.nameUUIDFromBytes(senderDeviceId.toByteArray()) }
        val totalLength = HEADER_SIZE + payloadBytes.size
        val buf = ByteBuffer.allocate(totalLength)

        buf.put(type.code)
        buf.putLong(uuid.mostSignificantBits)
        buf.putLong(uuid.leastSignificantBits)
        buf.putInt(streamId)
        buf.putInt(sequenceNumber)
        buf.put(languageCode)
        buf.put(flags)
        buf.putShort(payloadBytes.size.toShort())
        buf.put(payloadBytes)

        return buf.array()
    }

    companion object {
        const val HEADER_SIZE = 29

        fun decode(bytes: ByteArray): VoicePayload {
            if (bytes.size < HEADER_SIZE) {
                throw IllegalArgumentException("Payload smaller than required 29-byte header: ${bytes.size}")
            }
            val buf = ByteBuffer.wrap(bytes)
            val type = MessageType.fromCode(buf.get())
            val msb = buf.long
            val lsb = buf.long
            val senderId = UUID(msb, lsb).toString()
            val streamId = buf.int
            val seq = buf.int
            val lang = buf.get()
            val flags = buf.get()
            val len = buf.short.toInt() and 0xFFFF

            if (buf.remaining() < len) {
                throw IllegalArgumentException("Incomplete payload: expected $len bytes, available ${buf.remaining()}")
            }

            val payload = ByteArray(len)
            buf.get(payload)

            return VoicePayload(
                type = type,
                senderDeviceId = senderId,
                streamId = streamId,
                sequenceNumber = seq,
                languageCode = lang,
                flags = flags,
                payloadBytes = payload
            )
        }

        fun createAck(senderDeviceId: String, streamId: Int, sequenceNumber: Int): VoicePayload {
            return VoicePayload(
                type = MessageType.ACK,
                senderDeviceId = senderDeviceId,
                streamId = streamId,
                sequenceNumber = sequenceNumber,
                languageCode = 0,
                flags = 0,
                payloadBytes = ByteArray(0)
            )
        }

        fun createFloorRequest(senderDeviceId: String): VoicePayload {
            return VoicePayload(
                type = MessageType.FLOOR_REQUEST,
                senderDeviceId = senderDeviceId,
                streamId = 0,
                sequenceNumber = 0,
                languageCode = 0,
                flags = 0,
                payloadBytes = ByteArray(0)
            )
        }

        fun createFloorGrant(senderDeviceId: String, grantedToDeviceId: String): VoicePayload {
            val grantBytes = grantedToDeviceId.toByteArray(StandardCharsets.UTF_8)
            return VoicePayload(
                type = MessageType.FLOOR_GRANT,
                senderDeviceId = senderDeviceId,
                streamId = 0,
                sequenceNumber = 0,
                languageCode = 0,
                flags = 0,
                payloadBytes = grantBytes
            )
        }

        fun createFloorRelease(senderDeviceId: String): VoicePayload {
            return VoicePayload(
                type = MessageType.FLOOR_RELEASE,
                senderDeviceId = senderDeviceId,
                streamId = 0,
                sequenceNumber = 0,
                languageCode = 0,
                flags = 0,
                payloadBytes = ByteArray(0)
            )
        }
    }
}
