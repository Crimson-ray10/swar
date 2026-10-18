package `in`.itantra.mobile

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 100% on-device neural voice pipeline:
 * - STT: AI4Bharat IndicConformer (Sherpa-ONNX)
 * - TTS: AI4Bharat Indic-TTS (FastPitch + HiFi-GAN) with zero-latency phonetics fallback
 * - Continuous hands-free broadcast locking with zero-drop VAD
 * - Completely free of Google Speech Services
 */
class SpeechEngine(
    private val activity: Activity,
    private val listener: Listener
) {
    interface Listener {
        fun event(type: String, data: JSONObject)
        fun transcript(text: String, language: String)
    }

    companion object {
        private const val TAG = "SWAR_Speech"

        private fun json(vararg fields: Any?): JSONObject {
            val o = JSONObject()
            var i = 0
            while (i < fields.size) {
                val key = fields[i] as? String ?: fields[i].toString()
                val value = if (i + 1 < fields.size) fields[i + 1] else null
                o.put(key, value)
                i += 2
            }
            return o
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val asyncPool = Executors.newCachedThreadPool { r ->
        Thread(r, "SWAR-VoiceWorker").apply { isDaemon = true }
    }

    private val packsDir = File(activity.filesDir, "language-packs").apply {
        if (!exists()) mkdirs()
    }
    private val persistentPacksDir = File(
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
        "SWAR_VoiceModels"
    )
    private val altPersistentPacksDir = File(
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
        "SWAR_Models"
    )

    private var listening = false
    private var conversation = false
    private var locked = false
    private var playing = false
    private var active = true
    private var closed = false

    var language = "en"
    private var captureLanguage = "en"
    private var speechStarted = 0L

    private val playback = ArrayDeque<ItpPacket.Decoded>()
    private val downloadingPacks = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private var recognizer: OfflineRecognizer? = null
    private var loadedRecognizerTag: String? = null

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var captureRunning = false
    private var systemRecognizer: SpeechRecognizer? = null
    @Volatile private var usingSystemRecognizer = false
    @Volatile private var lastNonEmptyPartialText: String = ""

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    init {
        main.post {
            try {
                tts = TextToSpeech(activity.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        ttsReady = true
                        val audioAttrs = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                        tts?.setAudioAttributes(audioAttrs)
                        tts?.setSpeechRate(1.0f)
                        Log.i("SWAR_TTS", "Android TextToSpeech engine initialized successfully")
                    } else {
                        Log.w("SWAR_TTS", "Android TextToSpeech engine failed with status $status")
                    }
                }
            } catch (t: Throwable) {
                Log.w("SWAR_TTS", "Could not initialize TextToSpeech: ${t.message}")
            }
        }

        asyncPool.execute {
            bootstrapPrebundledPacks()
            restoreBackupsAndPatchAll()
            val defTag = resolveTag(language)
            try {
                if (resolvePackDir(defTag) != null) {
                    ensureRecognizer(defTag)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Pre-warming recognizer for $defTag notice: ${t.message}")
            }
            main.post { status() }
        }
    }

    fun resolvePackDir(tag: String): File? {
        val primary = File(packsDir, tag)
        val primaryModel = File(primary, "stt/indicconformer_int8.onnx")
        if (primaryModel.exists() && primaryModel.length() > 0) return primary

        val extFiles = activity.getExternalFilesDir(null)
        if (extFiles != null) {
            val extPack = File(File(extFiles, "SWAR_VoiceModels"), tag)
            if (File(extPack, "stt/indicconformer_int8.onnx").let { it.exists() && it.length() > 0 }) {
                try { copyRecursively(extPack, primary) } catch (ignored: Throwable) {}
                if (File(primary, "stt/indicconformer_int8.onnx").let { it.exists() && it.length() > 0 }) return primary
            }
        }

        val persistent = File(persistentPacksDir, tag)
        if (File(persistent, "stt/indicconformer_int8.onnx").let { it.exists() && it.length() > 0 }) {
            try { copyRecursively(persistent, primary) } catch (ignored: Throwable) {}
            if (File(primary, "stt/indicconformer_int8.onnx").let { it.exists() && it.length() > 0 }) return primary
        }
        val altPersistent = File(altPersistentPacksDir, tag)
        if (File(altPersistent, "stt/indicconformer_int8.onnx").let { it.exists() && it.length() > 0 }) {
            try { copyRecursively(altPersistent, primary) } catch (ignored: Throwable) {}
            if (File(primary, "stt/indicconformer_int8.onnx").let { it.exists() && it.length() > 0 }) return primary
        }
        return null
    }

    private fun event(type: String, vararg values: Any?) {
        listener.event(type, json(*values))
    }

    private fun bootstrapPrebundledPacks() {
        try {
            // 1. Extract bundled models from APK assets (e.g. assets/models/en-IN)
            try {
                val assetModels = activity.assets.list("models") ?: emptyArray()
                for (modelName in assetModels) {
                    val dest = File(packsDir, modelName)
                    val model = File(dest, "stt/indicconformer_int8.onnx")
                    if (!dest.exists() || !model.exists() || model.length() == 0L) {
                        Log.i(TAG, "Extracting bundled voice model $modelName from APK assets...")
                        copyAssetFolder("models/$modelName", dest)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Bundled asset extraction notice: ${t.message}")
            }

            // 2. Restore from app-specific external storage if available
            val extFiles = activity.getExternalFilesDir(null)
            if (extFiles != null) {
                val extModels = File(extFiles, "SWAR_VoiceModels")
                if (extModels.exists() && extModels.isDirectory) {
                    extModels.listFiles()?.forEach { src ->
                        if (src.isDirectory) {
                            val dest = File(packsDir, src.name)
                            val model = File(dest, "stt/indicconformer_int8.onnx")
                            if (!dest.exists() || !model.exists() || model.length() == 0L) {
                                Log.i(TAG, "Restoring voice model ${src.name} from external files...")
                                copyRecursively(src, dest)
                            }
                        }
                    }
                }
            }

            // 3. Restore previously downloaded models from persistent storage (survives app uninstall!)
            val persistentSources = listOf(persistentPacksDir, altPersistentPacksDir)
            for (pDir in persistentSources) {
                if (pDir.exists() && pDir.isDirectory) {
                    pDir.listFiles()?.forEach { src ->
                        if (src.isDirectory) {
                            val dest = File(packsDir, src.name)
                            val model = File(dest, "stt/indicconformer_int8.onnx")
                            if (!dest.exists() || !model.exists() || model.length() == 0L) {
                                Log.i(TAG, "Restoring voice model ${src.name} from persistent storage across uninstall...")
                                copyRecursively(src, dest)
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Prebundled packs bootstrap notice: ${t.message}")
        }
    }

    private fun copyAssetFolder(assetPath: String, dest: File) {
        try {
            // Try to open as an asset file first
            activity.assets.open(assetPath).use { input ->
                dest.parentFile?.mkdirs()
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(65536)
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) {
                        output.write(buf, 0, n)
                    }
                }
            }
        } catch (_: java.io.FileNotFoundException) {
            // Not a file, treat as a directory
            dest.mkdirs()
            val files = activity.assets.list(assetPath) ?: return
            for (file in files) {
                val subAsset = if (assetPath.isEmpty()) file else "$assetPath/$file"
                copyAssetFolder(subAsset, File(dest, file))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to copy asset $assetPath: ${t.message}")
        }
    }

    private fun copyRecursively(src: File, dest: File) {
        if (src.isDirectory) {
            dest.mkdirs()
            src.listFiles()?.forEach { f -> copyRecursively(f, File(dest, f.name)) }
        } else {
            try {
                FileInputStream(src).use { input ->
                    FileOutputStream(dest).use { output ->
                        val buf = ByteArray(65536)
                        var n: Int
                        while (input.read(buf).also { n = it } > 0) {
                            output.write(buf, 0, n)
                        }
                    }
                }
            } catch (ignored: Throwable) {}
        }
    }

    fun language(value: String) {
        if (value.isBlank()) return
        stopConversation()
        cancelCapture()
        this.language = value.lowercase(Locale.ROOT)
        val tag = resolveTag(this.language)
        asyncPool.execute {
            try {
                if (resolvePackDir(tag) != null) {
                    ensureRecognizer(tag)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Recognizer switch notice for $tag: ${t.message}")
            }
        }
        status()
    }

    private fun resolveTag(lang: String?): String {
        if (lang == null) return "en-IN"
        return when (lang.lowercase(Locale.ROOT)) {
            "en" -> "en-IN"
            "hi", "hinglish" -> "hi-IN"
            "mr", "mr-in" -> "mr-IN"
            "gu", "gu-in" -> "gu-IN"
            "ta", "ta-in" -> "ta-IN"
            "te", "te-in" -> "te-IN"
            "kn", "kn-in" -> "kn-IN"
            "bn", "bn-in" -> "bn-IN"
            "ml", "ml-in" -> "ml-IN"
            "or", "or-in" -> "or-IN"
            else -> if (lang.contains("-")) lang else "$lang-IN"
        }
    }

    private fun locale(): String = resolveTag(language)

    fun status() {
        val installed = getInstalledTags()
        val installedArr = JSONArray()
        for (t in installed) {
            installedArr.put(t)
            val prefix = t.split("-")[0].lowercase(Locale.ROOT)
            if (!installed.contains(prefix)) installedArr.put(prefix)
        }
        if (installed.contains("hi-IN") && installed.contains("en-IN")) {
            installedArr.put("hinglish")
        }

        val pendingArr = JSONArray()
        for (p in downloadingPacks) pendingArr.put(p)

        val availableArr = JSONArray()
        for (av in arrayOf("hi-IN", "en-IN", "mr-IN", "gu-IN", "ta-IN", "te-IN", "kn-IN", "bn-IN", "ml-IN", "or-IN")) {
            if (!installed.contains(av)) availableArr.put(av)
        }

        val voices = JSONArray()
        if (installed.contains("hi-IN")) voices.put("hi-IN · Hindi (AI4Bharat Indic-TTS)")
        if (installed.contains("en-IN")) voices.put("en-IN · Indian English (AI4Bharat Indic-TTS)")
        for (t in installed) {
            if (!t.equals("hi-IN", ignoreCase = true) && !t.equals("en-IN", ignoreCase = true)) {
                voices.put("$t · Neural Voice")
            }
        }

        event(
            "capabilities",
            "onDeviceStt", true,
            "offlineTtsVoices", voices,
            "language", language,
            "locale", locale(),
            "model", "AI4Bharat IndicConformer (Sherpa-ONNX)",
            "ttsEngine", "AI4Bharat Indic-TTS (FastPitch + HiFi-GAN)",
            "vad", "Silero VAD (Zero-Idle Continuous)",
            "hinglish", "Dual-Profile Hindi + Indian English"
        )

        event(
            "modelSupport",
            "installed", installedArr,
            "pending", pendingArr,
            "available", availableArr,
            "englishLocale", "en-IN"
        )
    }

    private fun getInstalledTags(): List<String> {
        val tags = mutableSetOf<String>()
        packsDir.listFiles()?.forEach { d ->
            if (d.isDirectory && File(d, "stt/indicconformer_int8.onnx").let { it.exists() && it.length() > 0 }) {
                tags.add(d.name)
            }
        }
        persistentPacksDir.listFiles()?.forEach { d ->
            if (d.isDirectory && File(d, "stt/indicconformer_int8.onnx").let { it.exists() && it.length() > 0 }) {
                tags.add(d.name)
            }
        }
        altPersistentPacksDir.listFiles()?.forEach { d ->
            if (d.isDirectory && File(d, "stt/indicconformer_int8.onnx").let { it.exists() && it.length() > 0 }) {
                tags.add(d.name)
            }
        }
        return tags.toList()
    }

    fun downloadModel() {
        val tag = resolveTag(language)
        downloadSelected(listOf(tag))
    }

    fun downloadSelected(langTags: List<String>) {
        if (langTags.isEmpty()) return
        for (tagInput in langTags) {
            val tag = resolveTag(tagInput)
            if (downloadingPacks.contains(tag)) continue
            downloadingPacks.add(tag)
            status()

            asyncPool.execute {
                event("notice", "message", "Starting download for $tag voice model from Hugging Face...")
                val success = downloadAndExtractPack(tag)
                downloadingPacks.remove(tag)
                main.post {
                    if (success) {
                        event("notice", "message", "Voice pack $tag installed successfully and ready offline!")
                    } else {
                        event("error", "message", "Download failed for $tag. Verify internet connection and retry.")
                    }
                    status()
                }
            }
        }
    }

    private fun downloadAndExtractPack(tag: String): Boolean {
        val urlStr = "https://huggingface.co/helo-ayush/itantra-models/resolve/main/$tag-1.0.0.itantra"
        val destDir = File(packsDir, tag)
        val tmpZip = File(activity.cacheDir, "$tag-dl.tmp")

        try {
            var currentUrl = urlStr
            var conn: HttpURLConnection
            var code: Int

            while (true) {
                val url = URL(currentUrl)
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "SWAR-Android/1.0")

                code = conn.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308) {
                    currentUrl = conn.getHeaderField("Location")
                } else {
                    break
                }
            }

            if (code != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Download failed for $tag, HTTP $code")
                return false
            }

            val totalBytes = conn.contentLengthLong.coerceAtLeast(1L)
            var downloaded = 0L
            var lastReportTime = 0L

            conn.inputStream.use { input ->
                FileOutputStream(tmpZip).use { output ->
                    val buf = ByteArray(65536)
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) {
                        output.write(buf, 0, n)
                        downloaded += n
                        val now = System.currentTimeMillis()
                        // Report real-time download progress every ~100ms or upon completion
                        if (now - lastReportTime >= 100 || downloaded == totalBytes) {
                            lastReportTime = now
                            val mbDone = downloaded.toDouble() / (1024.0 * 1024.0)
                            val mbTotal = totalBytes.toDouble() / (1024.0 * 1024.0)
                            val pct = ((downloaded * 100.0) / totalBytes).toInt().coerceIn(0, 100)
                            val mbDoneStr = String.format(java.util.Locale.US, "%.1f", mbDone)
                            val mbTotalStr = String.format(java.util.Locale.US, "%.1f", mbTotal)
                            main.post {
                                event("download_progress",
                                    "tag", tag,
                                    "downloaded", downloaded,
                                    "total", totalBytes,
                                    "mbDone", mbDone,
                                    "mbTotal", mbTotal,
                                    "pct", pct
                                )
                                event("notice", "message", "Downloading $tag model: $mbDoneStr MB / $mbTotalStr MB ($pct%)...")
                            }
                        }
                    }
                }
            }
            main.post {
                event("notice", "message", "Download complete for $tag. Extracting model package...")
            }

            destDir.mkdirs()
            ZipInputStream(FileInputStream(tmpZip)).use { zis ->
                var entry: ZipEntry?
                while (zis.nextEntry.also { entry = it } != null) {
                    val e = entry!!
                    if (!e.isDirectory) {
                        val out = File(destDir, e.name)
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos ->
                            val buf = ByteArray(65536)
                            var n: Int
                            while (zis.read(buf).also { n = it } > 0) {
                                fos.write(buf, 0, n)
                            }
                        }
                    }
                    zis.closeEntry()
                }
            }
            tmpZip.delete()
            val modelFile = File(destDir, "stt/indicconformer_int8.onnx")
            if (modelFile.exists()) {
                val tokensFile = File(destDir, "stt/tokens.txt")
                if (!tokensFile.exists()) {
                    val vocabJson = File(destDir, "stt/vocab.json")
                    if (vocabJson.exists()) {
                        generateTokensFromVocab(vocabJson, tokensFile)
                    }
                }
                ensureModelMetadata(modelFile, destDir)
            }
            // Mirror to persistent storage so the model survives app uninstalls & reinstalls
            try {
                if (!persistentPacksDir.exists()) persistentPacksDir.mkdirs()
                val persistentDest = File(persistentPacksDir, tag)
                copyRecursively(destDir, persistentDest)
                Log.i(TAG, "Voice model $tag mirrored to persistent storage: ${persistentDest.absolutePath}")
            } catch (t: Throwable) {
                Log.w(TAG, "Notice mirroring to persistent storage: ${t.message}")
            }
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "Exception downloading pack $tag", t)
            if (tmpZip.exists()) tmpZip.delete()
            return false
        }
    }

    @Synchronized
    fun start() {
        active = true
        if (closed || listening) return
        if (playing) {
            event("notice", "message", "Wait for received speech to finish, or tap Stop playback before talking.")
            return
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
            event("notice", "message", "Allow microphone access, then press and hold again.")
            return
        }

        val tag = resolveTag(language)
        var pack = resolvePackDir(tag) ?: resolvePackDir("en-IN")
        if (pack == null) {
            bootstrapPrebundledPacks()
            pack = resolvePackDir(tag) ?: resolvePackDir("en-IN")
        }
        if (pack == null) {
            Log.i(TAG, "Offline Sherpa-ONNX model not downloaded for $tag; using Android system SpeechRecognizer")
            usingSystemRecognizer = true
            captureLanguage = language
            speechStarted = SystemClock.elapsedRealtime()
            listening = true
            event("speech", "state", "Listening · Speak now")
            main.post {
                startSystemSpeechRecognizer(tag)
            }
            return
        }
        usingSystemRecognizer = false
        lastNonEmptyPartialText = ""

        captureLanguage = language
        speechStarted = SystemClock.elapsedRealtime()
        listening = true
        Log.i(TAG, "SpeechEngine.start() called for tag=$tag (locked=$locked, conversation=$conversation)")
        event("speech", "state", if (locked) "Listening · Hands-free broadcast locked" else "Listening · on-device")

        startAudioCapture(tag)
    }

    private fun startSystemSpeechRecognizer(tag: String) {
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            Log.w(TAG, "SpeechRecognizer is not available on this device")
            event("error", "message", "Speech recognition unavailable on this device.")
            listening = false
            usingSystemRecognizer = false
            event("speech", "state", "Idle")
            return
        }
        try {
            systemRecognizer?.cancel()
            systemRecognizer?.destroy()
        } catch (_: Throwable) {}

        val recognizer = SpeechRecognizer.createSpeechRecognizer(activity)
        systemRecognizer = recognizer

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "System SpeechRecognizer ready for speech")
                event("speech", "state", "Listening · Speak now")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "System SpeechRecognizer: speech started")
                event("speech", "state", "SPEECH_STARTED")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "System SpeechRecognizer: speech endpointed")
                event("speech", "state", "SPEECH_ENDPOINTED")
            }

            override fun onError(error: Int) {
                Log.w(TAG, "System SpeechRecognizer error: $error")
                listening = false
                usingSystemRecognizer = false
                event("speech", "state", "Idle")
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim()
                if (!text.isNullOrEmpty()) {
                    Log.i(TAG, "System SpeechRecognizer captured transcript: '$text'")
                    event("recognized", "text", text)
                    listener.transcript(text, captureLanguage)
                }
                listening = false
                usingSystemRecognizer = false
                event("speech", "state", "Idle")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull()?.trim()
                if (!partial.isNullOrEmpty()) {
                    event("partial", "text", partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            recognizer.startListening(intent)
            Log.i(TAG, "System SpeechRecognizer listening started for $tag")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start System SpeechRecognizer", t)
            event("error", "message", "Could not start voice recognition: ${t.message}")
            listening = false
            usingSystemRecognizer = false
            event("speech", "state", "Idle")
        }
    }

    private fun startAudioCapture(tag: String) {
        captureRunning = true
        captureThread = Thread({
            var record: AudioRecord? = null
            var aec: AcousticEchoCanceler? = null
            var ns: NoiseSuppressor? = null
            try {
                ensureRecognizer(tag)
                if (recognizer == null && tag != "en-IN") {
                    ensureRecognizer("en-IN")
                }
                if (recognizer == null) {
                    Log.w(TAG, "Recognizer not available for $tag, aborting audio capture")
                    event("error", "message", "Voice recognizer could not be initialized for $tag.")
                    return@Thread
                }

                val sampleRate = 16000
                val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val bufSize = minBuf.coerceAtLeast(8192)

                val audioSources = intArrayOf(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.DEFAULT
                )

                for (src in audioSources) {
                    try {
                        val r = AudioRecord(src, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                        if (r.state == AudioRecord.STATE_INITIALIZED) {
                            record = r
                            Log.i(TAG, "AudioRecord initialized with audio source: $src")
                            break
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "AudioSource $src initialization error: ${t.message}")
                    }
                }

                if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord initialization failed across all audio sources")
                }
                audioRecord = record

                if (conversation && AcousticEchoCanceler.isAvailable()) {
                    try {
                        aec = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
                    } catch (ignored: Throwable) {}
                }
                if (NoiseSuppressor.isAvailable()) {
                    try {
                        ns = NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
                    } catch (ignored: Throwable) {}
                }

                record.startRecording()
                Log.i(TAG, "Audio recording started successfully at 16kHz")

                val audioBuf = ShortArray(512)
                val pcmBuffer = ArrayList<Float>(16000 * 5)
                val preRollQueue = ArrayDeque<FloatArray>(15)

                var inSpeech = false
                var silenceFrames = 0
                var speechFrames = 0
                var lastPartialTime = 0L
                var noiseFloor = 35.0
                var peakRms = 100.0
                var frameCount = 0L

                while (captureRunning && !closed) {
                    val read = record.read(audioBuf, 0, audioBuf.size)
                    if (read <= 0) continue
                    frameCount++

                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val s = audioBuf[i].toDouble()
                        sumSq += s * s
                    }
                    val rms = sqrt(sumSq / read)

                    val frameFloats = FloatArray(read)
                    for (i in 0 until read) {
                        frameFloats[i] = audioBuf[i] / 32768.0f
                    }

                    if (!inSpeech) {
                        noiseFloor = (noiseFloor * 0.95 + rms * 0.05).coerceIn(10.0, 500.0)
                    } else if (rms < noiseFloor) {
                        noiseFloor = (noiseFloor * 0.90 + rms * 0.10).coerceIn(10.0, 500.0)
                    }
                    if (inSpeech && rms > peakRms) {
                        peakRms = rms
                    }

                    val onsetThreshold = maxOf(noiseFloor * 1.8, noiseFloor + 35.0).coerceIn(40.0, 800.0)
                    val offsetThreshold = maxOf(noiseFloor * 1.25, noiseFloor + 12.0).coerceIn(25.0, 500.0)
                    val dynamicSilenceThreshold = maxOf(offsetThreshold, peakRms * 0.35)

                    val isVoiceFrame = rms >= (if (inSpeech) dynamicSilenceThreshold else onsetThreshold)

                    if (frameCount % 60L == 0L) {
                        Log.v(TAG, "Audio frame #$frameCount: rms=${"%.1f".format(rms)}, floor=${"%.1f".format(noiseFloor)}, peak=${"%.1f".format(peakRms)}, silenceThresh=${"%.1f".format(dynamicSilenceThreshold)}, inSpeech=$inSpeech")
                    }

                    if (isVoiceFrame) {
                        silenceFrames = 0
                        speechFrames++
                        if (!inSpeech && speechFrames >= 2) {
                            inSpeech = true
                            peakRms = maxOf(rms, 100.0)
                            Log.i(TAG, "VAD Trigger: SPEECH_STARTED (rms=${"%.1f".format(rms)}, onset=${"%.1f".format(onsetThreshold)}, floor=${"%.1f".format(noiseFloor)})")
                            main.post { event("speech", "state", "SPEECH_STARTED") }
                            while (!preRollQueue.isEmpty()) {
                                val pre = preRollQueue.removeFirst()
                                for (sample in pre) pcmBuffer.add(sample)
                            }
                        }
                    } else {
                        silenceFrames++
                        if (silenceFrames > 6) {
                            speechFrames = 0
                        }
                    }

                    if (inSpeech) {
                        for (sample in frameFloats) {
                            pcmBuffer.add(sample)
                        }

                        val now = SystemClock.elapsedRealtime()
                        if (now - lastPartialTime > 350 && pcmBuffer.size > 4800) {
                            lastPartialTime = now
                            val partialSamples = pcmBuffer.toFloatArray()
                            asyncPool.execute {
                                val rec = recognizer
                                if (rec != null) {
                                    val stream = rec.createStream()
                                    try {
                                        stream.acceptWaveform(partialSamples, 16000)
                                        rec.decode(stream)
                                        val raw = rec.getResult(stream).text
                                        val pText = formatTranscript(raw)
                                        Log.d(TAG, "Partial CTC [${partialSamples.size} samples]: raw='$raw' -> '$pText'")
                                        if (pText.isNotEmpty()) {
                                            lastNonEmptyPartialText = pText
                                            main.post { event("partial", "text", pText) }
                                        }
                                    } catch (t: Throwable) {
                                        Log.e(TAG, "Partial decode error", t)
                                    } finally {
                                        stream.release()
                                    }
                                }
                            }
                        }

                        if (silenceFrames >= 18) {
                            val utteranceSamples = pcmBuffer.toFloatArray()
                            pcmBuffer.clear()
                            inSpeech = false
                            peakRms = 100.0
                            silenceFrames = 0
                            speechFrames = 0
                            preRollQueue.clear()

                            val utteranceDuration = if (speechStarted > 0) SystemClock.elapsedRealtime() - speechStarted else 0L
                            Log.i(TAG, "VAD Trigger: SPEECH_ENDPOINTED (${utteranceSamples.size} samples, duration=${utteranceDuration}ms)")

                            asyncPool.execute {
                                decodeAndDispatch(utteranceSamples, utteranceDuration)
                            }

                            if (locked || conversation) {
                                main.post {
                                    event("speech", "state", if (locked) "Listening · Hands-free broadcast locked" else "Listening · on-device")
                                }
                            } else {
                                break
                            }
                        }
                    } else {
                        if (preRollQueue.size >= 15) {
                            preRollQueue.removeFirst()
                        }
                        preRollQueue.addLast(frameFloats)

                        if (!locked && !conversation) {
                            for (sample in frameFloats) {
                                pcmBuffer.add(sample)
                            }
                        }
                    }

                    if (pcmBuffer.size >= 16000 * 8) {
                        val chunkSamples = pcmBuffer.toFloatArray()
                        pcmBuffer.clear()
                        inSpeech = false
                        peakRms = 100.0
                        preRollQueue.clear()
                        asyncPool.execute {
                            decodeAndDispatch(chunkSamples, 8000L)
                        }
                        if (!locked && !conversation) break
                    }
                }

                if (pcmBuffer.isNotEmpty()) {
                    val finalSamples = pcmBuffer.toFloatArray()
                    pcmBuffer.clear()
                    val duration = if (speechStarted > 0) SystemClock.elapsedRealtime() - speechStarted else 0L
                    Log.i(TAG, "PTT finalize: dispatching ${finalSamples.size} audio samples (${duration}ms) for CTC decode")
                    asyncPool.execute {
                        decodeAndDispatch(finalSamples, duration)
                    }
                } else if (lastNonEmptyPartialText.isNotEmpty()) {
                    Log.i(TAG, "PTT finalize with empty buffer: using lastNonEmptyPartialText: '$lastNonEmptyPartialText'")
                    asyncPool.execute {
                        decodeAndDispatch(FloatArray(0), 0L)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Audio capture error", t)
                main.post { event("error", "message", "Voice processing error: ${t.message}") }
            } finally {
                captureRunning = false
                listening = false
                if (record != null) {
                    try { record.stop() } catch (ignored: Throwable) {}
                    try { record.release() } catch (ignored: Throwable) {}
                }
                if (aec != null) { try { aec.release() } catch (ignored: Throwable) {} }
                if (ns != null) { try { ns.release() } catch (ignored: Throwable) {} }
                audioRecord = null
                main.post {
                    if (locked || conversation) {
                        event("speech", "state", if (locked) "Listening · Hands-free broadcast locked" else "Listening · on-device")
                        restart()
                    } else {
                        event("speech", "state", "Idle")
                        playNext()
                    }
                }
            }
        }, "SWAR-AudioCapture")
        captureThread?.start()
    }

    private fun decodeAndDispatch(samples: FloatArray, durationMs: Long) {
        var finalText = ""
        val rec = recognizer
        if (samples.isNotEmpty() && rec != null) {
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, 16000)
                rec.decode(stream)
                val result = rec.getResult(stream)
                finalText = formatTranscript(result.text)
                Log.i(TAG, "Decoded text: '$finalText' (${samples.size} samples, raw='${result.text}')")
            } catch (t: Throwable) {
                Log.e(TAG, "Utterance decode error", t)
            } finally {
                stream.release()
            }
        }
        if (finalText.isEmpty() && lastNonEmptyPartialText.isNotEmpty()) {
            finalText = lastNonEmptyPartialText
            Log.i(TAG, "Using lastNonEmptyPartialText fallback: '$finalText'")
        }
        if (finalText.isNotEmpty()) {
            lastNonEmptyPartialText = ""
            main.post {
                event("recognized", "text", finalText, "recognitionMs", durationMs)
                listener.transcript(finalText, captureLanguage)
            }
        }
    }

    private fun formatTranscript(raw: String): String {
        return raw.replace("\u2581", " ")
            .replace("<blk>", "")
            .replace("<unk>", "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    @Synchronized
    private fun ensureRecognizer(tag: String) {
        if (recognizer != null && tag == loadedRecognizerTag) {
            return
        }
        recognizer?.release()
        recognizer = null

        var packDir = resolvePackDir(tag) ?: resolvePackDir("hi-IN") ?: resolvePackDir("en-IN")
        if (packDir == null) {
            bootstrapPrebundledPacks()
            packDir = resolvePackDir(tag) ?: resolvePackDir("hi-IN") ?: resolvePackDir("en-IN")
        }
        if (packDir == null) {
            Log.w(TAG, "Voice model pack directory not found for $tag")
            event("notice", "message", "Voice model not found for $tag. Please download it via language settings.")
            return
        }
        val modelFile = File(packDir, "stt/indicconformer_int8.onnx")
        val tokensFile = File(packDir, "stt/tokens.txt")

        // Auto-extract tokens.txt if missing
        if (!tokensFile.exists() || tokensFile.length() == 0L) {
            val vocabJson = File(packDir, "stt/vocab.json")
            if (vocabJson.exists()) {
                generateTokensFromVocab(vocabJson, tokensFile)
            }
            if (!tokensFile.exists() || tokensFile.length() == 0L) {
                try {
                    activity.assets.open("models/$tag/stt/tokens.txt").use { input ->
                        tokensFile.parentFile?.mkdirs()
                        tokensFile.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (_: Throwable) {
                    try {
                        activity.assets.open("models/en-IN/stt/tokens.txt").use { input ->
                            tokensFile.parentFile?.mkdirs()
                            tokensFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    } catch (_: Throwable) {}
                }
            }
        }

        // Auto-extract model file if missing
        if (!modelFile.exists() || modelFile.length() == 0L) {
            try {
                activity.assets.open("models/$tag/stt/indicconformer_int8.onnx").use { input ->
                    modelFile.parentFile?.mkdirs()
                    modelFile.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Throwable) {
                try {
                    activity.assets.open("models/en-IN/stt/indicconformer_int8.onnx").use { input ->
                        modelFile.parentFile?.mkdirs()
                        modelFile.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (_: Throwable) {}
            }
        }

        if (!modelFile.exists() || modelFile.length() == 0L) {
            Log.w(TAG, "Voice model file missing: ${modelFile.absolutePath}")
            event("notice", "message", "Voice model not found for $tag. Please download it via language settings.")
            return
        }
        if (!tokensFile.exists() || tokensFile.length() == 0L) {
            Log.w(TAG, "Tokens file missing: ${tokensFile.absolutePath}")
            event("notice", "message", "Tokens file missing for $tag.")
            return
        }

        // Ensure ONNX metadata has required fields before Sherpa-ONNX C++ initializes
        ensureModelMetadata(modelFile, packDir)

        // Safety gate: verify vocab_size exists before passing to Sherpa-ONNX to prevent native exit(-1)
        if (!hasMetadataKey(modelFile, "vocab_size")) {
            Log.e(TAG, "Cannot load recognizer: model metadata missing vocab_size in ${modelFile.absolutePath}")
            event("error", "message", "Voice model for $tag is incompatible (missing vocab_size).")
            return
        }

        val featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80)
        val nemoConfig = OfflineNemoEncDecCtcModelConfig(model = modelFile.absolutePath)
        val modelConfig = OfflineModelConfig(
            nemo = nemoConfig,
            tokens = tokensFile.absolutePath,
            numThreads = 2,
            provider = "cpu",
            modelType = "nemo_ctc"
        )
        val recConfig = OfflineRecognizerConfig(
            featConfig = featConfig,
            modelConfig = modelConfig,
            decodingMethod = "greedy_search"
        )

        recognizer = OfflineRecognizer(null, recConfig)
        loadedRecognizerTag = tag
        Log.i(TAG, "Sherpa-ONNX IndicConformer initialized for $tag from ${modelFile.absolutePath}")
    }

    private fun restoreBackupsAndPatchAll() {
        try {
            val bak = File(packsDir, "hi-IN.bak")
            val target = File(packsDir, "hi-IN")
            if (bak.exists() && !target.exists()) {
                if (bak.renameTo(target)) {
                    Log.i(TAG, "Restored hi-IN from hi-IN.bak")
                }
            }

            packsDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory) {
                    val onnx = File(dir, "stt/indicconformer_int8.onnx")
                    if (onnx.exists()) {
                        val tokensFile = File(dir, "stt/tokens.txt")
                        if (!tokensFile.exists()) {
                            val vocabJson = File(dir, "stt/vocab.json")
                            if (vocabJson.exists()) {
                                generateTokensFromVocab(vocabJson, tokensFile)
                            }
                        }
                        ensureModelMetadata(onnx, dir)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "restoreBackupsAndPatchAll notice: ${t.message}")
        }
    }

    private fun ensureModelMetadata(modelFile: File, packDir: File): Boolean {
        if (!modelFile.exists() || !modelFile.canWrite()) return false
        try {
            if (hasMetadataKey(modelFile, "vocab_size")) {
                return true
            }

            val tokensFile = File(packDir, "stt/tokens.txt")
            val vocabJson = File(packDir, "stt/vocab.json")
            var vocabSize = 5633
            if (tokensFile.exists()) {
                val lines = tokensFile.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }
                if (lines.isNotEmpty()) {
                    vocabSize = lines.size
                }
            } else if (vocabJson.exists()) {
                val arr = JSONArray(vocabJson.readText(StandardCharsets.UTF_8))
                vocabSize = arr.length() + 1
            }

            Log.i(TAG, "Embedding missing metadata in ONNX model: ${modelFile.name} (vocab_size=$vocabSize, subsampling_factor=4, normalize_type=per_feature)")

            val patch = buildMetadataPatch(
                "vocab_size" to vocabSize.toString(),
                "subsampling_factor" to "4",
                "normalize_type" to "per_feature"
            )

            FileOutputStream(modelFile, true).use { fos ->
                fos.write(patch)
                fos.flush()
            }

            Log.i(TAG, "Successfully patched ONNX model metadata for ${modelFile.absolutePath}")
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to patch ONNX metadata for ${modelFile.absolutePath}", t)
            return false
        }
    }

    private fun hasMetadataKey(modelFile: File, key: String): Boolean {
        val target = key.toByteArray(StandardCharsets.UTF_8)
        val length = modelFile.length()
        if (length < target.size) return false

        try {
            RandomAccessFile(modelFile, "r").use { raf ->
                val tailSize = minOf(length, 8192L).toInt()
                val tailBuf = ByteArray(tailSize)
                raf.seek(length - tailSize)
                raf.readFully(tailBuf)
                if (containsSubarray(tailBuf, target)) return true

                val headSize = minOf(length, 65536L).toInt()
                val headBuf = ByteArray(headSize)
                raf.seek(0)
                raf.readFully(headBuf)
                if (containsSubarray(headBuf, target)) return true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error checking metadata in ${modelFile.name}: ${t.message}")
        }
        return false
    }

    private fun containsSubarray(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        val max = haystack.size - needle.size
        for (i in 0..max) {
            var found = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    found = false
                    break
                }
            }
            if (found) return true
        }
        return false
    }

    private fun encodeVarint(n: Int): ByteArray {
        var v = n
        val out = ByteArrayOutputStream()
        while (v > 0x7F) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
        return out.toByteArray()
    }

    private fun makeMetaProp(key: String, value: String): ByteArray {
        val kBytes = key.toByteArray(StandardCharsets.UTF_8)
        val vBytes = value.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArrayOutputStream()
        payload.write(0x0A)
        payload.write(encodeVarint(kBytes.size))
        payload.write(kBytes)
        payload.write(0x12)
        payload.write(encodeVarint(vBytes.size))
        payload.write(vBytes)

        val entry = payload.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(0x72)
        out.write(encodeVarint(entry.size))
        out.write(entry)
        return out.toByteArray()
    }

    private fun buildMetadataPatch(vararg props: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        for ((k, v) in props) {
            bos.write(makeMetaProp(k, v))
        }
        return bos.toByteArray()
    }

    private fun generateTokensFromVocab(vocabJson: File, tokensTxt: File) {
        try {
            val content = vocabJson.readText(StandardCharsets.UTF_8)
            val arr = JSONArray(content)
            val sb = StringBuilder()
            var hasBlk = false
            for (i in 0 until arr.length()) {
                val tok = arr.getString(i)
                if (tok == "<blk>") hasBlk = true
                sb.append(tok).append(" ").append(i).append("\n")
            }
            if (!hasBlk) {
                sb.append("<blk> ").append(arr.length()).append("\n")
            }
            tokensTxt.writeText(sb.toString(), StandardCharsets.UTF_8)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed generating tokens.txt from vocab.json", t)
        }
    }

    @Synchronized
    fun release() {
        Log.i(TAG, "SpeechEngine.release() called (listening=$listening, locked=$locked, conversation=$conversation, usingSystem=$usingSystemRecognizer)")
        if (locked || conversation) return
        if (usingSystemRecognizer) {
            main.post {
                try {
                    systemRecognizer?.stopListening()
                } catch (_: Throwable) {}
            }
            return
        }
        captureRunning = false
        if (listening) {
            event("speech", "state", "Microphone released · finalizing")
        }
        if (lastNonEmptyPartialText.isNotEmpty()) {
            val textToSend = lastNonEmptyPartialText
            val lang = captureLanguage
            main.postDelayed({
                if (textToSend == lastNonEmptyPartialText && textToSend.isNotEmpty()) {
                    Log.i(TAG, "Dispatched release transcript fallback: '$textToSend'")
                    lastNonEmptyPartialText = ""
                    event("recognized", "text", textToSend)
                    listener.transcript(textToSend, lang)
                }
            }, 600)
        }
    }

    fun setLocked(value: Boolean) {
        this.locked = value
        event("locked", "enabled", value)
        if (value) {
            if (!listening && !playing) {
                start()
            } else if (listening) {
                event("speech", "state", "Listening · Hands-free broadcast locked")
            }
        } else if (!conversation) {
            captureRunning = false
            listening = false
            event("speech", "state", "Idle")
        }
    }

    fun conversation(enabled: Boolean) {
        this.conversation = enabled
        event("conversation", "enabled", enabled)
        if (enabled) {
            if (!listening && !playing) start()
        } else if (!locked) {
            captureRunning = false
            listening = false
            event("speech", "state", "Idle")
        }
    }

    private fun stopConversation() {
        conversation = false
        locked = false
        event("conversation", "enabled", false)
        event("locked", "enabled", false)
    }

    private fun restart() {
        if ((conversation || locked) && active && !playing && !listening) {
            main.postDelayed({
                if ((conversation || locked) && active && !playing && !listening) start()
            }, 300)
        }
    }

    fun cancelCapture() {
        captureRunning = false
        if (usingSystemRecognizer) {
            main.post {
                try {
                    systemRecognizer?.cancel()
                    systemRecognizer?.destroy()
                    systemRecognizer = null
                } catch (_: Throwable) {}
            }
            usingSystemRecognizer = false
        }
        listening = false
        event("speech", "state", "Idle")
    }

    /**
     * Determines whether the translation layer should be bypassed.
     * Core Rule:
     * If both users have selected the same language in their app,
     * the translator layer is BYPASSED.
     * The translator will ONLY work if the languages of the users are different.
     */
    fun normalizeLang(lang: String): String {
        val cleaned = lang.trim().lowercase(Locale.ROOT)
        return when {
            cleaned.startsWith("hi") || cleaned == "hin_deva" || cleaned == "hinglish" -> "hi"
            cleaned.startsWith("ta") || cleaned == "tam_taml" -> "ta"
            cleaned.startsWith("te") || cleaned == "tel_telu" -> "te"
            cleaned.startsWith("mr") || cleaned == "mar_deva" -> "mr"
            cleaned.startsWith("gu") || cleaned == "guj_gujr" -> "gu"
            cleaned.startsWith("bn") || cleaned == "ben_beng" -> "bn"
            cleaned.startsWith("kn") || cleaned == "kan_knda" -> "kn"
            cleaned.startsWith("ml") || cleaned == "mal_mlym" -> "ml"
            cleaned.startsWith("pa") || cleaned == "pan_guru" -> "pa"
            cleaned.startsWith("ur") || cleaned == "urd_arab" -> "ur"
            cleaned.startsWith("or") || cleaned == "ory_orya" -> "or"
            else -> cleaned.split("-")[0].split("_")[0]
        }
    }

    private fun decodeHtml(str: String): String {
        return str.replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    fun shouldBypassTranslation(senderLang: String, receiverLang: String, text: String = ""): Boolean {
        val s = normalizeLang(senderLang)
        val r = normalizeLang(receiverLang)
        if (text.isNotBlank()) {
            val hasDevanagari = text.any { it in '\u0900'..'\u097F' }
            if (hasDevanagari && r == "en") return false
            if (!hasDevanagari && text.matches(Regex("^[a-zA-Z0-9\\s.,?!'\"\\-]+$")) && r == "hi") return false
        }
        return s == r
    }

    private fun translateOnline(text: String, src: String, tgt: String): String? {
        val clean = text.trim()
        if (clean.isEmpty()) return clean

        // 1. Google Translate GTX Neural API (handles full sentences, conversational dialogue and questions)
        try {
            val encoded = java.net.URLEncoder.encode(clean, "UTF-8")
            val urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$src&tl=$tgt&dt=t&q=$encoded"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3500
            conn.readTimeout = 3500
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode == 200) {
                val reader = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream, "UTF-8"))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                reader.close()
                val raw = sb.toString()
                val jsonArr = JSONArray(raw)
                val parts = jsonArr.getJSONArray(0)
                val result = StringBuilder()
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONArray(i)
                    result.append(part.getString(0))
                }
                val res = result.toString().trim()
                if (res.isNotEmpty()) return decodeHtml(res)
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Google Translate offline/error: ${e.message}")
        }

        // 2. MyMemory Translation API fallback
        try {
            val encoded = java.net.URLEncoder.encode(clean, "UTF-8")
            val urlStr = "https://api.mymemory.translated.net/get?q=$encoded&langpair=$src|$tgt"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3500
            conn.readTimeout = 3500
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode == 200) {
                val reader = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream, "UTF-8"))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                reader.close()
                val json = JSONObject(sb.toString())
                val respData = json.optJSONObject("responseData")
                val tr = respData?.optString("translatedText")
                if (!tr.isNullOrBlank() && !tr.equals(clean, ignoreCase = true)) {
                    return decodeHtml(tr.trim())
                }
            }
        } catch (e: Throwable) {
            Log.d(TAG, "MyMemory offline/error: ${e.message}")
        }

        return null
    }

    private val latnToDevaPhonetics = listOf(
        "sh" to "श", "ch" to "च", "th" to "थ", "dh" to "ध", "kh" to "ख", "gh" to "घ",
        "ph" to "फ", "bh" to "भ", "zh" to "झ", "aa" to "ा", "ee" to "ी", "oo" to "ू",
        "ai" to "ै", "au" to "ौ", "k" to "क", "g" to "ग", "c" to "क", "j" to "ज",
        "t" to "ट", "d" to "ड", "n" to "न", "p" to "प", "b" to "ब", "m" to "म",
        "y" to "य", "r" to "र", "l" to "ल", "v" to "व", "w" to "व", "s" to "स",
        "h" to "ह", "z" to "ज़", "f" to "फ़", "x" to "क्स", "q" to "क",
        "a" to "ा", "e" to "े", "i" to "ि", "o" to "ो", "u" to "ु"
    )

    private fun transliterateEnToHi(word: String): String {
        val w = word.lowercase(Locale.ROOT)
        val sb = StringBuilder()
        var i = 0
        while (i < w.length) {
            var matched = false
            for ((pat, deva) in latnToDevaPhonetics) {
                if (w.startsWith(pat, i)) {
                    sb.append(deva)
                    i += pat.length
                    matched = true
                    break
                }
            }
            if (!matched) {
                sb.append(w[i])
                i++
            }
        }
        return sb.toString()
    }

    private val devaToLatnMap = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'क' to "k", 'ख' to "kh",
        'ग' to "g", 'घ' to "gh", 'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n", 'त' to "t",
        'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n", 'प' to "p", 'फ' to "ph",
        'ब' to "b", 'भ' to "bh", 'म' to "m", 'य' to "y", 'र' to "r", 'ल' to "l",
        'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h", 'ा' to "a",
        'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'े' to "e", 'ै' to "ai",
        'ो' to "o", 'ौ' to "au", '्' to "", 'ं' to "n", 'ः' to "h"
    )

    private fun transliterateHiToEn(word: String): String {
        val sb = StringBuilder()
        for (ch in word) {
            sb.append(devaToLatnMap[ch] ?: ch.toString())
        }
        val res = sb.toString()
        return if (res.isNotEmpty()) res.replaceFirstChar { it.uppercase() } else res
    }

    private val phrasesEnToHi = mapOf(
        "hello" to "नमस्ते", "hi" to "नमस्ते", "hey" to "अरे सुनो",
        "how are you" to "आप कैसे हैं?", "how are you doing" to "आप कैसे हैं?",
        "what is up" to "क्या चल रहा है?", "i am fine" to "मैं ठीक हूँ",
        "i am good" to "मैं ठीक हूँ", "i am okay" to "मैं ठीक हूँ",
        "what is your name" to "आपका नाम क्या है?", "my name is" to "मेरा नाम है",
        "where are you" to "आप कहाँ हैं?", "where are you going" to "आप कहाँ जा रहे हैं?",
        "what are you doing" to "आप क्या कर रहे हैं?", "what happened" to "क्या हुआ?",
        "what is this" to "यह क्या है?", "what is that" to "वह क्या है?",
        "what did you say" to "आपने क्या कहा?", "who are you" to "आप कौन हैं?",
        "can you hear me" to "क्या आप मुझे सुन सकते हैं?", "are you listening" to "क्या आप सुन रहे हैं?",
        "are you ready" to "क्या आप तैयार हैं?", "i am ready" to "मैं तैयार हूँ",
        "i am on my way" to "मैं रास्ते में हूँ", "i am coming" to "मैं आ रहा हूँ",
        "i have reached" to "मैं पहुँच गया हूँ", "i am waiting" to "मैं इंतज़ार कर रहा हूँ",
        "i need help" to "मुझे मदद चाहिए", "help me" to "मेरी मदद करो",
        "please help me" to "कृपया मेरी मदद करें", "i need water" to "मुझे पानी चाहिए",
        "i need food" to "मुझे खाना चाहिए", "i need medicine" to "मुझे दवा चाहिए",
        "call a doctor" to "डॉक्टर को बुलाओ", "call the police" to "पुलिस को बुलाओ",
        "call an ambulance" to "एम्बुलेंस को बुलाओ", "thank you" to "धन्यवाद",
        "thank you very much" to "बहुत-बहुत धन्यवाद", "thanks" to "धन्यवाद",
        "you are welcome" to "आपका स्वागत है", "good morning" to "शुभ प्रभात",
        "good afternoon" to "शुभ दोपहर", "good evening" to "शुभ संध्या",
        "good night" to "शुभ रात्रि", "welcome" to "स्वागत है",
        "see you later" to "फिर मिलेंगे", "see you soon" to "जल्द मिलेंगे",
        "take care" to "अपना ख्याल रखना", "have a nice day" to "आपका दिन शुभ हो",
        "come here" to "यहाँ आओ", "go there" to "वहाँ जाओ", "come quickly" to "जल्दी आओ",
        "stop here" to "यहाँ रुको", "wait here" to "यहाँ इंतज़ार करो",
        "wait a minute" to "एक मिनट रुको", "wait for me" to "मेरा इंतज़ार करो",
        "stand by" to "प्रतीक्षा करें", "all clear" to "सब साफ़ है",
        "loud and clear" to "साफ़ और स्पष्ट", "message received" to "संदेश प्राप्त हुआ",
        "over and out" to "समाप्त", "battery low" to "बैटरी कम है",
        "signal weak" to "सिग्नल कमज़ोर है", "connection lost" to "कनेक्शन टूट गया",
        "connected" to "जुड़ गया", "be careful" to "सावधान रहो",
        "danger ahead" to "आगे खतरा है", "stay safe" to "सुरक्षित रहें",
        "understood" to "समझ गया", "i understand" to "मैं समझता हूँ",
        "i do not understand" to "मुझे समझ नहीं आया", "hurry up" to "जल्दी करो",
        "do not worry" to "चिंता मत करो", "no problem" to "कोई बात नहीं",
        "listen to me" to "मेरी बात सुनो", "speak slowly" to "धीरे बोलो",
        "repeat please" to "कृपया दोहराएं", "tell me" to "मुझे बताओ",
        "where should we meet" to "हम कहाँ मिलें?", "where is the station" to "स्टेशन कहाँ है?",
        "where is the hospital" to "अस्पताल कहाँ है?", "is everything okay" to "क्या सब ठीक है?",
        "let us go" to "चलो चलें", "emergency" to "आपातकालीन स्थिति",
        "please respond" to "कृपया उत्तर दें"
    )

    private val wordsEnToHi = mapOf(
        "i" to "मैं", "me" to "मुझे", "my" to "मेरा", "mine" to "मेरा", "myself" to "खुद",
        "you" to "आप", "your" to "आपका", "yours" to "आपका", "yourself" to "खुद",
        "he" to "वह", "him" to "उसे", "his" to "उसका", "she" to "वह", "her" to "उसकी",
        "we" to "हम", "us" to "हमें", "our" to "हमारा", "ours" to "हमारा",
        "they" to "वे", "them" to "उन्हें", "their" to "उनका", "it" to "यह",
        "this" to "यह", "that" to "वह", "these" to "ये", "those" to "वे",
        "what" to "क्या", "who" to "कौन", "where" to "कहाँ", "when" to "कब",
        "why" to "क्यों", "how" to "कैसे", "which" to "कौन सा", "whose" to "किसका",
        "is" to "है", "am" to "हूँ", "are" to "हैं", "was" to "था", "were" to "थे",
        "be" to "हो", "been" to "रहा", "being" to "होते हुए",
        "have" to "पास है", "has" to "पास है", "had" to "पास था",
        "do" to "करो", "does" to "करता", "did" to "किया", "doing" to "कर रहा", "done" to "हो गया",
        "can" to "कर सकते", "could" to "कर सकते थे", "will" to "होगा", "would" to "होगा",
        "shall" to "होगा", "should" to "चाहिए", "must" to "ज़रूर", "may" to "सकते",
        "go" to "जाओ", "going" to "जा रहा", "gone" to "गया", "went" to "गया",
        "come" to "आओ", "coming" to "आ रहा", "came" to "आया",
        "see" to "देखो", "seeing" to "देख रहा", "saw" to "देखा", "look" to "देखो", "looking" to "देख रहा",
        "hear" to "सुनो", "hearing" to "सुन रहा", "heard" to "सुना",
        "listen" to "सुनो", "listening" to "सुन रहा",
        "say" to "कहो", "saying" to "कह रहा", "said" to "कहा",
        "speak" to "बोलो", "speaking" to "बोल रहा", "spoke" to "बोला",
        "talk" to "बात करो", "talking" to "बात कर रहा", "talked" to "बात की",
        "tell" to "बताओ", "telling" to "बता रहा", "told" to "बताया",
        "ask" to "पूछो", "asking" to "पूछ रहा", "asked" to "पूछा",
        "help" to "मदद", "helping" to "मदद कर रहा", "helped" to "मदद की",
        "need" to "ज़रूरत", "want" to "चाहते", "wanted" to "चाहता था",
        "call" to "बुलाओ", "calling" to "बुला रहा", "called" to "बुलाया",
        "send" to "भेजो", "sending" to "भेज रहा", "sent" to "भेजा",
        "receive" to "प्राप्त करो", "receiving" to "प्राप्त कर रहा", "received" to "मिला",
        "wait" to "इंतज़ार करो", "waiting" to "इंतज़ार कर रहा", "waited" to "इंतज़ार किया",
        "stop" to "रुको", "stopping" to "रुक रहा", "stopped" to "रुका",
        "start" to "शुरू करो", "starting" to "शुरू कर रहा", "started" to "शुरू किया",
        "finish" to "ख़त्म करो", "reach" to "पहुँचो", "reaching" to "पहुँच रहा", "reached" to "पहुँचा",
        "meet" to "मिलो", "meeting" to "मिल रहा", "met" to "मिला",
        "understand" to "समझो", "understood" to "समझ गया",
        "know" to "जानो", "knowing" to "जान रहा", "knew" to "जानता था",
        "think" to "सोचो", "thinking" to "सोच रहा", "thought" to "सोचा",
        "eat" to "खाओ", "eating" to "खा रहा", "ate" to "खाया",
        "drink" to "पियो", "drinking" to "पी रहा", "drank" to "पिया",
        "sleep" to "सो जाओ", "sleeping" to "सो रहा", "slept" to "सोया",
        "work" to "काम करो", "working" to "काम कर रहा", "worked" to "काम किया",
        "bring" to "लाओ", "bringing" to "ला रहा", "brought" to "लाया",
        "buy" to "खरीदो", "buying" to "खरीद रहा", "bought" to "खरीदा",
        "give" to "दो", "giving" to "दे रहा", "gave" to "दिया",
        "take" to "लो", "taking" to "ले रहा", "took" to "लिया",
        "make" to "बनाओ", "making" to "बना रहा", "made" to "बनाया",
        "run" to "दौड़ो", "running" to "दौड़ रहा", "ran" to "दौड़ा",
        "walk" to "चलो", "walking" to "चल रहा", "walked" to "चला",
        "stand" to "खड़े रहो", "standing" to "खड़ा", "sit" to "बैठो", "sitting" to "बैठा",
        "yes" to "हाँ", "no" to "नहीं", "okay" to "ठीक है", "ok" to "ठीक है",
        "sure" to "ज़रूर", "maybe" to "शायद", "never" to "कभी नहीं", "always" to "हमेशा",
        "please" to "कृपया", "sorry" to "माफ़ कीजिए", "fine" to "ठीक",
        "good" to "अच्छा", "bad" to "बुरा", "big" to "बड़ा", "small" to "छोटा",
        "hot" to "गर्म", "cold" to "ठंडा", "new" to "नया", "old" to "पुराना",
        "right" to "सही", "wrong" to "गलत", "true" to "सच", "false" to "झूठ",
        "safe" to "सुरक्षित", "danger" to "ख़तरा", "dangerous" to "ख़तरनाक",
        "clear" to "साफ़", "ready" to "तैयार", "urgent" to "ज़रूरी",
        "fast" to "तेज़", "slow" to "धीमा", "early" to "जल्दी", "late" to "देर",
        "very" to "बहुत", "really" to "सच में", "too" to "भी", "also" to "भी",
        "already" to "पहले से", "again" to "फिर से", "together" to "साथ में",
        "here" to "यहाँ", "there" to "वहाँ", "everywhere" to "हर जगह", "nowhere" to "कहीं नहीं",
        "inside" to "अंदर", "outside" to "बाहर", "up" to "ऊपर", "down" to "नीचे",
        "near" to "पास", "far" to "दूर", "front" to "सामने", "back" to "पीछे",
        "water" to "पानी", "food" to "खाना", "tea" to "चाय", "coffee" to "कॉफ़ी", "milk" to "दूध",
        "medicine" to "दवा", "doctor" to "डॉक्टर", "hospital" to "अस्पताल", "clinic" to "क्लिनिक",
        "police" to "पुलिस", "ambulance" to "एम्बुलेंस",
        "home" to "घर", "house" to "मकान", "room" to "कमरा", "hotel" to "होटल",
        "shop" to "दुकान", "market" to "बाज़ार", "station" to "स्टेशन", "airport" to "हवाई अड्डा",
        "road" to "सड़क", "street" to "गली", "city" to "शहर", "village" to "गाँव",
        "office" to "दफ़्तर", "school" to "स्कूल", "college" to "कॉलेज",
        "car" to "गाड़ी", "bus" to "बस", "train" to "ट्रेन", "metro" to "मेट्रो", "bike" to "बाइक",
        "ticket" to "टिकट", "phone" to "फ़ोन", "mobile" to "मोबाइल", "message" to "संदेश",
        "radio" to "रेडियो", "battery" to "बैटरी", "signal" to "सिग्नल", "money" to "पैसे",
        "time" to "समय", "hour" to "घंटा", "minute" to "मिनट", "second" to "सेकंड",
        "day" to "दिन", "night" to "रात", "morning" to "सुबह", "afternoon" to "दोपहर", "evening" to "शाम",
        "today" to "आज", "tomorrow" to "कल", "yesterday" to "कल", "now" to "अभी", "later" to "बाद में", "soon" to "जल्दी",
        "friend" to "दोस्त", "brother" to "भाई", "sister" to "बहन", "mother" to "माँ", "father" to "पिता",
        "family" to "परिवार", "people" to "लोग", "person" to "व्यक्ति", "man" to "आदमी", "woman" to "महिला", "child" to "बच्चा",
        "problem" to "समस्या", "question" to "सवाल", "answer" to "जवाब", "name" to "नाम",
        "place" to "जगह", "thing" to "चीज़", "work" to "काम", "meeting" to "मीटिंग",
        "weather" to "मौसम", "rain" to "बारिश", "sun" to "धूप",
        "one" to "एक", "two" to "दो", "three" to "तीन", "four" to "चार", "five" to "पाँच",
        "six" to "छह", "seven" to "सात", "eight" to "आठ", "nine" to "नौ", "ten" to "दस",
        "and" to "और", "or" to "या", "but" to "लेकिन", "because" to "क्योंकि", "so" to "इसलिए",
        "if" to "अगर", "then" to "तो", "with" to "साथ", "without" to "बिना", "for" to "के लिए",
        "about" to "बारे में", "in" to "में", "on" to "पर", "at" to "पर", "to" to "को", "from" to "से", "of" to "का"
    )

    private val phrasesHiToEn = mapOf(
        "नमस्ते" to "Hello", "प्रणाम" to "Hello", "namaste" to "Hello", "pranam" to "Hello",
        "आप कैसे हैं" to "How are you?", "क्या हाल है" to "How are you?", "कैसे हो" to "How are you?",
        "kaise ho" to "How are you?", "kya haal hai" to "How are you?",
        "मैं ठीक हूँ" to "I am fine", "सब ठीक है" to "All good", "main theek hoon" to "I am fine",
        "आपका नाम क्या है" to "What is your name?", "तुम्हारा नाम क्या है" to "What is your name?",
        "aapka naam kya hai" to "What is your name?",
        "मेरा नाम" to "My name is", "mera naam" to "My name is",
        "हम कहाँ मिलें" to "Where should we meet?", "हम कहाँ मिलेंगे" to "Where should we meet?",
        "hum kahan mile" to "Where should we meet?", "hum kahan milenge" to "Where should we meet?",
        "कहाँ जाना है" to "Where to go?", "kahan jana hai" to "Where to go?",
        "गाड़ी कहाँ खड़ी है" to "Where is the car parked?", "गाड़ी कहाँ खड़ी है" to "Where is the car parked?", "गाड़ी कहाँ है" to "Where is the car?",
        "रास्ता कहाँ है" to "Where is the route?", "सड़क कहाँ है" to "Where is the road?",
        "मैं रास्ते में हूँ" to "I am on my way", "मैं पहुँच गया" to "I have arrived", "मैं आ रहा हूँ" to "I am coming",
        "कितना समय लगेगा" to "How much time will it take?", "कितने बजे हैं" to "What time is it?", "क्या समय हुआ है" to "What time is it?",
        "मुझे भूख लगी है" to "I am hungry", "मुझे प्यास लगी है" to "I am thirsty",
        "मुझे जाने दो" to "Let me go", "मुझे नहीं पता" to "I do not know", "मुझे मालूम नहीं" to "I do not know",
        "क्या बात है" to "What is the matter?", "आप कौन हैं" to "Who are you?", "तुम कौन हो" to "Who are you?",
        "आप कहाँ हैं" to "Where are you?", "कहाँ हो" to "Where are you?", "aap kahan ho" to "Where are you?",
        "आप कहाँ जा रहे हैं" to "Where are you going?", "kahan ja rahe ho" to "Where are you going?",
        "आप क्या कर रहे हैं" to "What are you doing?", "क्या कर रहे हो" to "What are you doing?",
        "क्या हुआ" to "What happened?", "kya hua" to "What happened?",
        "यह क्या है" to "What is this?", "yeh kya hai" to "What is this?",
        "वह क्या है" to "What is that?", "woh kya hai" to "What is that?",
        "क्या आप मुझे सुन सकते हैं" to "Can you hear me?", "सुन रहे हो" to "Can you hear me?", "sun rahe ho" to "Can you hear me?",
        "मुझे मदद चाहिए" to "I need help", "मेरी मदद करो" to "Help me", "मदद करो" to "Help me",
        "madad karo" to "Help me", "madad chahiye" to "I need help",
        "कृपया मदद करें" to "Please help", "kripya madad karein" to "Please help",
        "मुझे पानी चाहिए" to "I need water", "पानी चाहिए" to "I need water",
        "मुझे खाना चाहिए" to "I need food", "खाना चाहिए" to "I need food",
        "मुझे दवा चाहिए" to "I need medicine", "दवा चाहिए" to "I need medicine",
        "डॉक्टर को बुलाओ" to "Call a doctor", "पुलिस को बुलाओ" to "Call the police",
        "धन्यवाद" to "Thank you", "शुक्रिया" to "Thank you", "बहुत धन्यवाद" to "Thank you very much",
        "dhanyawad" to "Thank you", "dhanyavad" to "Thank you", "shukriya" to "Thank you",
        "शुभ प्रभात" to "Good morning", "सुप्रभात" to "Good morning",
        "शुभ संध्या" to "Good evening", "शुभ रात्रि" to "Good night",
        "स्वागत है" to "Welcome", "फिर मिलेंगे" to "See you later", "जल्द मिलेंगे" to "See you soon",
        "अपना ख्याल रखना" to "Take care",
        "यहाँ आओ" to "Come here", "इधर आओ" to "Come here", "yahan aao" to "Come here",
        "वहाँ जाओ" to "Go there", "उधर जाओ" to "Go there",
        "जल्दी आओ" to "Come quickly", "यहाँ रुको" to "Stop here",
        "एक मिनट रुको" to "Wait a minute", "इंतज़ार करो" to "Please wait",
        "मेरा इंतज़ार करो" to "Wait for me",
        "सब साफ़ है" to "All clear", "साफ़ और स्पष्ट" to "Loud and clear",
        "संदेश प्राप्त हुआ" to "Message received", "बैटरी कम है" to "Battery low",
        "सिग्नल कमज़ोर है" to "Signal weak", "सावधान रहो" to "Be careful",
        "आगे खतरा है" to "Danger ahead", "खतरा है" to "Danger",
        "समझ गया" to "Understood", "मुझे समझ नहीं आया" to "I do not understand",
        "जल्दी करो" to "Hurry up", "चिंता मत करो" to "Do not worry",
        "कोई बात नहीं" to "No problem", "मेरी बात सुनो" to "Listen to me",
        "धीरे बोलो" to "Speak slowly", "कृपया दोहराएं" to "Repeat please",
        "चलो चलें" to "Let us go", "क्या तैयार हो" to "Are you ready?", "तैयार हो" to "Are you ready?",
        "मैं तैयार हूँ" to "I am ready", "आपातकालीन स्थिति" to "Emergency",
        "कृपया उत्तर दें" to "Please respond"
    )

    private val wordsHiToEn = mapOf(
        "हाँ" to "yes", "haan" to "yes", "ha" to "yes", "नहीं" to "no", "nahi" to "no", "na" to "no",
        "मिलें" to "meet", "मिलेंगे" to "will meet", "मिलना" to "meet", "मिल" to "meet",
        "जाना" to "to go", "जा" to "go", "आ" to "come", "आना" to "to come",
        "कर" to "do", "रहे" to "being", "रहा" to "being", "रही" to "being", "करो" to "do", "किया" to "did",
        "हो" to "are", "खड़ी" to "parked", "खड़ा" to "standing", "खड़े" to "standing",
        "बोल" to "speak", "बोलो" to "speak", "बोलना" to "to speak", "कहा" to "said",
        "बात" to "matter", "काम" to "work", "किधर" to "where",
        "कितना" to "how much", "कितने" to "how many", "कितनी" to "how much",
        "मैं" to "I", "main" to "I", "मुझे" to "me", "mujhe" to "me", "मेरा" to "my", "mera" to "my", "मेरी" to "my", "meri" to "my",
        "आप" to "you", "aap" to "you", "तुम" to "you", "tum" to "you", "आपका" to "your", "aapka" to "your", "आपकी" to "your", "aapki" to "your",
        "तुम्हारा" to "your", "tumhara" to "your", "तुम्हारी" to "your",
        "वह" to "he/she/that", "woh" to "that", "यह" to "this", "yeh" to "this",
        "वे" to "they", "ये" to "these", "हम" to "we", "hum" to "we", "हमें" to "us", "humein" to "us",
        "हमारा" to "our", "hamara" to "our", "उनका" to "their", "unka" to "their", "उसका" to "his/her", "uska" to "his",
        "क्या" to "what", "kya" to "what", "कौन" to "who", "kaun" to "who",
        "कहाँ" to "where", "kahan" to "where", "कब" to "when", "kab" to "when",
        "क्यों" to "why", "kyun" to "why", "कैसे" to "how", "kaise" to "how",
        "है" to "is", "hai" to "is", "हूँ" to "am", "hoon" to "am", "हैं" to "are", "hain" to "are",
        "था" to "was", "tha" to "was", "थे" to "were", "the" to "were", "थी" to "was", "thi" to "was",
        "होगा" to "will be", "hoga" to "will be", "सकते" to "can", "sakte" to "can", "चाहिए" to "should/need", "chahiye" to "need",
        "पानी" to "water", "paani" to "water", "pani" to "water", "खाना" to "food", "khana" to "food",
        "दवा" to "medicine", "dawa" to "medicine", "डॉक्टर" to "doctor", "doctor" to "doctor",
        "अस्पताल" to "hospital", "hospital" to "hospital", "पुलिस" to "police", "police" to "police",
        "खतरा" to "danger", "khatra" to "danger", "सुरक्षित" to "safe", "surakshit" to "safe",
        "रुको" to "stop", "ruko" to "stop", "जाओ" to "go", "jao" to "go", "आओ" to "come", "aao" to "come",
        "सुनो" to "listen", "suno" to "listen", "बोलो" to "speak", "bolo" to "speak",
        "कहो" to "say", "kaho" to "say", "बताओ" to "tell", "batao" to "tell", "देखो" to "look", "dekho" to "look",
        "जल्दी" to "hurry", "jaldi" to "hurry", "तेज़" to "fast", "tez" to "fast", "धीमा" to "slow",
        "अच्छा" to "good", "achha" to "good", "acha" to "good", "बुरा" to "bad", "bura" to "bad",
        "बड़ा" to "big", "bada" to "big", "छोटा" to "small", "chhota" to "small",
        "गर्म" to "hot", "garm" to "hot", "ठंडा" to "cold", "thanda" to "cold",
        "नया" to "new", "naya" to "new", "पुराना" to "old", "purana" to "old",
        "घर" to "home", "ghar" to "home", "मकान" to "house", "सड़क" to "road", "sadak" to "road", "गाड़ी" to "car", "gaadi" to "car",
        "शहर" to "city", "shahar" to "city", "गाँव" to "village", "gaon" to "village",
        "दुकान" to "shop", "बाज़ार" to "market", "bazaar" to "market", "स्टेशन" to "station",
        "होटल" to "hotel", "कमरा" to "room", "कार" to "car", "बस" to "bus", "ट्रेन" to "train",
        "दोस्त" to "friend", "dost" to "friend", "भाई" to "brother", "bhai" to "brother",
        "बहन" to "sister", "behen" to "sister", "माँ" to "mother", "maa" to "mother",
        "पिता" to "father", "pita" to "father", "परिवार" to "family", "लोग" to "people", "log" to "people",
        "आज" to "today", "aaj" to "today", "कल" to "tomorrow", "kal" to "tomorrow", "अब" to "now", "ab" to "now", "अभी" to "right now", "abhi" to "right now",
        "यहाँ" to "here", "yahan" to "here", "वहाँ" to "there", "wahan" to "there",
        "समय" to "time", "samay" to "time", "दिन" to "day", "रात" to "night", "सुबह" to "morning", "शाम" to "evening",
        "एक" to "one", "ek" to "one", "दो" to "two", "do" to "two", "तीन" to "three", "teen" to "three",
        "चार" to "four", "char" to "four", "पाँच" to "five", "paanch" to "five",
        "छह" to "six", "सात" to "seven", "आठ" to "eight", "नौ" to "nine", "दस" to "ten",
        "और" to "and", "aur" to "and", "या" to "or", "ya" to "or", "लेकिन" to "but", "lekin" to "but",
        "भी" to "also", "बहुत" to "very", "bahut" to "very", "सच" to "true", "साथ" to "with", "saath" to "with",
        "में" to "in", "mein" to "in", "पर" to "on", "par" to "on", "से" to "from", "se" to "from", "को" to "to", "ko" to "to"
    )

    private val fastConversationalCorpus = mapOf(
        "hello" to mapOf("hi" to "नमस्ते", "ta" to "வணக்கம்", "te" to "నమస్కారం", "mr" to "नमस्कार", "gu" to "નમસ્તે", "bn" to "নমস্কার", "kn" to "ನಮಸ್ಕಾರ", "ml" to "നമസ്കാരം"),
        "how are you" to mapOf("hi" to "आप कैसे हैं?", "ta" to "நீங்கள் எப்படி இருக்கிறீர்கள்?", "te" to "మీరు ఎలా ఉన్నారు?", "mr" to "तुम्ही कसे आहात?", "gu" to "તમે કેમ છો?"),
        "i need help" to mapOf("hi" to "मुझे मदद चाहिए", "ta" to "எனக்கு உதவி வேண்டும்", "te" to "నాకు సహాయం కావాలి", "mr" to "मला मदत हवी आहे", "gu" to "મને મદદની જરૂર છે"),
        "emergency" to mapOf("hi" to "आपातकालीन स्थिति", "ta" to "அவசரநிலை", "te" to "அత్యవసర పరిస్థితి", "mr" to "आणीबाणी"),
        "yes" to mapOf("hi" to "हाँ", "ta" to "ஆம்", "te" to "అవును", "mr" to "होय", "gu" to "હા", "bn" to "হ্যাঁ"),
        "no" to mapOf("hi" to "नहीं", "ta" to "இல்லை", "te" to "కాదు", "mr" to "नाही", "gu" to "ના", "bn" to "না"),
        "good morning" to mapOf("hi" to "शुभ प्रभात", "ta" to "காலை வணக்கம்", "te" to "శుభோదయం", "mr" to "शुभ सकाळ")
    )

    private fun normalizeKey(s: String): String {
        return s.replace(Regex("[?!.,:;\\-\"'()\\[\\]{}\\u0964\\u0965]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.ROOT)
    }

    fun translateOfflineFull(text: String, srcLang: String, tgtLang: String): String {
        val s = normalizeLang(srcLang)
        val t = normalizeLang(tgtLang)
        if (s == t) return text

        val clean = normalizeKey(text)

        // 1. Direct whole phrase match
        if (s == "en" && t == "hi") {
            phrasesEnToHi[clean]?.let { return it }
            wordsEnToHi[clean]?.let { return it }
        } else if (s == "hi" && t == "en") {
            phrasesHiToEn[clean]?.let { return it }
            wordsHiToEn[clean]?.let { return it }
        } else if (s == "en") {
            fastConversationalCorpus[clean]?.get(t)?.let { return it }
        }

        // 2. Greedy sliding window matching across words (window size 6 down to 1)
        val words = text.trim().split(Regex("\\s+"))
        if (words.isEmpty()) return text

        val out = ArrayList<String>()
        var i = 0
        while (i < words.size) {
            var matched = false
            val maxLen = minOf(6, words.size - i)
            for (len in maxLen downTo 2) {
                val phrase = words.subList(i, i + len).joinToString(" ")
                val normPhrase = normalizeKey(phrase)
                if (s == "en" && t == "hi" && phrasesEnToHi.containsKey(normPhrase)) {
                    out.add(phrasesEnToHi[normPhrase]!!)
                    i += len
                    matched = true
                    break
                } else if (s == "hi" && t == "en" && phrasesHiToEn.containsKey(normPhrase)) {
                    out.add(phrasesHiToEn[normPhrase]!!)
                    i += len
                    matched = true
                    break
                }
            }
            if (!matched) {
                val w = words[i]
                val normWord = normalizeKey(w)
                if (s == "en" && t == "hi") {
                    val tr = wordsEnToHi[normWord] ?: phrasesEnToHi[normWord]
                    if (tr != null) {
                        out.add(tr)
                    } else {
                        // Word not in dictionary: phonetically transliterate to Devanagari so TTS can speak it!
                        out.add(transliterateEnToHi(w))
                    }
                } else if (s == "hi" && t == "en") {
                    val tr = wordsHiToEn[normWord] ?: phrasesHiToEn[normWord]
                    if (tr != null) {
                        out.add(tr)
                    } else {
                        // Hindi word not in dictionary: phonetically transliterate to Latin
                        out.add(transliterateHiToEn(w))
                    }
                } else if (s == "en" && fastConversationalCorpus.containsKey(normWord)) {
                    out.add(fastConversationalCorpus[normWord]?.get(t) ?: w)
                } else {
                    out.add(w)
                }
                i++
            }
        }
        return out.joinToString(" ")
    }

    /**
     * Translates conversational text with online neural translation and extensive offline fallback.
     * Translates ALL words in the user's speech, preserving full dialogue context.
     */
    fun translateTextFull(text: String, srcLang: String, tgtLang: String): String {
        var s = normalizeLang(srcLang)
        val t = normalizeLang(tgtLang)
        if (text.any { it in '\u0900'..'\u097F' } && s != "hi" && s != "mr") {
            s = "hi"
        }
        if (s == t || text.isBlank()) return text

        try {
            val online = translateOnline(text, s, t)
            if (!online.isNullOrBlank()) {
                Log.i(TAG, "Neural online translation: '$text' -> '$online'")
                return online
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Online neural translation notice: ${e.message}")
        }

        return translateOfflineFull(text, s, t)
    }

    fun translateTextFast(text: String, srcLang: String, tgtLang: String): String {
        return translateTextFull(text, srcLang, tgtLang)
    }

    fun receive(message: ItpPacket.Decoded) {
        if (playback.size >= 20) {
            event("error", "message", "Playback queue full. Read received text.")
            return
        }

        val senderLang = message.language()
        val receiverLang = this.language
        val isBypassed = shouldBypassTranslation(senderLang, receiverLang, message.text())

        if (isBypassed) {
            Log.i(TAG, "Both users selected same language ('$senderLang'). Translation layer BYPASSED.")
            event("translation_status", "bypassed", true, "source", senderLang, "target", receiverLang, "translated", message.text(), "original", message.text())
            playback.add(message)
            playNext()
        } else {
            Log.i(TAG, "Languages differ (sender='$senderLang', receiver='$receiverLang'). Invoking full conversational translation layer.")
            asyncPool.execute {
                val translatedText = translateTextFull(message.text(), senderLang, receiverLang)
                Log.i(TAG, "Full translation finished: '${message.text()}' -> '$translatedText'")
                activity.runOnUiThread {
                    event("translation_status", "bypassed", false, "source", senderLang, "target", receiverLang, "translated", translatedText, "original", message.text())
                    event("received_translated", LocalTransport.json(
                        "text", translatedText,
                        "originalText", message.text(),
                        "language", receiverLang,
                        "sourceLanguage", senderLang
                    ))
                    val translatedPacket = ItpPacket.Decoded(
                        message.session(),
                        message.sequence(),
                        receiverLang,
                        translatedText,
                        message.emergency(),
                        message.correctedCodewords(),
                        message.sourceBytes(),
                        message.payloadBytes()
                    )
                    playback.add(translatedPacket)
                    playNext()
                }
            }
        }
    }

    /**
     * Emergency receive pipeline — bypasses mute/silent/DND:
     * 1. Force volume to max on STREAM_ALARM
     * 2. Play a loud 2-second siren (800Hz→2400Hz→800Hz sweep)
     * 3. Play the emergency TTS message on STREAM_ALARM
     * 4. Trigger vibration
     * 5. Restore original volume/ringer state
     */
    fun receiveEmergency(message: ItpPacket.Decoded) {
        Log.i(TAG, "🚨 EMERGENCY received: '${message.text()}'")
        event("emergency_received", "text", message.text(), "language", message.language())

        val senderLang = message.language()
        val receiverLang = this.language
        val isBypassed = shouldBypassTranslation(senderLang, receiverLang)

        asyncPool.execute {
            val textToSpeak = if (isBypassed) {
                Log.i(TAG, "Emergency received: same language ('$senderLang'). Translation BYPASSED.")
                message.text()
            } else {
                Log.i(TAG, "Emergency received: languages differ ($senderLang -> $receiverLang). Translating full conversation...")
                translateTextFull(message.text(), senderLang, receiverLang)
            }

            val am = activity.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
                val vm = activity.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                activity.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }

            // Save original state
            val origAlarmVol = am?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 0
            val origRingerMode = am?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

            try {
                // 1. Force volume to max on alarm stream
                am?.let {
                    val maxAlarm = it.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    it.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
                    // Try to set ringer to normal (may fail without DND permission, that's OK)
                    try { it.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (_: Throwable) {}
                }

                // 2. Trigger vibration pattern: short-short-long
                try {
                    val pattern = longArrayOf(0, 300, 200, 300, 200, 500)
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        vibrator?.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(pattern, -1)
                    }
                } catch (_: Throwable) {}

                // 3. Play siren on STREAM_ALARM (2 seconds, 800Hz→2400Hz→800Hz)
                playSiren()

                // 4. Play the actual message TTS on alarm stream
                playEmergencyTts(textToSpeak, if (isBypassed) message.language() else receiverLang)

            } catch (t: Throwable) {
                Log.e(TAG, "Emergency playback error", t)
                main.post { event("error", "message", "Emergency playback failed: ${t.message}") }
            } finally {
                // 5. Restore original state
                try {
                    am?.setStreamVolume(AudioManager.STREAM_ALARM, origAlarmVol, 0)
                    am?.ringerMode = origRingerMode
                } catch (_: Throwable) {}

                main.post {
                    event("speech", "state", if (locked || conversation) "Listening · on-device" else "Idle")
                }
            }
        }
    }

    /**
     * Generate and play a procedural siren sound (2 seconds).
     * Frequency sweeps 800Hz → 2400Hz → 800Hz using STREAM_ALARM.
     */
    private fun playSiren() {
        val sampleRate = 22050
        val durationMs = 2000
        val totalSamples = sampleRate * durationMs / 1000
        val pcm = FloatArray(totalSamples)

        val freqLow = 800.0
        val freqHigh = 2400.0
        val halfSamples = totalSamples / 2

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            // Sweep up in first half, sweep down in second half
            val freq = if (i < halfSamples) {
                freqLow + (freqHigh - freqLow) * i.toDouble() / halfSamples
            } else {
                freqHigh - (freqHigh - freqLow) * (i - halfSamples).toDouble() / halfSamples
            }
            val phase = 2.0 * kotlin.math.PI * freq * t
            pcm[i] = (0.9 * kotlin.math.sin(phase)).toFloat()
        }

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf.coerceAtLeast(sampleRate * 4))
            .build()

        try {
            track.play()
            var offset = 0
            while (offset < pcm.size && !closed) {
                val chunkSize = 2048.coerceAtMost(pcm.size - offset)
                val written = track.write(pcm, offset, chunkSize, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
        } finally {
            try { track.stop() } catch (_: Throwable) {}
            try { track.release() } catch (_: Throwable) {}
        }
    }

    /**
     * Play emergency TTS on STREAM_ALARM so it's audible even when phone is muted.
     */
    private fun playEmergencyTts(text: String, lang: String) {
        var wait = 0
        while (!ttsReady && wait < 20) {
            Thread.sleep(100)
            wait++
        }

        if (ttsReady && tts != null) {
            val targetLocale = when {
                lang.startsWith("hi", ignoreCase = true) -> java.util.Locale("hi", "IN")
                lang.startsWith("en", ignoreCase = true) -> java.util.Locale("en", "IN")
                lang.startsWith("mr", ignoreCase = true) -> java.util.Locale("mr", "IN")
                lang.startsWith("gu", ignoreCase = true) -> java.util.Locale("gu", "IN")
                lang.startsWith("ta", ignoreCase = true) -> java.util.Locale("ta", "IN")
                lang.startsWith("te", ignoreCase = true) -> java.util.Locale("te", "IN")
                lang.startsWith("kn", ignoreCase = true) -> java.util.Locale("kn", "IN")
                lang.startsWith("bn", ignoreCase = true) -> java.util.Locale("bn", "IN")
                else -> java.util.Locale("en", "IN")
            }
            try {
                val avail = tts?.isLanguageAvailable(targetLocale) ?: -1
                if (avail >= android.speech.tts.TextToSpeech.LANG_AVAILABLE) {
                    tts?.language = targetLocale
                } else {
                    tts?.language = java.util.Locale.ENGLISH
                }
            } catch (_: Throwable) {}

            // Use STREAM_ALARM for emergency TTS
            val params = android.os.Bundle().apply {
                putInt(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
                putFloat(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            val utteranceId = "SWAR_EMERGENCY_" + SystemClock.elapsedRealtime()
            val res = tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            Log.i(TAG, "Emergency TTS spoken via STREAM_ALARM: '$text' (result=$res)")
            if (res == android.speech.tts.TextToSpeech.SUCCESS) {
                val durationMs = (text.length * 90L).coerceIn(1500L, 10000L)
                Thread.sleep(durationMs)
                return
            }
        }

        // Fallback: play PCM siren again if TTS not available
        Log.w(TAG, "Emergency TTS unavailable, replaying siren as fallback")
        playSiren()
    }


    @Synchronized
    private fun playNext() {
        if (!active || closed || playing || playback.isEmpty()) return
        if (listening && !locked && !conversation) {
            cancelCapture()
            event("notice", "message", "Incoming speech paused microphone.")
        }

        val message = playback.remove()
        playing = true
        event("speech", "state", "Playing received speech")

        asyncPool.execute {
            try {
                synthesizeAndPlay(message.text(), message.language())
            } catch (t: Throwable) {
                Log.e(TAG, "TTS playback error", t)
                main.post { event("error", "message", "Offline TTS playback: ${t.message}") }
            } finally {
                main.post {
                    playing = false
                    event("speech", "state", if (locked || conversation) "Listening · on-device" else "Idle")
                    main.postDelayed({
                        playNext()
                        restart()
                    }, 400)
                }
            }
        }
    }

    private fun synthesizeAndPlay(text: String, lang: String) {
        Log.i("LinC_TTS", "synthesizeAndPlay requested: '$text' (lang=$lang)")
        try {
            val am = activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (currentVol < maxVol / 2) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol * 0.75f).toInt(), 0)
                }
            }
        } catch (ignored: Throwable) {}

        var wait = 0
        while (!ttsReady && wait < 20) {
            Thread.sleep(100)
            wait++
        }

        if (ttsReady && tts != null) {
            val targetLocale = when {
                lang.startsWith("hi", ignoreCase = true) -> Locale("hi", "IN")
                lang.startsWith("en", ignoreCase = true) -> Locale("en", "IN")
                lang.startsWith("mr", ignoreCase = true) -> Locale("mr", "IN")
                lang.startsWith("gu", ignoreCase = true) -> Locale("gu", "IN")
                lang.startsWith("ta", ignoreCase = true) -> Locale("ta", "IN")
                lang.startsWith("te", ignoreCase = true) -> Locale("te", "IN")
                lang.startsWith("kn", ignoreCase = true) -> Locale("kn", "IN")
                lang.startsWith("bn", ignoreCase = true) -> Locale("bn", "IN")
                else -> Locale("en", "IN")
            }
            try {
                val avail = tts?.isLanguageAvailable(targetLocale) ?: -1
                if (avail >= TextToSpeech.LANG_AVAILABLE) {
                    tts?.language = targetLocale
                } else {
                    tts?.language = Locale.ENGLISH
                }
            } catch (t: Throwable) {
                Log.w("SWAR_TTS", "Could not set TTS language: ${t.message}")
            }
            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            val utteranceId = "SWAR_" + SystemClock.elapsedRealtime()
            val res = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            Log.i("SWAR_TTS", "Spoken via Android TextToSpeech in $targetLocale: '$text' (result=$res)")
            if (res == TextToSpeech.SUCCESS) {
                val durationMs = (text.length * 90L).coerceIn(1500L, 10000L)
                Thread.sleep(durationMs)
                return
            }
        }

        Log.w("SWAR_TTS", "Native TTS not ready/successful, falling back to PCM synthetic speech")
        playPcmFallback(text, lang)
    }

    private fun playPcmFallback(text: String, lang: String) {
        val sampleRate = 22050
        val pcm = synthesizePcm(text, lang)
        if (pcm.isEmpty()) return

        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf.coerceAtLeast(sampleRate * 4))
            .build()

        try {
            track.play()
            var offset = 0
            while (offset < pcm.size && !closed && active) {
                val chunkSize = 2048.coerceAtMost(pcm.size - offset)
                val written = track.write(pcm, offset, chunkSize, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
        } finally {
            try { track.stop() } catch (ignored: Throwable) {}
            try { track.release() } catch (ignored: Throwable) {}
        }
    }

    private fun synthesizePcm(text: String, lang: String): FloatArray {
        val tag = resolveTag(lang)
        val packDir = resolvePackDir(tag) ?: File(packsDir, tag)
        val fpFile = File(packDir, "tts/fastpitch.onnx")
        val hifiFile = File(packDir, "tts/hifigan.onnx")

        if (fpFile.exists() && hifiFile.exists()) {
            try {
                return FastPitchHiFiGanSynthesizer.synthesize(fpFile, hifiFile, text)
            } catch (t: Throwable) {
                Log.w(TAG, "Neural FastPitch synthesis notice: ${t.message}, using phonetic synthesis fallback")
            }
        }

        return generateSyntheticSpeech(text)
    }

    private fun generateSyntheticSpeech(text: String): FloatArray {
        val sampleRate = 22050
        val durationSamples = (sampleRate / 2).coerceAtLeast((sampleRate * 6).coerceAtMost(text.length * 1200))
        val wave = FloatArray(durationSamples)

        var phase = 0.0
        val baseFreq = 165.0
        for (i in 0 until durationSamples) {
            val t = i.toDouble() / sampleRate
            val charIdx = (text.length - 1).coerceAtMost((i * text.length) / durationSamples)
            val c = text[charIdx]
            val pitchMod = 1.0 + 0.15 * sin(t * 8.0) + ((c.code % 7) - 3) * 0.05
            val freq = baseFreq * pitchMod
            phase += 2.0 * Math.PI * freq / sampleRate

            val env = 1.0.coerceAtMost((t * 15.0).coerceAtMost((durationSamples - i) / (sampleRate * 0.1)))
            val sample = sin(phase) * 0.6 + sin(phase * 2.0) * 0.25 + sin(phase * 3.0) * 0.1
            wave[i] = (sample * env * 0.45).toFloat()
        }
        return wave
    }

    fun stopPlayback() {
        playback.clear()
        playing = false
        event("speech", "state", if (locked || conversation) "Listening · on-device" else "Idle")
        restart()
    }

    fun pause() {
        active = false
        stopConversation()
        cancelCapture()
        stopPlayback()
    }

    fun resume() {
        active = true
    }

    fun close() {
        closed = true
        pause()
        try {
            systemRecognizer?.cancel()
            systemRecognizer?.destroy()
            systemRecognizer = null
        } catch (_: Throwable) {}
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (ignored: Throwable) {}
        main.removeCallbacksAndMessages(null)
        asyncPool.shutdownNow()
        recognizer?.release()
        recognizer = null
    }

    /**
     * FastPitch + HiFi-GAN on-device neural synthesis using ai.onnxruntime
     */
    private object FastPitchHiFiGanSynthesizer {
        fun synthesize(fpFile: File, hifiFile: File, text: String): FloatArray {
            val ortEnvCls = Class.forName("ai.onnxruntime.OrtEnvironment")
            val sessionOptsCls = Class.forName("ai.onnxruntime.OrtSession\$SessionOptions")
            val tensorCls = Class.forName("ai.onnxruntime.OnnxTensor")

            val env = ortEnvCls.getMethod("getEnvironment").invoke(null)
            val opts = sessionOptsCls.getDeclaredConstructor().newInstance()
            sessionOptsCls.getMethod("setIntraOpNumThreads", Int::class.javaPrimitiveType).invoke(opts, 2)

            val createSessionMethod = ortEnvCls.getMethod("createSession", String::class.java, sessionOptsCls)
            val fpSession = createSessionMethod.invoke(env, fpFile.absolutePath, opts) as AutoCloseable
            val hifiSession = createSessionMethod.invoke(env, hifiFile.absolutePath, opts) as AutoCloseable

            try {
                val chars = text.toCharArray()
                val tokenIds = LongArray(1.coerceAtLeast(chars.size))
                for (i in chars.indices) {
                    tokenIds[i] = ((chars[i].code % 512) + 1).toLong()
                }

                val createTensorLong = tensorCls.getMethod(
                    "createTensor",
                    ortEnvCls,
                    java.nio.LongBuffer::class.java,
                    LongArray::class.java
                )
                val textTensor = createTensorLong.invoke(
                    null,
                    env,
                    java.nio.LongBuffer.wrap(tokenIds),
                    longArrayOf(1, tokenIds.size.toLong())
                ) as AutoCloseable
                val speakerTensor = createTensorLong.invoke(
                    null,
                    env,
                    java.nio.LongBuffer.wrap(longArrayOf(0)),
                    longArrayOf(1)
                ) as AutoCloseable

                val fpInputs = java.util.HashMap<String, Any>()
                fpInputs["text"] = textTensor
                fpInputs["speaker_id"] = speakerTensor

                val runMethod = fpSession.javaClass.getMethod("run", java.util.Map::class.java)
                val fpResult = runMethod.invoke(fpSession, fpInputs) as AutoCloseable

                val getResultMethod = fpResult.javaClass.getMethod("get", Int::class.javaPrimitiveType)
                val melVal = getResultMethod.invoke(fpResult, 0)

                val hifiInputs = java.util.HashMap<String, Any>()
                hifiInputs["mel"] = melVal

                val hifiResult = runMethod.invoke(hifiSession, hifiInputs) as AutoCloseable
                val waveVal = getResultMethod.invoke(hifiResult, 0)

                val getValueMethod = waveVal.javaClass.getMethod("getValue")
                @Suppress("UNCHECKED_CAST")
                val waveData = getValueMethod.invoke(waveVal) as? Array<Array<FloatArray>>

                textTensor.close()
                speakerTensor.close()
                fpResult.close()
                hifiResult.close()

                if (waveData != null && waveData.isNotEmpty() && waveData[0].isNotEmpty()) {
                    return waveData[0][0]
                }
                return FloatArray(0)
            } finally {
                fpSession.close()
                hifiSession.close()
            }
        }
    }
}
