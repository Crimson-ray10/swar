package `in`.itantra.mobile.audio

import `in`.itantra.mobile.protocol.FloorController
import `in`.itantra.mobile.protocol.MessageType
import `in`.itantra.mobile.protocol.VoicePayload
import `in`.itantra.mobile.stt.StreamingSttPipeline
import `in`.itantra.mobile.transport.TransportManager
import `in`.itantra.mobile.tts.FloorAwareAudioQueue
import `in`.itantra.mobile.tts.IndicTtsPipeline
import `in`.itantra.mobile.tts.PlaybackChunk
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

class WalkieTalkieBroadcastSession(
    val localDeviceId: String,
    val isGroupOwnerHub: Boolean,
    val transportManager: TransportManager,
    val sttPipeline: StreamingSttPipeline,
    val ttsPipeline: IndicTtsPipeline,
    val languageCodeByte: Byte = 1 // 1=HI
) {
    val floorController = FloorController(localDeviceId, isHub = isGroupOwnerHub)
    private val audioQueue = FloorAwareAudioQueue()
    private var streamIdGen = 5000

    val receivedTranscripts = CopyOnWriteArrayList<Pair<String, String>>() // senderId -> text
    var onSpeechPlayed: ((senderId: String, text: String) -> Unit)? = null
    var onChannelBusyChanged: ((isBusy: Boolean, holderId: String?) -> Unit)? = null

    init {
        floorController.onFloorStateChanged = { state ->
            when (state) {
                is FloorController.FloorState.Busy -> {
                    onChannelBusyChanged?.invoke(true, state.holderDeviceId)
                }
                is FloorController.FloorState.Granted -> {
                    onChannelBusyChanged?.invoke(false, state.holderDeviceId)
                }
                is FloorController.FloorState.Open -> {
                    onChannelBusyChanged?.invoke(false, null)
                }
                else -> {}
            }
        }

        transportManager.onMessageReceived = { senderId, rawBytes ->
            handleIncomingBroadcastPayload(senderId, rawBytes)
        }
    }

    /**
     * User presses Push-To-Talk button.
     * Returns true if floor request sent or granted, false if channel is busy and mic suppressed.
     */
    fun pressPushToTalk(): Boolean {
        if (floorController.shouldSuppressLocalMic) {
            return false // Blocked: floor held by someone else
        }
        return floorController.requestFloor { grantOrReleasePayload ->
            broadcastPayload(grantOrReleasePayload)
        }
    }

    /**
     * User releases Push-To-Talk button or silence timeout triggers.
     */
    fun releasePushToTalk() {
        if (floorController.isFloorHeldByMe) {
            val finalText = sttPipeline.finalizeUtterance()
            if (finalText.isNotEmpty()) {
                val finalPayload = VoicePayload(
                    type = MessageType.STT_FINAL,
                    senderDeviceId = localDeviceId,
                    streamId = streamIdGen++,
                    sequenceNumber = 1,
                    languageCode = languageCodeByte,
                    flags = 1, // isFinal
                    payloadBytes = finalText.toByteArray(StandardCharsets.UTF_8)
                )
                broadcastPayload(finalPayload)
            }
            floorController.releaseFloor { releasePayload ->
                broadcastPayload(releasePayload)
            }
        }
    }

    /**
     * Audio chunk captured from mic during PTT press.
     * Blocked if local mic should be suppressed.
     */
    fun onMicPcmChunk(pcmFrame: ShortArray, simulatedTokens: List<String>? = null): Boolean {
        if (floorController.shouldSuppressLocalMic || !floorController.isFloorHeldByMe) {
            return false // Microphone suppressed
        }

        sttPipeline.onStablePartial = { stablePrefix, newlyStabilized ->
            val partialPayload = VoicePayload(
                type = MessageType.STT_PARTIAL,
                senderDeviceId = localDeviceId,
                streamId = streamIdGen,
                sequenceNumber = 1,
                languageCode = languageCodeByte,
                flags = 0,
                payloadBytes = (newlyStabilized ?: stablePrefix).toByteArray(StandardCharsets.UTF_8)
            )
            broadcastPayload(partialPayload)
        }

        sttPipeline.processAudioFrame(pcmFrame, simulatedTokens = simulatedTokens)
        return true
    }

    private fun broadcastPayload(payload: VoicePayload) {
        val wireBytes = payload.encode()
        transportManager.broadcast(wireBytes)
    }

    private fun handleIncomingBroadcastPayload(senderId: String, rawBytes: ByteArray) {
        val payload = VoicePayload.decode(rawBytes)

        // Hub-as-hub broadcast forwarding:
        if (isGroupOwnerHub && payload.senderDeviceId != localDeviceId) {
            // Forward once to all connected group members
            transportManager.broadcast(rawBytes)
        }

        // Process floor control frames
        if (payload.type == MessageType.FLOOR_REQUEST ||
            payload.type == MessageType.FLOOR_GRANT ||
            payload.type == MessageType.FLOOR_RELEASE) {
            floorController.handleIncomingPayload(payload) { responsePayload ->
                broadcastPayload(responsePayload)
            }
            return
        }

        // Process speech payloads (STT_PARTIAL / STT_FINAL)
        if (payload.type == MessageType.STT_FINAL || payload.type == MessageType.STT_PARTIAL) {
            if (payload.senderDeviceId == localDeviceId) return // ignore echo of own broadcast

            val text = String(payload.payloadBytes, StandardCharsets.UTF_8)
            receivedTranscripts.add(Pair(payload.senderDeviceId, text))

            // Synthesize and queue in floor-aware audio queue to avoid mixed overlap
            val langStr = if (payload.languageCode.toInt() == 2) "en" else "hi"
            val synth = ttsPipeline.synthesize(text, langStr)

            val chunk = PlaybackChunk(
                senderDeviceId = payload.senderDeviceId,
                audioSamples = synth.audioSamples,
                text = text,
                sampleRate = synth.sampleRate
            )
            audioQueue.enqueue(chunk)

            // Play out sequentially
            val readyChunk = audioQueue.pollNextChunk()
            if (readyChunk != null) {
                onSpeechPlayed?.invoke(readyChunk.senderDeviceId, readyChunk.text)
            }
        }
    }
}
