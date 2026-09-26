package `in`.itantra.offline.speech

import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.*
import `in`.itantra.offline.models.ModelPacks
import `in`.itantra.offline.protocol.Language
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.Executors

/** Capture, STT, synthesis, and playback have separate workers and bounded queues. */
class AudioEngine(
    private val context: Context,
    private val packs: ModelPacks,
    private val scope: CoroutineScope,
    private val transcript: (Long, Language, TextChunk, Boolean) -> Unit,
    private val notice: (String) -> Unit,
    private val metric: (String, Long) -> Unit,
    private val captureFinished: () -> Unit
) : AutoCloseable {
    private val recognitionDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val synthesisDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val playbackDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private data class Speech(val language: Language, val text: String)
    private val text = Channel<Speech>(32)
    private val pcm = Channel<SynthesizedAudio>(3)
    private var capture: Job? = null
    private var recognizerJob: Job? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var frames: Channel<FloatArray>? = null
    @Volatile private var closed = false
    val capturing: Boolean get() = capture?.isActive == true
    private val synthesisJob = scope.launch(synthesisDispatcher) {
        var engine: FastPitchHiFiGan? = null
        var current: Language? = null
        try {
            for (speech in text) {
                try {
                    if (current != speech.language) {
                        engine?.close(); engine = null; current = null
                        val pack = packs.find("tts", speech.language) ?: error("${speech.language.tag} voice pack required; transcript remains visible")
                        engine = FastPitchHiFiGan(pack); current = speech.language
                    }
                    val started = SystemClock.elapsedRealtimeNanos()
                    val audio = engine!!.synthesize(speech.text)
                    metric("tts_synthesis_ns", SystemClock.elapsedRealtimeNanos() - started)
                    metric("tts_audio_duration_ns", audio.samples.size.toLong() * 1_000_000_000 / audio.sampleRate)
                    pcm.send(audio)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { notice("Synthesis unavailable: ${e.message}") }
            }
        } finally { engine?.close(); pcm.close() }
    }
    private val playbackJob = scope.launch(playbackDispatcher) {
        var rate = 0
        try {
            for (audio in pcm) {
                if (rate != audio.sampleRate || track == null) {
                    track?.stop(); track?.release()
                    val minBuffer = AudioTrack.getMinBufferSize(audio.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                    require(minBuffer > 0)
                    track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(audio.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minBuffer, audio.sampleRate / 5 * 4)).build()
                    rate = audio.sampleRate; track!!.play()
                }
                var offset = 0
                metric("tts_audio_enqueue_ns", SystemClock.elapsedRealtimeNanos())
                // This is enqueue time, not a fabricated hardware mouth-to-ear timestamp.
                while (offset < audio.samples.size && isActive) {
                    val count = track!!.write(audio.samples, offset, minOf(2048, audio.samples.size - offset), AudioTrack.WRITE_BLOCKING)
                    check(count > 0) { "AudioTrack write failed: $count" }; offset += count
                }
            }
        } finally { runCatching { track?.stop() }; track?.release(); track = null }
    }
    fun receive(language: Language, chunk: TextChunk) {
        if (!text.trySend(Speech(language, chunk.text)).isSuccess) notice("Speech playback queue full; transcript remains visible")
    }
    fun start(language: Language) {
        check(!closed)
        if (capturing || recognizerJob?.isActive == true) return
        val pack = packs.find("stt", language) ?: error("Install a verified ${language.tag} streaming STT pack before speaking")
        val queue = Channel<FloatArray>(16); frames = queue
        val manager = context.getSystemService(AudioManager::class.java)
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        if (!manager.isWiredHeadsetOn && !manager.isBluetoothScoOn) manager.isSpeakerphoneOn = true
        recognizerJob = scope.launch(recognitionDispatcher) {
            var recognizer: StreamingRecognizer? = null
            var vad: Vad? = null
            try {
                recognizer = StreamingRecognizer(pack)
                vad = Vad(context.assets, VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(model = "silero_vad.onnx"), sampleRate = 16000, numThreads = 1))
                val stable = StableText()
                val preRoll = ArrayDeque<FloatArray>()
                var speech = false; var silenceFrames = 0; var utteranceFrames = 0
                var stream = SystemClock.elapsedRealtimeNanos()
                fun finish() {
                    if (!speech) return
                    val result = recognizer!!.finish()
                    if (result.isNotBlank()) transcript(stream, language, TextChunk(0, result), true)
                    stable.reset(); speech = false; silenceFrames = 0; utteranceFrames = 0
                    stream = SystemClock.elapsedRealtimeNanos()
                }
                for (samples in queue) {
                    val probability = vad.compute(samples)
                    if (probability >= 0.5f) {
                        if (!speech) {
                            speech = true; metric("mic_speech_detected_ns", SystemClock.elapsedRealtimeNanos())
                            preRoll.forEach { recognizer.accept(it) }; preRoll.clear()
                        }
                        silenceFrames = 0; utteranceFrames++
                        recognizer.accept(samples)?.let { hypothesis -> stable.update(hypothesis)?.let { chunk ->
                            metric("stt_stable_ns", SystemClock.elapsedRealtimeNanos()); transcript(stream, language, chunk, false)
                        } }
                        if (utteranceFrames >= 938) finish() // Bound utterances to about 30 seconds.
                    } else {
                        if (speech && ++silenceFrames >= 16) finish()
                        preRoll.addLast(samples); while (preRoll.size > 6) preRoll.removeFirst()
                    }
                }
                finish()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice("Offline capture unavailable: ${e.message}"); stop() }
            finally { recognizer?.close(); vad?.release(); captureFinished() }
        }
        capture = scope.launch(Dispatchers.IO) {
            var aec: AcousticEchoCanceler? = null; var ns: NoiseSuppressor? = null
            var microphone: AudioRecord? = null
            try {
                if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    error("Microphone permission is required")
                }
                val size = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                require(size > 0)
                microphone = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(size, 8192))
                check(microphone.state == AudioRecord.STATE_INITIALIZED)
                record = microphone
                if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(microphone.audioSessionId)?.apply { enabled = true }
                if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(microphone.audioSessionId)?.apply { enabled = true }
                if (aec?.enabled != true) notice("Hardware echo cancellation unavailable; headset recommended for two-way conversation")
                microphone.startRecording()
                val buffer = ShortArray(512)
                while (isActive) {
                    var filled = 0
                    while (filled < buffer.size && isActive) {
                        val n = microphone.read(buffer, filled, buffer.size - filled, AudioRecord.READ_BLOCKING)
                        check(n > 0) { "Microphone read failed: $n" }; filled += n
                    }
                    if (!isActive) break
                    check(queue.trySend(FloatArray(512) { buffer[it] / 32768f }).isSuccess) { "STT cannot keep up with microphone; capture stopped to avoid corrupted speech" }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice("Microphone unavailable: ${e.message}") }
            finally { queue.close(); runCatching { microphone?.stop() }; aec?.release(); ns?.release(); microphone?.release(); record = null }
        }
    }
    /** Stops only outgoing capture; incoming synthesis and playback continue. */
    fun stop() { capture?.cancel(); runCatching { record?.stop() }; frames?.close() }
    override fun close() {
        closed = true; stop(); text.close(); synthesisJob.cancel(); playbackJob.cancel(); recognizerJob?.cancel()
        runCatching { track?.pause(); track?.flush() }
        recognitionDispatcher.close(); synthesisDispatcher.close(); playbackDispatcher.close()
        context.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
    }
}
