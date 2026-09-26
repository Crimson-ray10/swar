package `in`.itantra.offline

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.*
import `in`.itantra.offline.models.ModelPacks
import `in`.itantra.offline.protocol.Language
import `in`.itantra.offline.protocol.MessageType
import `in`.itantra.offline.session.*
import `in`.itantra.offline.speech.AudioEngine
import kotlinx.coroutines.*

/** Offline native entry point. The earlier WebView demo is a separate application/module. */
class OfflineActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var packs: ModelPacks
    private lateinit var status: TextView
    private lateinit var models: TextView
    private lateinit var history: TextView
    private lateinit var peers: LinearLayout
    private lateinit var language: Spinner
    private lateinit var conversation: Switch
    private lateinit var ptt: Button
    private var session: OfflineSession? = null
    private var audio: AudioEngine? = null
    private var stateJob: Job? = null
    private var holding = false
    private val discovered = mutableSetOf<String>()
    private val historyLines = ArrayDeque<String>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packs = ModelPacks(java.io.File(filesDir, "language-packs"))
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32); setBackgroundColor(Color.rgb(245, 248, 247)) }
        scroll.addView(root); setContentView(scroll)
        fun label(value: String, size: Float = 16f): TextView = TextView(this).apply { text = value; textSize = size; setTextColor(Color.rgb(20, 48, 44)); setPadding(0, 12, 0, 12); root.addView(this) }
        label("LinC Offline", 30f)
        label("Nearby encrypted communication", 18f)
        status = label("Ready to discover nearby phones")
        models = label("")
        language = Spinner(this).apply {
            adapter = ArrayAdapter(this@OfflineActivity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Hindi", "English", "Odia", "Bengali", "Tamil", "Telugu", "Marathi", "Hinglish"))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    audio?.stop(); session?.language = Language.entries[position]; updateModels()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            root.addView(this)
        }
        conversation = Switch(this).apply {
            text = "Conversation Mode • simultaneous speaking and listening"
            setOnCheckedChangeListener { _, enabled -> action {
                audio?.stop(); session?.setMode(if (enabled) OperatingMode.CONVERSATION else OperatingMode.WALKIE_TALKIE)
                ptt.text = if (enabled) "Start / stop conversation" else "Hold to speak"
            } }; root.addView(this)
        }
        fun button(title: String, callback: () -> Unit) = Button(this).apply { text = title; setOnClickListener { action(callback) }; root.addView(this) }
        button("Discover nearby devices") { requestRadios() }
        peers = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; root.addView(this) }
        ptt = Button(this).apply {
            text = "Hold to speak"
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { action {
                        if (conversation.isChecked) {
                            if (audio?.capturing == true) audio?.stop() else requestCapture()
                        } else { holding = true; requestCapture() }
                    }; true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!conversation.isChecked) {
                            holding = false
                            val wasCapturing = audio?.capturing == true
                            audio?.stop()
                            if (!wasCapturing) session?.releaseFloor()
                        }
                        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                        true
                    }
                    else -> true
                }
            }; root.addView(this)
        }
        val message = EditText(this).apply { hint = "Type an offline message"; maxLines = 4; root.addView(this) }
        button("Send text") { session?.sendText(message.text.toString()); message.text.clear() }
        button("Import offline language pack (.ilp)") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, IMPORT_PACK)
        }
        button("Disconnect") { stopSession(); startSession(); discovered.clear(); peers.removeAllViews() }
        label("Transcript", 22f)
        history = label("Messages appear here. Missing voice packs never prevent reading received text.")
        label("Development build: model quality, speakerphone echo handling, and physical radio reliability require device validation.", 13f)
        updateModels()
    }
    private fun action(block: () -> Unit) { try { block() } catch (e: Exception) { showNotice(e.message ?: "Action unavailable") } }
    private fun showNotice(message: String) { scope.launch { status.text = message } }
    private fun startSession() {
        if (session != null) return
        action {
            val active = OfflineSession(this, scope, ::showNotice,
                { address, name -> if (discovered.add(address)) peers.addView(Button(this).apply {
                    text = "Pair with $name • ${address.takeLast(5)}"; setOnClickListener { action { session?.connect(address) } }
                }) },
                { address, code -> AlertDialog.Builder(this).setTitle("Compare pairing codes")
                    .setMessage("Both phones must show $code. Confirm only after comparing with the other person.")
                    .setPositiveButton("Codes match") { _, _ -> session?.confirm(address, true) }
                    .setNegativeButton("Reject") { _, _ -> session?.confirm(address, false) }
                    .setOnCancelListener { session?.confirm(address, false) }.show() },
                { packet ->
                    if (packet.type == MessageType.FINAL) {
                        historyLines += "${if (packet.sender.toString() == session?.id) "You" else "Peer"} [${packet.language.tag}]: ${packet.text}"
                        while (historyLines.size > 60) historyLines.removeFirst()
                        history.text = historyLines.joinToString("\n\n")
                    }
                }, { source, chunk -> audio?.receive(source, chunk) },
                { allowed -> if (allowed) {
                    if (conversation.isChecked || holding) action { audio?.start(Language.entries[language.selectedItemPosition]) }
                } else audio?.stop() }
            )
            session = active
            audio = AudioEngine(this, packs, scope,
                { stream, source, chunk, final -> scope.launch { if (session === active) active.outgoing(stream, source, chunk, final) } },
                ::showNotice, { stage, value -> active.metrics.record(stage, value) },
                { scope.launch { if (session === active && !holding && active.mode == OperatingMode.WALKIE_TALKIE) active.releaseFloor() } })
            active.language = Language.entries[language.selectedItemPosition]
            active.setMode(if (conversation.isChecked) OperatingMode.CONVERSATION else OperatingMode.WALKIE_TALKIE)
            stateJob = scope.launch { active.router.connectionState.collect { state -> title = "LinC • $state" } }
        }
    }
    private fun stopSession() { holding = false; stateJob?.cancel(); audio?.close(); audio = null; session?.close(); session = null }
    private fun requestCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE); return
        }
        require(session?.connectedPeers?.isNotEmpty() == true) { "Pair with a nearby phone first" }
        require(packs.find("stt", Language.entries[language.selectedItemPosition]) != null) { "A compatible streaming STT pack is required. Typed messages are available." }
        session?.requestFloor()
    }
    private fun requestRadios() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) permissions += listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), RADIOS) else session?.startDiscovery()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RADIOS) {
            action { session?.startDiscovery() }
            if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) showNotice("Some nearby permissions were denied. Available radios remain active; Wi-Fi discovery requires precise location.")
        } else if (requestCode == MICROPHONE) showNotice("Press the speech control again after allowing microphone access")
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != IMPORT_PACK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        scope.launch {
            try {
                showNotice("Checking and importing offline pack…")
                val pack = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)!!.use(packs::import) }
                updateModels(); showNotice("Imported ${pack.id}; device inference and quality validation still required")
            } catch (e: Exception) { showNotice("Pack rejected: ${e.message}") }
        }
    }
    private fun updateModels() {
        if (!::models.isInitialized) return
        val selected = if (::language.isInitialized) Language.entries[language.selectedItemPosition.coerceAtLeast(0)] else Language.HI
        val stt = packs.find("stt", selected); val tts = packs.find("tts", selected)
        models.text = "${selected.tag.uppercase()} packs • STT: ${if (stt == null) "missing" else "installed, unverified"} • TTS: ${if (tts == null) "missing" else "installed, unverified"}\nTranslation is unavailable until its validated runtime and model packs are integrated."
    }
    override fun onStart() { super.onStart(); if (::packs.isInitialized) startSession() }
    override fun onStop() { stopSession(); super.onStop() }
    override fun onDestroy() { stopSession(); scope.cancel(); super.onDestroy() }
    companion object { private const val RADIOS = 10; private const val MICROPHONE = 11; private const val IMPORT_PACK = 12 }
}
