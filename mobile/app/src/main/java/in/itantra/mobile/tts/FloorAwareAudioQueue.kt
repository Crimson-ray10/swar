package `in`.itantra.mobile.tts

import java.util.ArrayDeque

data class PlaybackChunk(
    val senderDeviceId: String,
    val audioSamples: ShortArray,
    val text: String,
    val sampleRate: Int = 16000
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlaybackChunk) return false
        return senderDeviceId == other.senderDeviceId &&
                audioSamples.contentEquals(other.audioSamples) &&
                text == other.text &&
                sampleRate == other.sampleRate
    }

    override fun hashCode(): Int {
        var result = senderDeviceId.hashCode()
        result = 31 * result + audioSamples.contentHashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + sampleRate
        return result
    }
}

/**
 * Ensures sequential playback across multiple speakers:
 * Mixed overlapping TTS speech is strictly prevented. Audio chunks from different senders
 * are serialized sequentially into the active playback channel.
 */
class FloorAwareAudioQueue {
    private val queue = ArrayDeque<PlaybackChunk>()
    private var currentlyPlayingSender: String? = null
    var isPlaying: Boolean = false
        private set

    @Synchronized
    fun enqueue(chunk: PlaybackChunk) {
        queue.addLast(chunk)
    }

    @Synchronized
    fun pollNextChunk(): PlaybackChunk? {
        val next = queue.pollFirst()
        if (next != null) {
            currentlyPlayingSender = next.senderDeviceId
            isPlaying = true
        } else {
            currentlyPlayingSender = null
            isPlaying = false
        }
        return next
    }

    @Synchronized
    fun getCurrentlyPlayingSender(): String? = currentlyPlayingSender

    @Synchronized
    fun getPendingCount(): Int = queue.size

    @Synchronized
    fun clear() {
        queue.clear()
        currentlyPlayingSender = null
        isPlaying = false
    }
}
