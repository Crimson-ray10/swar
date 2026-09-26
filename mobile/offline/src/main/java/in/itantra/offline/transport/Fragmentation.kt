package `in`.itantra.offline.transport

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** GATT fragmentation independent of negotiated MTU. One bounded in-flight message per link. */
object Fragmentation {
    const val LIMIT = 16384
    fun split(id: Int, bytes: ByteArray, payloadSize: Int = 20): List<ByteArray> {
        require(bytes.size in 1..LIMIT && payloadSize in 13..512)
        val capacity = payloadSize - 12
        val count = (bytes.size + capacity - 1) / capacity
        return (0 until count).map { index ->
            val body = bytes.copyOfRange(index * capacity, minOf(bytes.size, (index + 1) * capacity))
            ByteBuffer.allocate(12 + body.size).putInt(id).putInt(index).putInt(count).put(body).array()
        }
    }
}

class FragmentAssembler(private val now: () -> Long) {
    private var id = -1
    private var next = 0
    private var count = 0
    private var started = 0L
    private var output = ByteArrayOutputStream()
    fun accept(frame: ByteArray): ByteArray? {
        require(frame.size in 13..512)
        val b = ByteBuffer.wrap(frame)
        val frameId = b.int; val index = b.int; val total = b.int
        require(total in 1..2048 && index in 0 until total)
        if (index == 0) { id = frameId; next = 0; count = total; output = ByteArrayOutputStream(); started = now() }
        require(now() - started <= 30_000) { "Fragment timeout" }
        require(frameId == id && index == next && total == count) { "Out-of-order fragment" }
        require(output.size() + b.remaining() <= Fragmentation.LIMIT)
        output.write(frame, 12, frame.size - 12); next++
        return if (next == count) output.toByteArray().also { id = -1; next = 0; output.reset() } else null
    }
}
