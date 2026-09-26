package `in`.itantra.mobile.protocol

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ReliabilityEngine(
    val localDeviceId: String,
    val maxRetries: Int = 3,
    val retransmitTimeoutMs: Long = 200
) {
    data class PendingMessage(
        val payload: VoicePayload,
        var attempts: Int,
        var lastSentEpochMs: Long
    )

    // Key: streamId:sequenceNumber
    private val pendingAcks = ConcurrentHashMap<String, PendingMessage>()
    private val sequenceGen = AtomicInteger(1)

    fun nextSequence(): Int = sequenceGen.getAndIncrement()

    fun registerFinalMessage(payload: VoicePayload) {
        val key = "${payload.streamId}:${payload.sequenceNumber}"
        pendingAcks[key] = PendingMessage(
            payload = payload,
            attempts = 1,
            lastSentEpochMs = System.currentTimeMillis()
        )
    }

    fun onAckReceived(streamId: Int, sequenceNumber: Int): Boolean {
        val key = "$streamId:$sequenceNumber"
        val removed = pendingAcks.remove(key)
        return removed != null
    }

    fun hasPendingMessage(streamId: Int, sequenceNumber: Int): Boolean {
        return pendingAcks.containsKey("$streamId:$sequenceNumber")
    }

    fun getPendingCount(): Int = pendingAcks.size

    fun checkAndRetransmit(retransmitFn: (VoicePayload) -> Boolean): Int {
        val now = System.currentTimeMillis()
        var retransmittedCount = 0

        for ((key, pending) in pendingAcks) {
            if (now - pending.lastSentEpochMs >= retransmitTimeoutMs) {
                if (pending.attempts < maxRetries) {
                    pending.attempts++
                    pending.lastSentEpochMs = now
                    retransmitFn(pending.payload)
                    retransmittedCount++
                } else {
                    // Retries exhausted, drop boundedly
                    pendingAcks.remove(key)
                }
            }
        }

        return retransmittedCount
    }
}
