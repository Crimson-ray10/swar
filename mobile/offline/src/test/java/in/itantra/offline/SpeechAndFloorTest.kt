package `in`.itantra.offline

import `in`.itantra.offline.speech.*
import `in`.itantra.offline.session.*
import `in`.itantra.offline.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SpeechAndFloorTest {
    @Test fun revisedHypothesesDoNotCommitUnstableSuffixes() {
        val buffer = StableText(3)
        assertNull(buffer.update("send four")); assertNull(buffer.update("send five"))
        assertEquals(TextChunk(0, "send"), buffer.update("send five people"))
        assertEquals(TextChunk(1, "five"), buffer.update("send five people"))
        assertEquals(TextChunk(2, "people"), buffer.update("send five people"))
        assertNull(buffer.update("send four people", true))
    }
    @Test fun changedEarlierWordInvalidatesSuffixStabilityCounts() {
        val buffer = StableText(3)
        buffer.update("one road"); buffer.update("two road")
        assertNull(buffer.update("three road"))
    }
    @Test fun finalRecoversDroppedPartialsWithoutRepeatingQueuedWords() {
        val ledger = ReceiveLedger()
        assertEquals(listOf(TextChunk(0, "need help")), ledger.partial(0, "need help"))
        assertTrue(ledger.partial(4, "station").isEmpty())
        assertEquals(listOf(TextChunk(2, "at the station")), ledger.final("need help at the station"))
        assertTrue(ledger.final("need help at the station").isEmpty())
        assertTrue(ledger.partial(2, "at the").isEmpty())
    }
    @Test fun reorderedChunksBecomeAudibleInOrder() {
        val ledger = ReceiveLedger()
        assertTrue(ledger.partial(2, "on road").isEmpty())
        assertEquals(listOf(TextChunk(0, "need help"), TextChunk(2, "on road")), ledger.partial(0, "need help"))
    }
    @Test fun finalCorrectionUpdatesTextWithoutReplayingAudio() {
        val ledger = ReceiveLedger(); ledger.partial(0, "send four")
        assertTrue(ledger.final("send five people").isEmpty()); assertTrue(ledger.revised)
    }
    @Test fun remoteStreamsCannotOverwriteEachOther() {
        val a = ReceiveLedger(); val b = ReceiveLedger()
        a.partial(0, "A one"); b.partial(0, "B two")
        assertEquals("three", a.final("A one three").single().text)
        assertEquals("four", b.final("B two four").single().text)
    }
    @Test fun conversationNeverDependsOnFloorStateAndLeasesExpire() {
        var now = 0L
        val floor = FloorCoordinator({ now }, 1000); val a = UUID.randomUUID(); val b = UUID.randomUUID()
        assertNull(floor.current()); assertTrue(floor.mayCapture(OperatingMode.CONVERSATION, a))
        assertFalse(floor.mayCapture(OperatingMode.WALKIE_TALKIE, a))
        floor.request(a); assertNull(floor.request(b))
        assertTrue(floor.mayCapture(OperatingMode.CONVERSATION, b))
        floor.release(b, 0); assertEquals(a, floor.current()!!.holder)
        now = 1001; assertEquals(b, floor.request(b)!!.holder)
        floor.newEpoch(1); assertNull(floor.current()); floor.request(a)
        floor.release(a, 0); assertNotNull(floor.current())
    }
    @Test fun finalsRetryFreshlyAndAckIsBoundToPeerSenderStreamSequence() {
        var now = 0L
        val reliability = ReliableFinals({ now }, 100, 3)
        val packet = SpeechPacket(MessageType.FINAL, UUID.randomUUID(), 3, 5, Language.HI, text = "मदद")
        reliability.track("peer", packet)
        assertFalse(reliability.acknowledge("wrong-peer", packet.sender, 3, 5))
        var sent = 0; var failed = 0
        repeat(3) { now += 100; reliability.tick({ _, _ -> sent++ }, { failed++ }) }
        assertEquals(2, sent); assertEquals(1, failed); assertEquals(0, reliability.size)
        reliability.track("peer", packet)
        assertTrue(reliability.acknowledge("peer", packet.sender, 3, 5))
        assertEquals(0, reliability.size)
    }
}
