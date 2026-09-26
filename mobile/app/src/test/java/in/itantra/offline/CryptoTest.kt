package `in`.itantra.offline

import `in`.itantra.offline.crypto.*
import org.junit.Assert.*
import org.junit.Test

class CryptoTest {
    private fun pair(): Pair<Pairing, Pairing> {
        val a = Pairing(Identity(), { 0 }, { false })
        val b = Pairing(Identity(), { 0 }, { false })
        val revealA = a.receive(b.begin())!!
        val revealB = b.receive(a.begin())!!
        a.receive(revealB); b.receive(revealA)
        assertEquals(a.sas, b.sas)
        return a to b
    }
    @Test fun bothUsersMustConfirmBeforeApplicationTraffic() {
        val (a, b) = pair()
        assertThrows(IllegalStateException::class.java) { a.encrypt(byteArrayOf(1)) }
        b.receive(a.confirm())
        assertFalse(a.ready); assertFalse(b.ready)
        a.receive(b.confirm())
        assertTrue(a.ready); assertTrue(b.ready)
        assertArrayEquals("नमस्ते".toByteArray(), b.decrypt(a.encrypt("नमस्ते".toByteArray())))
        assertArrayEquals("hello".toByteArray(), a.decrypt(b.encrypt("hello".toByteArray())))
        assertEquals(a.awarePassphrase(), b.awarePassphrase())
    }
    @Test fun replayReflectionAndTamperingAreRejected() {
        val (a, b) = pair(); b.receive(a.confirm()); a.receive(b.confirm())
        val frame = a.encrypt(byteArrayOf(8))
        val corrupt = frame.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { b.decrypt(corrupt) }
        assertArrayEquals(byteArrayOf(8), b.decrypt(frame)) // A forgery must not consume the counter.
        assertThrows(Exception::class.java) { b.decrypt(frame) }
        assertThrows(Exception::class.java) { a.decrypt(a.encrypt(byteArrayOf(2))) }
    }
    @Test fun outOfOrderRadioArrivalsWithinWindowAreAcceptedOnce() {
        val (a, b) = pair(); b.receive(a.confirm()); a.receive(b.confirm())
        val messages = (0..10).map { a.encrypt(byteArrayOf(it.toByte())) }
        listOf(10, 3, 0, 8, 4, 9, 1, 6, 7, 5, 2).forEach { index -> assertArrayEquals(byteArrayOf(index.toByte()), b.decrypt(messages[index])) }
    }
    @Test fun oldSessionMessagesFailAfterFreshHandshake() {
        val (a, b) = pair(); b.receive(a.confirm()); a.receive(b.confirm())
        val (_, nextB) = pair()
        assertThrows(Exception::class.java) { nextB.decrypt(a.encrypt(byteArrayOf(5))) }
    }
    @Test fun commitmentCannotBeChangedAfterSeeingTheOtherReveal() {
        val a = Pairing(Identity(), { 0 }, { false }); val b = Pairing(Identity(), { 0 }, { false })
        val reveal = b.receive(a.begin())!!; a.receive(b.begin())
        val changed = reveal.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { a.receive(changed) }
        assertThrows(Exception::class.java) { a.receive(reveal) }
    }
    @Test fun pairingExpiryAndIdentityPersistence() {
        val identity = Identity(); assertEquals(identity.id, Identity(identity.exportSeed()).id)
        var time = 0L
        val a = Pairing(identity, { time }, { false })
        time = 120_001
        assertThrows(Exception::class.java) { a.receive(Pairing(Identity(), { 0 }, { false }).begin()) }
    }
}
