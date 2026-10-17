package `in`.itantra.mobile.protocol

import java.nio.charset.StandardCharsets

class FloorController(
    val localDeviceId: String,
    val isHub: Boolean = false,
    val floorTimeoutMs: Long = 15000 // max 15s push-to-talk floor hold before auto-release
) {
    companion object {
        const val CONTENTION_WINDOW_MS = 200L
    }

    sealed class FloorState {
        object Open : FloorState()
        data class Busy(val holderDeviceId: String, val expiryEpochMs: Long) : FloorState()
        object Requesting : FloorState()
        data class Granted(val holderDeviceId: String) : FloorState()
    }

    @Volatile
    var state: FloorState = FloorState.Open
        private set

    val isChannelBusy: Boolean
        get() = when (val s = state) {
            is FloorState.Busy -> s.holderDeviceId != localDeviceId
            else -> false
        }

    val isFloorHeldByMe: Boolean
        get() = when (val s = state) {
            is FloorState.Granted -> s.holderDeviceId == localDeviceId
            is FloorState.Busy -> s.holderDeviceId == localDeviceId
            else -> false
        }

    val shouldSuppressLocalMic: Boolean
        get() = isChannelBusy

    var onFloorStateChanged: ((FloorState) -> Unit)? = null

    // Hub-side coordination
    @Volatile
    private var hubCurrentHolder: String? = null
    @Volatile
    private var hubHolderGrantedAtMs: Long = 0
    @Volatile
    private var hubHolderExpiryMs: Long = 0

    fun requestFloor(sendBroadcastFn: (VoicePayload) -> Unit): Boolean {
        if (shouldSuppressLocalMic) return false
        state = FloorState.Requesting
        onFloorStateChanged?.invoke(state)

        if (isHub) {
            synchronized(this) {
                if (hubCurrentHolder == null || System.currentTimeMillis() > hubHolderExpiryMs) {
                    hubCurrentHolder = localDeviceId
                    hubHolderGrantedAtMs = System.currentTimeMillis()
                    hubHolderExpiryMs = hubHolderGrantedAtMs + floorTimeoutMs
                    state = FloorState.Granted(localDeviceId)
                    onFloorStateChanged?.invoke(state)
                    sendBroadcastFn(VoicePayload.createFloorGrant(localDeviceId, localDeviceId))
                    return true
                }
            }
        } else {
            sendBroadcastFn(VoicePayload.createFloorRequest(localDeviceId))
        }
        return true
    }

    fun releaseFloor(sendBroadcastFn: (VoicePayload) -> Unit) {
        if (isFloorHeldByMe || state is FloorState.Requesting) {
            if (isHub) {
                synchronized(this) {
                    hubCurrentHolder = null
                    hubHolderGrantedAtMs = 0
                    hubHolderExpiryMs = 0
                }
            }
            state = FloorState.Open
            onFloorStateChanged?.invoke(state)
            sendBroadcastFn(VoicePayload.createFloorRelease(localDeviceId))
        }
    }

    fun handleIncomingPayload(payload: VoicePayload, sendBroadcastFn: (VoicePayload) -> Unit) {
        when (payload.type) {
            MessageType.FLOOR_REQUEST -> {
                if (isHub) {
                    handleHubFloorRequest(payload.senderDeviceId, sendBroadcastFn)
                }
            }
            MessageType.FLOOR_GRANT -> {
                val grantedTo = String(payload.payloadBytes, StandardCharsets.UTF_8)
                if (grantedTo == localDeviceId) {
                    state = FloorState.Granted(localDeviceId)
                } else {
                    state = FloorState.Busy(grantedTo, System.currentTimeMillis() + floorTimeoutMs)
                }
                onFloorStateChanged?.invoke(state)
            }
            MessageType.FLOOR_RELEASE -> {
                if (isHub) {
                    synchronized(this) {
                        if (hubCurrentHolder == payload.senderDeviceId) {
                            hubCurrentHolder = null
                            hubHolderGrantedAtMs = 0
                            hubHolderExpiryMs = 0
                        }
                    }
                }
                state = FloorState.Open
                onFloorStateChanged?.invoke(state)
            }
            else -> {}
        }
    }

    private fun handleHubFloorRequest(candidateDeviceId: String, sendBroadcastFn: (VoicePayload) -> Unit) {
        synchronized(this) {
            val now = System.currentTimeMillis()
            val current = hubCurrentHolder

            if (current == null || now > hubHolderExpiryMs) {
                // Floor is free, grant it
                hubCurrentHolder = candidateDeviceId
                hubHolderGrantedAtMs = now
                hubHolderExpiryMs = now + floorTimeoutMs
                sendBroadcastFn(VoicePayload.createFloorGrant(localDeviceId, candidateDeviceId))
            } else if (current != candidateDeviceId) {
                // If candidate arrived within contention window of current grant, resolve deterministically:
                // lower device ID always wins deterministically
                if ((now - hubHolderGrantedAtMs) <= CONTENTION_WINDOW_MS && candidateDeviceId < current) {
                    hubCurrentHolder = candidateDeviceId
                    hubHolderGrantedAtMs = now
                    hubHolderExpiryMs = now + floorTimeoutMs
                    sendBroadcastFn(VoicePayload.createFloorGrant(localDeviceId, candidateDeviceId))
                } else {
                    // Send existing grant so candidate updates to channel busy
                    sendBroadcastFn(VoicePayload.createFloorGrant(localDeviceId, current))
                }
            }
        }
    }
}
