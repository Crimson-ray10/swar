package `in`.itantra.offline.protocol

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

enum class MessageType { PARTIAL, FINAL, ACK, FLOOR_REQUEST, FLOOR_GRANT, FLOOR_RELEASE, HEARTBEAT, CAPABILITIES }
enum class Language(val tag: String) {
    HI("hi"), EN("en"), OR("or"), BN("bn"), TA("ta"), TE("te"), MR("mr"), HINGLISH("hi-en"),
    GU("gu"), KN("kn"), ML("ml"), PA("pa");
    companion object {
        fun fromTag(tag: String): Language = entries.firstOrNull { 
            it.tag.equals(tag, ignoreCase = true) || 
            (it == HINGLISH && (tag.equals("hinglish", ignoreCase = true) || tag.equals("hi-en", ignoreCase = true)))
        } ?: HI
    }
}

/** Offsets count whitespace-delimited words, never UTF-16 bytes. FINAL carries the complete transcript. */
data class SpeechPacket(
    val type: MessageType,
    val sender: UUID,
    val stream: Long,
    val sequence: Int,
    val language: Language,
    val offset: Int = 0,
    val translated: Boolean = false,
    val text: String = ""
) {
    init { require(stream >= 0 && sequence >= 0 && offset >= 0) }
    fun encode(): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_TEXT_BYTES) { "Message too large" }
        return ByteBuffer.allocate(HEADER_SIZE + body.size)
            .put(VERSION).put(type.ordinal.toByte()).putLong(sender.mostSignificantBits)
            .putLong(sender.leastSignificantBits).putLong(stream).putInt(sequence)
            .put(language.ordinal.toByte()).put(if (translated) 1 else 0)
            .putInt(offset).putShort(body.size.toShort()).put(body).array()
    }
    companion object {
        const val VERSION: Byte = 1
        const val HEADER_SIZE = 38
        const val MAX_TEXT_BYTES = 8192
        fun decode(bytes: ByteArray): SpeechPacket {
            require(bytes.size in HEADER_SIZE..HEADER_SIZE + MAX_TEXT_BYTES) { "Invalid frame size" }
            val b = ByteBuffer.wrap(bytes)
            require(b.get() == VERSION) { "Unsupported protocol version" }
            val type = MessageType.entries.getOrNull(b.get().toInt()) ?: error("Invalid message type")
            val sender = UUID(b.long, b.long)
            val stream = b.long
            val sequence = b.int
            val language = Language.entries.getOrNull(b.get().toInt()) ?: error("Invalid language")
            val flags = b.get().toInt()
            require(flags in 0..1) { "Unknown flags" }
            val offset = b.int
            val length = b.short.toInt() and 0xffff
            require(length == b.remaining()) { "Payload length mismatch" }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(b).toString()
            return SpeechPacket(type, sender, stream, sequence, language, offset, flags == 1, text)
        }
    }
}
