package `in`.itantra.mobile

import android.app.Activity
import android.os.Handler
import android.os.Looper
import `in`.itantra.offline.models.ModelPacks
import `in`.itantra.offline.protocol.Language
import `in`.itantra.offline.protocol.MessageType
import `in`.itantra.offline.session.OfflineSession
import `in`.itantra.offline.session.OperatingMode
import `in`.itantra.offline.speech.AudioEngine
import `in`.itantra.offline.speech.TextChunk
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

fun interface Listener {
    fun onEvent(type: String, data: JSONObject)
}

/**
 * Bridges the LinC web-based radar UI with the offline peer-to-peer engine:
 * multi-radio transport (BLE, Wi-Fi Direct, Wi-Fi Aware), X25519/ChaCha20-Poly1305
 * crypto, true full-duplex Conversation Mode, and PTT Walkie-Talkie floor management.
 */
class OfflineBridge(
    private val activity: Activity,
    private val listener: Listener
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val packs = ModelPacks(File(activity.filesDir, "language-packs"))
    
    private var session: OfflineSession? = null
    private var audio: AudioEngine? = null
    private var isHoldingPtt = false
    private var isConversation = false
    private var currentLanguage = Language.HI
    private val discoveredPeers = mutableMapOf<String, String>()
    private var pendingVerificationAddress: String? = null

    init {
        initSession()
    }

    private fun json(vararg pairs: Any?): JSONObject {
        val obj = JSONObject()
        var i = 0
        while (i < pairs.size) {
            val key = pairs[i]?.toString() ?: ""
            val value = if (i + 1 < pairs.size) pairs[i + 1] else null
            obj.put(key, value)
            i += 2
        }
        return obj
    }

    private fun postEvent(type: String, data: JSONObject) {
        mainHandler.post { listener.onEvent(type, data) }
    }

    private fun initSession() {
        val active = OfflineSession(
            context = activity,
            scope = scope,
            notice = { message: String ->
                postEvent("notice", json("message", message))
            },
            discovered = { address: String, name: String ->
                discoveredPeers[address] = name
                postEvent("peer", json(
                    "name", name,
                    "address", address,
                    "port", 8988
                ))
            },
            verification = { address: String, code: String ->
                pendingVerificationAddress = address
                postEvent("connection_request", json(
                    "peer", discoveredPeers[address] ?: "Nearby Peer",
                    "address", address,
                    "pin", code
                ))
            },
            transcript = { packet ->
                if (packet.type == MessageType.PARTIAL) {
                    postEvent("partial", json("text", packet.text))
                } else if (packet.type == MessageType.FINAL) {
                    postEvent("recognized", json("text", packet.text))
                    if (packet.sender.toString() == session?.id) {
                        postEvent("sent", json("text", packet.text))
                    } else {
                        postEvent("received", json(
                            "text", packet.text,
                            "language", packet.language.tag
                        ))
                    }
                }
            },
            speech = { language: Language, chunk: TextChunk ->
                audio?.receive(language, chunk)
            },
            captureAllowed = { allowed: Boolean ->
                if (allowed) {
                    postEvent("speech", json("state", "Transmitting (Floor Granted)"))
                    if (isConversation || isHoldingPtt) {
                        runCatching { audio?.start(currentLanguage) }
                    }
                } else {
                    postEvent("speech", json("state", "Floor Released / Idle"))
                    audio?.stop()
                }
            }
        )
        session = active

        audio = AudioEngine(
            context = activity,
            packs = packs,
            scope = scope,
            transcript = { stream: Long, source: Language, chunk: TextChunk, final: Boolean ->
                scope.launch {
                    if (session === active) {
                        active.outgoing(stream, source, chunk, final)
                    }
                }
            },
            notice = { msg: String -> postEvent("notice", json("message", msg)) },
            metric = { stage: String, value: Long -> 
                active.metrics.record(stage, value)
            },
            captureFinished = {
                scope.launch {
                    if (session === active && !isHoldingPtt && active.mode == OperatingMode.WALKIE_TALKIE) {
                        active.releaseFloor()
                    }
                }
            }
        )

        scope.launch {
            active.router.connectionState.collect { state ->
                val connected = active.connectedPeers.isNotEmpty()
                val peerName = discoveredPeers.values.firstOrNull() ?: "Mesh Peer"
                postEvent("connection", json(
                    "state", if (connected) "CONNECTED" else state.name,
                    "peer", peerName
                ))
            }
        }
    }

    val isConnected: Boolean get() = session?.connectedPeers?.isNotEmpty() == true

    fun checkPermissions() {
        val permissions = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.RECORD_AUDIO
        )
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            permissions += listOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_ADVERTISE,
                android.Manifest.permission.BLUETOOTH_CONNECT
            )
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            permissions += android.Manifest.permission.NEARBY_WIFI_DEVICES
        }
        val missing = permissions.filter { activity.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            activity.requestPermissions(missing.toTypedArray(), 101)
        }
    }

    fun startDiscovery() {
        checkPermissions()
        session?.startDiscovery()
        postEvent("discovery", json("state", "Scanning 2.4/5GHz & BLE..."))
    }

    fun connect(address: String) {
        session?.connect(address)
    }

    fun respondRequest(accept: Boolean) {
        val target = pendingVerificationAddress ?: discoveredPeers.keys.firstOrNull()
        if (target != null) {
            session?.confirm(target, accept)
            pendingVerificationAddress = null
        }
    }

    fun cancelRequest() {
        pendingVerificationAddress = null
    }

    fun setLanguage(langTag: String) {
        currentLanguage = runCatching { Language.fromTag(langTag) }.getOrDefault(Language.HI)
        session?.language = currentLanguage
    }

    fun setConversation(enabled: Boolean) {
        isConversation = enabled
        audio?.stop()
        session?.setMode(if (enabled) OperatingMode.CONVERSATION else OperatingMode.WALKIE_TALKIE)
        if (enabled) {
            session?.requestFloor()
        }
    }

    fun startPtt() {
        isHoldingPtt = true
        session?.requestFloor()
    }

    fun releasePtt() {
        isHoldingPtt = false
        val wasCapturing = audio?.capturing == true
        audio?.stop()
        if (!wasCapturing) {
            session?.releaseFloor()
        }
    }

    fun sendText(text: String, language: String) {
        if (text.isNotBlank()) {
            runCatching {
                session?.sendText(text)
            }.onFailure { e ->
                postEvent("error", json("message", e.message ?: "Failed to send text"))
            }
        }
    }

    fun disconnect() {
        audio?.stop()
        session?.close()
        initSession()
        postEvent("connection", json("state", "DISCONNECTED", "peer", ""))
    }

    override fun close() {
        scope.cancel()
        audio?.close()
        session?.close()
    }
}
