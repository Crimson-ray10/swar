package `in`.itantra.mobile.audio

import `in`.itantra.mobile.protocol.MessageType
import `in`.itantra.mobile.protocol.PayloadSecurity
import `in`.itantra.mobile.protocol.ReliabilityEngine
import `in`.itantra.mobile.protocol.VoicePayload
import `in`.itantra.mobile.stt.StreamingSttPipeline
import `in`.itantra.mobile.transport.TransportManager
import `in`.itantra.mobile.tts.IndicTtsPipeline
import `in`.itantra.mobile.tts.JitterBuffer
import `in`.itantra.mobile.tts.JitterBufferItem
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

data class LatencyMetrics(
    val micInputEpochMs: Long,
    val sttStableEpochMs: Long,
    val messageSentEpochMs: Long,
    val messageRecvEpochMs: Long,
    val ttsStartEpochMs: Long
) {
    val sttStabilityMs: Long get() = sttStableEpochMs - micInputEpochMs
    val transportHopMs: Long get() = messageRecvEpochMs - messageSentEpochMs
    val ttsStartMs: Long get() = ttsStartEpochMs - messageRecvEpochMs
    val totalMouthToEarMs: Long get() = ttsStartEpochMs - micInputEpochMs

    fun logSummary(): String {
        return "Latency [Mouth-to-Ear: ${totalMouthToEarMs}ms] (STT-stability: ${sttStabilityMs}ms, Transport-hop: ${transportHopMs}ms, TTS-start: ${ttsStartMs}ms)"
    }
}

/**
 * End-to-End Conversation Pipeline (1-to-1 Full Duplex):
 * Mic -> VAD -> STT -> StabilityBuffer -> Encoder -> Transport -> Decoder -> JitterBuffer -> TTS -> Playback
 * Fully instrumented with per-session mouth-to-ear budget tracking.
 */
