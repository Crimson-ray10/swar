package `in`.itantra.mobile.tts

import java.util.PriorityQueue

data class JitterBufferItem(
    val streamId: Int,
    val sequenceNumber: Int,
    val text: String,
    val languageCode: Byte,
    val isFinal: Boolean,
    val arrivalEpochMs: Long = System.currentTimeMillis()
) : Comparable<JitterBufferItem> {
    override fun compareTo(other: JitterBufferItem): Int {
        return if (this.streamId != other.streamId) {
            this.streamId.compareTo(other.streamId)
        } else {
            this.sequenceNumber.compareTo(other.sequenceNumber)
        }
    }
}

class JitterBuffer(
    val targetDelayMs: Long = 150,
    val maxQueueSize: Int = 16
) {
    private val queue = PriorityQueue<JitterBufferItem>()
    private var lastEmittedSeq = -1
    private var currentStreamId = -1

    @Synchronized
    fun push(item: JitterBufferItem) {
        if (item.streamId != currentStreamId) {
            currentStreamId = item.streamId
            lastEmittedSeq = -1
            queue.clear()
        }

        // Drop duplicate sequence numbers
        if (item.sequenceNumber <= lastEmittedSeq && lastEmittedSeq != -1) {
            return
        }

        if (queue.none { it.sequenceNumber == item.sequenceNumber }) {
            queue.add(item)
        }

        if (queue.size > maxQueueSize) {
            // Drop oldest
            queue.poll()
        }
    }

    @Synchronized
    fun pollReady(now: Long = System.currentTimeMillis()): JitterBufferItem? {
        val peek = queue.peek() ?: return null

        // If target delay elapsed or it is the immediate expected next sequence
        val delayElapsed = (now - peek.arrivalEpochMs) >= targetDelayMs
        val isImmediateNext = (lastEmittedSeq == -1 || peek.sequenceNumber == lastEmittedSeq + 1)

        if (delayElapsed || isImmediateNext || queue.size >= 4) {
            val item = queue.poll()
            lastEmittedSeq = item.sequenceNumber
            return item
        }
        return null
    }

    @Synchronized
    fun clear() {
        queue.clear()
        lastEmittedSeq = -1
    }

    val size: Int @Synchronized get() = queue.size
}
