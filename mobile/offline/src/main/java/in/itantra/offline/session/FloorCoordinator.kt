package `in`.itantra.offline.session

import java.util.UUID

enum class OperatingMode { CONVERSATION, WALKIE_TALKIE }
data class FloorLease(val holder: UUID, val epoch: Long, val expiresAtMs: Long)

/** Owned by one session coroutine. Time is injected and monotonic; grants originate at the hub. */
class FloorCoordinator(private val now: () -> Long, private val leaseMs: Long = 5000) {
    var epoch: Long = 0; private set
    private var lease: FloorLease? = null
    fun current(): FloorLease? = lease?.takeIf { it.epoch == epoch && it.expiresAtMs > now() }
    fun request(peer: UUID): FloorLease? {
        if (current()?.holder?.let { it != peer } == true) return null
        return FloorLease(peer, epoch, now() + leaseMs).also { lease = it }
    }
    fun release(peer: UUID, requestEpoch: Long) {
        if (requestEpoch == epoch && lease?.holder == peer) lease = null
    }
    fun newEpoch(value: Long) { require(value > epoch); epoch = value; lease = null }
    fun mayCapture(mode: OperatingMode, peer: UUID): Boolean =
        mode == OperatingMode.CONVERSATION || current()?.holder == peer
    companion object {
        fun elect(eligible: Collection<UUID>): UUID? = eligible.minByOrNull(UUID::toString)
    }
}