class ConversationSession(
    val localDeviceId: String,
    val targetPeerId: String,
    val transportManager: TransportManager,
    val sttPipeline: StreamingSttPipeline,
    val ttsPipeline: IndicTtsPipeline,
    val sessionAeadKey: ByteArray,
    val languageCodeByte: Byte = 1 // 1=HI, 2=EN
) {
    private val reliabilityEngine = ReliabilityEngine(localDeviceId)
    private val jitterBuffer = JitterBuffer(targetDelayMs = 100)
    private var streamIdGen = 1000

    val sessionLatencyLog = CopyOnWriteArrayList<LatencyMetrics>()
    var onReceivedSpeechPlayed: ((text: String, metrics: LatencyMetrics) -> Unit)? = null

    init {
        // Wire incoming transport messages
        transportManager.onMessageReceived = { senderId, rawBytes ->
            if (senderId == targetPeerId) {
                handleIncomingWirePayload(rawBytes)
            }
        }
    }

    /**
     * Called when mic inputs speech.
     * Encodes timestamps, encrypts with AEAD, and sends over TransportManager.
     */
    fun onUserSpeechChunk(
        pcmFrame: ShortArray,
        micTimestampMs: Long = System.currentTimeMillis(),
        simulatedProb: Float? = null,
        simulatedTokens: List<String>? = null,
        isFinalUtterance: Boolean = false
    ) {
        val streamId = streamIdGen

        sttPipeline.onStablePartial = { stablePrefix, newlyStabilized ->
            val sttTimestamp = System.currentTimeMillis()
            sendVoiceChunk(
                streamId = streamId,
                text = newlyStabilized ?: stablePrefix,
                isFinal = false,
                micTimestamp = micTimestampMs,
                sttTimestamp = sttTimestamp
            )
        }

        sttPipeline.processAudioFrame(pcmFrame, simulatedProb, simulatedTokens)

        if (isFinalUtterance) {
            val finalText = sttPipeline.finalizeUtterance()
            if (finalText.isNotEmpty()) {
                val sttTimestamp = System.currentTimeMillis()
                sendVoiceChunk(
                    streamId = streamId,
                    text = finalText,
                    isFinal = true,
                    micTimestamp = micTimestampMs,
                    sttTimestamp = sttTimestamp
                )
            }
            streamIdGen++
        }
    }

    private fun sendVoiceChunk(
        streamId: Int,
        text: String,
        isFinal: Boolean,
        micTimestamp: Long,
        sttTimestamp: Long
    ) {
        val seq = reliabilityEngine.nextSequence()
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)

        // Embed timing header: [8B micTimestamp] [8B sttTimestamp] [8B sentTimestamp] [text]
        val sentTimestamp = System.currentTimeMillis()
        val plainBuf = ByteBuffer.allocate(24 + textBytes.size)
        plainBuf.putLong(micTimestamp)
        plainBuf.putLong(sttTimestamp)
        plainBuf.putLong(sentTimestamp)
        plainBuf.put(textBytes)

        val nonce = ByteArray(12).also {
            it[0] = (seq and 0xFF).toByte()
            it[1] = (streamId and 0xFF).toByte()
        }

        // 29-byte AAD header
        val flags = (if (isFinal) 1 else 0) or 4 // isEncrypted
        val type = if (isFinal) MessageType.STT_FINAL else MessageType.STT_PARTIAL
        val dummyAad = ByteArray(VoicePayload.HEADER_SIZE) { 0x01 }

        val encryptedPayload = PayloadSecurity.encrypt(sessionAeadKey, nonce, plainBuf.array(), dummyAad)

        val payload = VoicePayload(
            type = type,
            senderDeviceId = localDeviceId,
            streamId = streamId,
            sequenceNumber = seq,
            languageCode = languageCodeByte,
            flags = flags.toByte(),
            payloadBytes = encryptedPayload
        )

        val wireBytes = payload.encode()
        if (isFinal) {
            reliabilityEngine.registerFinalMessage(payload)
        }

        transportManager.sendToPeer(targetPeerId, wireBytes)
    }

    private fun handleIncomingWirePayload(wireBytes: ByteArray) {
        val recvTimestamp = System.currentTimeMillis()
        val payload = VoicePayload.decode(wireBytes)

        if (payload.type == MessageType.ACK) {
            reliabilityEngine.onAckReceived(payload.streamId, payload.sequenceNumber)
            return
        }

        // If stt_final received, immediately emit ACK back to sender
        if (payload.type == MessageType.STT_FINAL) {
            val ack = VoicePayload.createAck(localDeviceId, payload.streamId, payload.sequenceNumber)
            transportManager.sendToPeer(targetPeerId, ack.encode())
        }

        // Decrypt payload
        val nonce = ByteArray(12).also {
            it[0] = (payload.sequenceNumber and 0xFF).toByte()
            it[1] = (payload.streamId and 0xFF).toByte()
        }
        val dummyAad = ByteArray(VoicePayload.HEADER_SIZE) { 0x01 }
        val decryptedPlain = PayloadSecurity.decrypt(sessionAeadKey, nonce, payload.payloadBytes, dummyAad)

        val buf = ByteBuffer.wrap(decryptedPlain)
        val micTs = buf.long
        val sttTs = buf.long
        val sentTs = buf.long
        val textBytes = ByteArray(buf.remaining())
        buf.get(textBytes)
        val text = String(textBytes, StandardCharsets.UTF_8)

        // Queue into jitter buffer
        val jItem = JitterBufferItem(
            streamId = payload.streamId,
            sequenceNumber = payload.sequenceNumber,
            text = text,
            languageCode = payload.languageCode,
            isFinal = payload.isFinal,
            arrivalEpochMs = recvTimestamp
        )
        jitterBuffer.push(jItem)

        // Poll ready items for TTS synthesis and playback
        val ready = jitterBuffer.pollReady(recvTimestamp + 150)
        if (ready != null) {
            val ttsStartTs = System.currentTimeMillis()
            val langStr = if (ready.languageCode.toInt() == 2) "en" else "hi"
            val synthResult = ttsPipeline.synthesize(ready.text, langStr)

            val metrics = LatencyMetrics(
                micInputEpochMs = micTs,
                sttStableEpochMs = sttTs,
                messageSentEpochMs = sentTs,
                messageRecvEpochMs = recvTimestamp,
                ttsStartEpochMs = ttsStartTs
            )
            sessionLatencyLog.add(metrics)
            onReceivedSpeechPlayed?.invoke(ready.text, metrics)
        }
    }
}
