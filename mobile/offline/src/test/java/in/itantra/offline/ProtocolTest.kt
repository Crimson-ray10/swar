package `in`.itantra.offline

import `in`.itantra.offline.protocol.*
import `in`.itantra.offline.transport.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ProtocolTest {
    private val sender = UUID.randomUUID()
    @Test fun allLanguagesRoundTripWithOffsetsAndUtf8() {
        for (language in Language.entries) {
            val message = SpeechPacket(MessageType.PARTIAL, sender, 7, 9, language, 4, true, "सहायता ଓଡ଼ିଆ தமிழ் 👩🏽‍🚒")
            assertEquals(message, SpeechPacket.decode(message.encode()))
        }
    }
    @Test fun truncatedOversizedUnknownAndMalformedUtf8Rejected() {
        val valid = SpeechPacket(MessageType.FINAL, sender, 1, 0, Language.EN, text = "a").encode()
        val cases = listOf(valid.copyOf(valid.size - 1), valid + 0, valid.copyOf().also { it[0] = 99 },
            valid.copyOf().also { it[1] = 99 }, valid.copyOf().also { it[it.lastIndex] = 0xff.toByte() })
        cases.forEach { bytes -> assertThrows(Exception::class.java) { SpeechPacket.decode(bytes) } }
        assertThrows(IllegalArgumentException::class.java) { SpeechPacket(MessageType.FINAL, sender, 1, 0, Language.EN, text = "a".repeat(8193)).encode() }
    }
    @Test fun everyGattMtuReassemblesTheSameMessage() {
        val source = ByteArray(8192) { (it % 251).toByte() }
        for (mtuPayload in listOf(20, 182, 244, 512)) {
            val assembler = FragmentAssembler { 0 }
            var result: ByteArray? = null
            val frames = Fragmentation.split(4, source, mtuPayload)
            frames.forEach { result = assembler.accept(it) }
            assertArrayEquals(source, result)
        }
    }
    @Test fun fragmentGapsAndExpiredAssembliesAreRejected() {
        var time = 0L
        val assembler = FragmentAssembler { time }
        val frames = Fragmentation.split(2, ByteArray(100))
        assembler.accept(frames[0])
        assertThrows(IllegalArgumentException::class.java) { assembler.accept(frames[2]) }
        assembler.accept(frames[0]); time = 30_001
        assertThrows(IllegalArgumentException::class.java) { assembler.accept(frames[1]) }
    }
}
