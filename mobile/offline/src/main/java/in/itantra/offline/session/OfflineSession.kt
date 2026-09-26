package `in`.itantra.offline.session

import android.content.Context
import android.os.Build
import android.os.SystemClock
import `in`.itantra.offline.crypto.*
import `in`.itantra.offline.protocol.*
import `in`.itantra.offline.speech.*
import `in`.itantra.offline.transport.*
import kotlinx.coroutines.*
import java.util.UUID

/** Main-dispatcher session actor. Radio callbacks enqueue here; inference never runs here. */
class OfflineSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val notice: (String) -> Unit,
    private val discovered: (String, String) -> Unit,
    private val verification: (String, String) -> Unit,
    private val transcript: (SpeechPacket) -> Unit,
    private val speech: (Language, TextChunk) -> Unit,
    private val captureAllowed: (Boolean) -> Unit
) : AutoCloseable {
    private val trust = KnownPeersStore(context)
    private val identity = trust.identity()
    private val pairing = mutableMapOf<String, Pairing>()
    private val peers = java.util.concurrent.ConcurrentHashMap<String, Pairing>()
    private val addresses = mutableMapOf<String, String>()
    private val lastBle = mutableMapOf<String, Long>()
    private val announced = mutableSetOf<String>()
    private val capabilities = mutableMapOf<String, List<String>>()
    private val ledgers = linkedMapOf<Pair<UUID, Long>, ReceiveLedger>()
    private val finals = ReliableFinals(SystemClock::elapsedRealtime)
    private val floor = FloorCoordinator(SystemClock::elapsedRealtime)
    private var remoteFloor: UUID? = null
    private var remoteFloorUntil = 0L
    private var remoteEpoch = 0L
    private var sequence = 0
    private var wantedPtt = false
    private var pendingText: String? = null
    private val finalGrace = mutableMapOf<UUID, Long>()
    private var closed = false
    private var tick: Job? = null
    private var channelId = UUID.randomUUID()
    private var coordinator: UUID = identity.id
    val id get() = identity.id.toString()
    var language: Language = Language.HI
    var mode: OperatingMode = OperatingMode.WALKIE_TALKIE; private set
    val connectedPeers: Set<String> get() = peers.keys.toSet()
    val metrics = SessionMetrics(java.io.File(context.filesDir, "session-metrics"))
    private val ble = BleController(context, discovered,
        { address -> onBleConnected(address) }, { address -> onBleDisconnected(address) },
        { address, bytes -> onBleMessage(address, bytes) }, notice)
    private val wifi: WifiDirectController = WifiDirectController(context, identity.id, scope, { peers.containsKey(it) },
        ::authenticateSocket, ::verifySocket,
        { peer, bytes -> scope.launch { onEncrypted(peer, bytes) } }, { scope.launch { router.refresh() } }, notice)
    private val aware: WifiAwareController = WifiAwareController(context, identity.id, scope, { peers.containsKey(it) },
        ::authenticateSocket, ::verifySocket,
        { peer, bytes -> scope.launch { onEncrypted(peer, bytes) } }, { scope.launch { router.refresh() } },
        { scope.launch { peers.keys.toList().forEach(::connectWifi) } })
    val router: TransportManager = TransportManager(listOf(ble, wifi, aware))

    private fun authenticateSocket(peer: String, challenge: ByteArray): ByteArray =
        peers.getValue(peer).encrypt("socket-proof-v1".toByteArray() + challenge)
    private fun verifySocket(peer: String, challenge: ByteArray, proof: ByteArray): Boolean =
        runCatching { java.security.MessageDigest.isEqual(peers.getValue(peer).decrypt(proof), "socket-proof-v1".toByteArray() + challenge) }.getOrDefault(false)

    fun startDiscovery() {
        check(!closed)
        router.startDiscovery(); wifi.startDiscovery()
        if (tick == null) tick = scope.launch {
            var beats = 0
            while (isActive) { delay(500); heartbeat(++beats) }
        }
    }
    fun connect(address: String) { if (mode == OperatingMode.CONVERSATION && peers.isNotEmpty()) error("Conversation Mode supports one peer"); ble.connect(address) }
    private fun onBleConnected(address: String) {
        if (closed || address in pairing) return
        if (pairing.size >= 7 || (mode == OperatingMode.CONVERSATION && pairing.isNotEmpty())) { ble.drop(address); return }
        val handshake = Pairing(identity, SystemClock::elapsedRealtime, trust::trusted)
        pairing[address] = handshake
        check(ble.send(address, handshake.begin()))
    }
    private fun onBleMessage(address: String, bytes: ByteArray) {
        val handshake = pairing[address] ?: return
        try {
            if (bytes.firstOrNull() == 3.toByte()) {
                val peer = handshake.remoteId?.toString() ?: return
                onEncrypted(peer, bytes, bleArrival = true)
                return
            }
            handshake.receive(bytes)?.let { check(ble.send(address, it)) }
            if (handshake.sas != null && announced.add(address)) {
                if (handshake.known) check(ble.send(address, handshake.confirm()))
                else verification(address, handshake.sas!!)
            }
            ready(address, handshake)
        } catch (e: Exception) { notice("Pairing rejected: ${e.message}"); ble.drop(address) }
    }
    fun confirm(address: String, accepted: Boolean) {
        val handshake = pairing[address] ?: return
        if (!accepted) { handshake.reject(); ble.drop(address); return }
        try { check(ble.send(address, handshake.confirm())); ready(address, handshake) }
        catch (e: Exception) { notice("Pairing confirmation failed: ${e.message}"); ble.drop(address) }
    }
    private fun ready(address: String, handshake: Pairing) {
        if (!handshake.ready) return
        val peer = handshake.remoteId!!.toString()
        if (peers.containsKey(peer)) return
        trust.trust(handshake.remoteKey!!)
        peers[peer] = handshake; addresses[peer] = address; lastBle[peer] = SystemClock.elapsedRealtime()
        ble.bind(peer, address); router.updatePeers(peers.keys)
        notice("Authenticated peer connected • BLE fallback")
        sendCapabilities(peer)
    }
    private fun onBleDisconnected(address: String) {
        val old = pairing.remove(address)
        announced.remove(address)
        val peer = old?.remoteId?.toString() ?: return
        peers.remove(peer); lastBle.remove(peer); capabilities.remove(peer); router.updatePeers(peers.keys)
        captureAllowed(false)
        if (coordinator.toString() == peer) {
            coordinator = FloorCoordinator.elect(peers.keys.map(UUID::fromString) + identity.id)!!
            floor.newEpoch(maxOf(floor.epoch, remoteEpoch) + 1)
            remoteEpoch = floor.epoch; remoteFloor = null; wantedPtt = false
            if (coordinator == identity.id) wifi.createGroup()
            notice("Coordinator lost; channel recovering over BLE")
        }
        if (!closed && old?.known == true) scope.launch {
            repeat(3) { delay((it + 1) * 1500L); if (!closed && !peers.containsKey(peer)) runCatching { ble.connect(address) } }
        }
    }
    private fun sendCapabilities(peer: String) {
        val details = listOf(wifi.localAddress.orEmpty(), if (aware.supported) "1" else "0", language.tag,
            channelId.toString(), coordinator.toString(), mode.name, Build.MODEL.replace('|', ' ').take(40), (peers.size + 1).toString())
        send(peer, packet(MessageType.CAPABILITIES, text = details.joinToString("|")))
    }
    private fun connectWifi(peer: String) {
        if (closed || wifi.available(peer)) return
        val address = capabilities[peer]?.firstOrNull()?.takeIf(String::isNotBlank) ?: return
        // One initiator avoids simultaneous conflicting GO negotiations.
        if (id > peer || coordinator.toString() == peer) runCatching { wifi.connect(address, coordinator == identity.id) }
    }
    fun setMode(value: OperatingMode) {
        require(value != OperatingMode.CONVERSATION || peers.size <= 1) { "Disconnect group peers before entering Conversation Mode" }
        releaseFloor(); captureAllowed(false); mode = value; router.mode = value
        if (value == OperatingMode.WALKIE_TALKIE) aware.stopDiscovery()
        peers.keys.toList().forEach(::sendCapabilities)
    }
    fun requestFloor() {
        if (mode == OperatingMode.CONVERSATION) { captureAllowed(peers.size == 1); return }
        wantedPtt = true
        if (coordinator == identity.id) grant(identity.id)
        else send(coordinator.toString(), packet(MessageType.FLOOR_REQUEST))
    }
    fun releaseFloor() {
        wantedPtt = false; captureAllowed(false)
        if (mode == OperatingMode.CONVERSATION) return
        if (coordinator == identity.id) { floor.release(identity.id, floor.epoch); announceFloor() }
        else send(coordinator.toString(), packet(MessageType.FLOOR_RELEASE, text = remoteEpoch.toString()))
    }
    private fun grant(peer: UUID) {
        val granted = floor.request(peer)
        if (granted == null) { if (peer == identity.id) notice("Channel busy"); return }
        finalGrace[peer] = granted.expiresAtMs + 5000
        announceFloor()
        if (peer == identity.id && pendingText != null) { sendPendingText(); return }
        if (peer == identity.id && wantedPtt) captureAllowed(true)
    }
    private fun announceFloor() {
        val lease = floor.current()
        val body = "${floor.epoch}|${lease?.holder ?: ""}|${lease?.let { maxOf(0, it.expiresAtMs - SystemClock.elapsedRealtime()) } ?: 0}"
        val update = packet(MessageType.FLOOR_GRANT, text = body)
        peers.keys.toList().forEach { send(it, update) }
    }
    fun outgoing(stream: Long, source: Language, chunk: TextChunk, final: Boolean) {
        if (closed || peers.isEmpty()) return
        if (mode == OperatingMode.WALKIE_TALKIE) {
            val holder = if (coordinator == identity.id) floor.current()?.holder else remoteFloor?.takeIf { remoteFloorUntil > SystemClock.elapsedRealtime() }
            if (holder != identity.id) { notice("Speech was not transmitted: channel floor is not held"); return }
        }
        val message = packet(if (final) MessageType.FINAL else MessageType.PARTIAL, stream, chunk.text, source, chunk.offset)
        transcript(message)
        val destinations = if (mode == OperatingMode.WALKIE_TALKIE && coordinator != identity.id) setOf(coordinator.toString()) else peers.keys.toSet()
        destinations.forEach { destination ->
            if (final) { finals.track(destination, message); send(destination, message) }
            else if (router.preferred(destination)?.radio != Radio.BLE) send(destination, message)
        }
    }
    fun sendText(text: String) {
        require(text.isNotBlank())
        require(text.toByteArray().size <= SpeechPacket.MAX_TEXT_BYTES)
        require(peers.isNotEmpty()) { "Pair with a nearby phone first" }
        require(pendingText == null) { "A typed message is already waiting for the floor" }
        if (mode == OperatingMode.CONVERSATION) outgoing(SystemClock.elapsedRealtimeNanos(), language, TextChunk(0, text), true)
        else { pendingText = text; requestFloor() }
    }
    private fun sendPendingText() {
        val text = pendingText ?: return
        pendingText = null
        outgoing(SystemClock.elapsedRealtimeNanos(), language, TextChunk(0, text), true)
        releaseFloor()
    }
    private fun packet(type: MessageType, stream: Long = 0, text: String = "", source: Language = language, offset: Int = 0): SpeechPacket {
        check(sequence < Int.MAX_VALUE) { "Session sequence exhausted; reconnect" }
        return SpeechPacket(type, identity.id, stream, sequence++, source, offset, text = text)
    }
    private fun send(peer: String, packet: SpeechPacket, forceBle: Boolean = false): Boolean {
        val handshake = peers[peer] ?: return false
        val encrypted = handshake.encrypt(packet.encode())
        val sent = if (forceBle) ble.send(peer, encrypted) else router.sendToPeer(peer, encrypted)
        if (sent) metrics.record("message_sent_ns", SystemClock.elapsedRealtimeNanos(), packet.stream)
        return sent
    }
    private fun onEncrypted(peer: String, encrypted: ByteArray, bleArrival: Boolean = false) {
        val handshake = peers[peer] ?: return
        try {
            val message = SpeechPacket.decode(handshake.decrypt(encrypted))
            require(message.sender.toString() == peer || (mode == OperatingMode.WALKIE_TALKIE && coordinator.toString() == peer && message.type in setOf(MessageType.PARTIAL, MessageType.FINAL)))
            if (bleArrival) lastBle[peer] = SystemClock.elapsedRealtime()
            metrics.record("message_received_ns", SystemClock.elapsedRealtimeNanos(), message.stream)
            when (message.type) {
                MessageType.HEARTBEAT -> Unit
                MessageType.CAPABILITIES -> {
                    val fields = message.text.split('|'); require(fields.size == 8 && fields[7].toInt() in 2..8)
                    UUID.fromString(fields[3]); val advertisedHub = UUID.fromString(fields[4])
                    capabilities[peer] = fields
                    // Joining a hub with existing members preserves its channel identity.
                    if (peers.size == 1 && (peer < id || fields[7].toInt() > 2) && (advertisedHub.toString() == peer || peers.containsKey(advertisedHub.toString()))) {
                        channelId = UUID.fromString(fields[3]); coordinator = advertisedHub
                    }
                    if (mode == OperatingMode.CONVERSATION && fields[5] == OperatingMode.CONVERSATION.name && fields[1] == "1" && aware.supported)
                        aware.connect(peer, handshake.awarePassphrase())
                    else connectWifi(peer)
                }
                MessageType.ACK -> finals.acknowledge(peer, UUID.fromString(message.text), message.stream, message.sequence)
                MessageType.FLOOR_REQUEST -> if (mode == OperatingMode.WALKIE_TALKIE && coordinator == identity.id) grant(message.sender)
                MessageType.FLOOR_RELEASE -> if (mode == OperatingMode.WALKIE_TALKIE && coordinator == identity.id) { floor.release(message.sender, message.text.toLong()); announceFloor() }
                MessageType.FLOOR_GRANT -> if (mode == OperatingMode.WALKIE_TALKIE && peer == coordinator.toString()) {
                    val parts = message.text.split('|'); require(parts.size == 3)
                    val epoch = parts[0].toLong(); require(epoch >= remoteEpoch)
                    val duration = parts[2].toLong(); require(duration in 0..5000)
                    remoteEpoch = epoch; remoteFloor = parts[1].takeIf(String::isNotBlank)?.let(UUID::fromString)
                    remoteFloorUntil = SystemClock.elapsedRealtime() + duration
                    if (remoteFloor == identity.id && duration > 0 && pendingText != null) sendPendingText()
                    else captureAllowed(wantedPtt && remoteFloor == identity.id && duration > 0)
                    if (remoteFloor != null && remoteFloor != identity.id) notice("Channel busy")
                }
                MessageType.PARTIAL, MessageType.FINAL -> receiveSpeech(peer, message)
            }
        } catch (e: Exception) { notice("Rejected incoming frame: ${e.message}") }
    }
    private fun receiveSpeech(peer: String, message: SpeechPacket) {
        val key = message.sender to message.stream
        if (mode == OperatingMode.WALKIE_TALKIE && coordinator == identity.id && ledgers[key]?.finished != true) {
            require(floor.current()?.holder == message.sender ||
                (message.type == MessageType.FINAL && (finalGrace[message.sender] ?: 0) > SystemClock.elapsedRealtime())) { "Sender does not hold the floor" }
        }
        if (message.type == MessageType.FINAL) {
            val ack = SpeechPacket(MessageType.ACK, identity.id, message.stream, message.sequence, message.language, text = message.sender.toString())
            send(peer, ack)
        }
        val ledger = ledgers.getOrPut(key) { ReceiveLedger() }
        if (ledger.finished) return
        while (ledgers.size > 256) ledgers.remove(ledgers.keys.first())
        val chunks = if (message.type == MessageType.FINAL) ledger.final(message.text) else ledger.partial(message.offset, message.text)
        transcript(message); chunks.forEach { speech(message.language, it) }
        if (ledger.revised) notice("Final transcript revised previously spoken words; audio was not repeated")
        if (mode == OperatingMode.WALKIE_TALKIE && coordinator == identity.id) {
            peers.keys.filter { it != peer }.forEach { recipient ->
                if (message.type == MessageType.FINAL) { finals.track(recipient, message); send(recipient, message) }
                else if (router.preferred(recipient)?.radio != Radio.BLE) send(recipient, message)
            }
        }
    }
    private fun heartbeat(beats: Int) {
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        peers.keys.toList().forEach { peer ->
            // Large BLE fragmented finals may occupy the queue longer than a heartbeat interval.
            if (now - (lastBle[peer] ?: now) > 15_000) { addresses[peer]?.let(ble::drop) }
            else {
                send(peer, packet(MessageType.HEARTBEAT), forceBle = true)
                if (router.preferred(peer)?.radio != Radio.BLE) send(peer, packet(MessageType.HEARTBEAT))
                if (beats % 10 == 0) sendCapabilities(peer)
            }
        }
        if (mode == OperatingMode.WALKIE_TALKIE) {
            if (wantedPtt && beats % 4 == 0) {
                if (coordinator == identity.id) grant(identity.id) else send(coordinator.toString(), packet(MessageType.FLOOR_REQUEST))
            }
            if (coordinator != identity.id && remoteFloorUntil <= now) { remoteFloor = null; captureAllowed(false) }
        }
        finals.tick({ peer, message -> send(peer, message) }, { notice("Final message not acknowledged after bounded retries") })
        router.refresh()
    }
    override fun close() { closed = true; tick?.cancel(); captureAllowed(false); router.close(); pairing.clear(); peers.clear(); finals.clear() }
}
