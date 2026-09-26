package `in`.itantra.offline.speech

fun words(text: String): List<String> = text.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
data class TextChunk(val offset: Int, val text: String)

/** Commit only an unchanged prefix. A later revision never rewinds audio already committed. */
class StableText(private val steps: Int = 3) {
    private var previous = emptyList<String>()
    private var counts = emptyList<Int>()
    private var committed = emptyList<String>()
    init { require(steps >= 1) }
    fun update(hypothesis: String, boundary: Boolean = false): TextChunk? {
        val tokens = words(hypothesis)
        var samePrefix = true
        counts = tokens.mapIndexed { index, token ->
            samePrefix = samePrefix && previous.getOrNull(index) == token
            if (samePrefix) (counts.getOrNull(index) ?: 0) + 1 else 1
        }
        previous = tokens
        // A mutable suffix must never cause already-spoken text to be retransmitted.
        if (tokens.take(committed.size) != committed) return null
        val end = if (boundary) tokens.size else counts.takeWhile { it >= steps }.size
        if (end <= committed.size) return null
        val chunk = TextChunk(committed.size, tokens.subList(committed.size, end).joinToString(" "))
        committed = tokens.take(end)
        return chunk
    }
    fun reset() { previous = emptyList(); counts = emptyList(); committed = emptyList() }
}

/** One instance per remote stream. Bounded reordering, deduplication, and final-gap recovery. */
class ReceiveLedger(private val maxWords: Int = 4096) {
    private val pending = sortedMapOf<Int, List<String>>()
    private val committed = mutableListOf<String>()
    var finished = false; private set
    var revised = false; private set
    fun partial(offset: Int, text: String): List<TextChunk> {
        val tokens = words(text)
        require(offset >= 0 && offset.toLong() + tokens.size <= maxWords)
        if (finished || tokens.isEmpty() || offset < committed.size) return emptyList()
        val existing = pending[offset]
        require(existing == null || existing == tokens) { "Conflicting committed chunk" }
        require(pending.size < 128 || existing != null) { "Reordering buffer full" }
        pending[offset] = tokens
        val chunks = mutableListOf<TextChunk>()
        while (true) {
            val next = pending.remove(committed.size) ?: break
            chunks += TextChunk(committed.size, next.joinToString(" "))
            committed += next
        }
        return chunks
    }
    fun final(text: String): List<TextChunk> {
        val tokens = words(text)
        require(tokens.size <= maxWords)
        if (finished) return emptyList()
        finished = true
        pending.clear()
        revised = tokens.take(committed.size) != committed
        // A revised prefix has no reliable positional tail: retain its transcript, avoid false audio.
        if (revised || tokens.size <= committed.size) return emptyList()
        val chunk = TextChunk(committed.size, tokens.drop(committed.size).joinToString(" "))
        committed += tokens.drop(committed.size)
        return listOf(chunk)
    }
}
