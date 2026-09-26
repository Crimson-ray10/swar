package `in`.itantra.offline.transport

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.security.SecureRandom
import `in`.itantra.offline.crypto.sha256

/** Each socket has independent reader and writer jobs. Never perform a socket read under a write lock. */
class SocketLinks(
    private val localId: UUID,
    private val scope: CoroutineScope,
    private val allowed: (String) -> Boolean,
    private val authenticate: (String, ByteArray) -> ByteArray,
    private val verify: (String, ByteArray, ByteArray) -> Boolean,
    private val received: (String, ByteArray) -> Unit,
    private val changed: () -> Unit
) : AutoCloseable {
    private class Link(val socket: Socket, val outgoing: Channel<ByteArray>)
    private val links = ConcurrentHashMap<String, Link>()
    private val pending = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var closed = false
    fun attach(socket: Socket, expectedPeer: String? = null) {
        if (closed || pending.size + links.size >= 8) { socket.close(); return }
        pending += socket
        scope.launch(Dispatchers.IO) {
            var id: String? = null
            var link: Link? = null
            var writer: Job? = null
            try {
                socket.soTimeout = 5000; socket.tcpNoDelay = true
                val input = DataInputStream(socket.getInputStream()); val output = DataOutputStream(socket.getOutputStream())
                val localNonce = ByteArray(32).also(SecureRandom()::nextBytes)
                output.writeLong(localId.mostSignificantBits); output.writeLong(localId.leastSignificantBits); output.write(localNonce); output.flush()
                val peer = UUID(input.readLong(), input.readLong()).toString()
                require(peer != localId.toString() && allowed(peer) && (expectedPeer == null || expectedPeer == peer))
                val remoteNonce = ByteArray(32); input.readFully(remoteNonce)
                val binding = sha256(if (localId.toString() < peer) localNonce + remoteNonce else remoteNonce + localNonce)
                val proof = authenticate(peer, binding)
                require(proof.size in 1..512)
                output.writeInt(proof.size); output.write(proof); output.flush()
                val proofSize = input.readInt(); require(proofSize in 1..512)
                val remoteProof = ByteArray(proofSize); input.readFully(remoteProof)
                require(verify(peer, binding, remoteProof)) { "Socket did not prove the paired identity" }
                id = peer
                socket.soTimeout = 5000
                link = Link(socket, Channel(64))
                val active = link
                check(!closed)
                check(links.putIfAbsent(peer, active) == null) { "Duplicate socket" }
                pending -= socket
                changed()
                writer = launch(Dispatchers.IO) {
                    try { for (bytes in active.outgoing) { output.writeInt(bytes.size); output.write(bytes); output.flush() } }
                    finally { socket.close() }
                }
                while (isActive) {
                    val size = input.readInt(); require(size in 1..Fragmentation.LIMIT)
                    val frame = ByteArray(size); input.readFully(frame)
                    received(peer, frame)
                }
            } catch (_: Exception) {
                // Link loss is observed via changed(); pending finals remain in the session layer.
            } finally {
                pending -= socket
                if (id != null && link != null) links.remove(id, link)
                link?.outgoing?.close(); writer?.cancel(); socket.close(); changed()
            }
        }
    }
    fun available(peer: String) = links.containsKey(peer)
    fun send(peer: String, bytes: ByteArray): Boolean = links[peer]?.outgoing?.trySend(bytes.copyOf())?.isSuccess == true
    fun disconnectAll() { pending.forEach { runCatching { it.close() } }; links.values.forEach { it.outgoing.close(); runCatching { it.socket.close() } }; links.clear(); changed() }
    override fun close() { closed = true; disconnectAll() }
}
