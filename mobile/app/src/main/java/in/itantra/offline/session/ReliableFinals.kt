package `in`.itantra.offline.session

import `in`.itantra.offline.protocol.MessageType
import `in`.itantra.offline.protocol.SpeechPacket
import java.util.UUID

/** Bounded final-only ACK/retry state. Retries must be encrypted afresh by the caller. */
class ReliableFinals(private val now: () -> Long, private val timeoutMs: Long = 1200, private val maxAttempts: Int = 4) {
    data class Key(val peer: String, val sender: UUID, val stream: Long, val sequence: Int)
    private data class Pending(val packet: SpeechPacket, var last: Long, var attempts: Int)
    private val pending = linkedMapOf<Key, Pending>()
    val size get() = pending.size
    fun track(peer: String, packet: SpeechPacket) {
        require(packet.type == MessageType.FINAL)
        val key = Key(peer, packet.sender, packet.stream, packet.sequence)
        if (key in pending) return
        require(pending.size < 128) { "Unacknowledged message queue full" }
        pending[key] = Pending(packet, now(), 1)
    }
    fun acknowledge(peer: String, sender: UUID, stream: Long, sequence: Int) = pending.remove(Key(peer, sender, stream, sequence)) != null
    fun tick(send: (String, SpeechPacket) -> Unit, failed: (Key) -> Unit) {
        val due = pending.filterValues { now() - it.last >= timeoutMs }.keys.toList()
        for (key in due) {
            val entry = pending[key] ?: continue
            if (entry.attempts >= maxAttempts) { pending.remove(key); failed(key) }
            else { entry.attempts++; entry.last = now(); send(key.peer, entry.packet) }
        }
    }
    fun clear() = pending.clear()
}
