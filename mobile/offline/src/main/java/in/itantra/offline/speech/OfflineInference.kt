package `in`.itantra.offline.speech

import ai.onnxruntime.*
import com.k2fsa.sherpa.onnx.*
import `in`.itantra.offline.models.ModelPack
import org.json.JSONObject
import java.nio.LongBuffer
import java.text.Normalizer

/** Loads only the explicitly declared streaming NeMo CTC export, never platform speech services. */
class StreamingRecognizer(pack: ModelPack) : AutoCloseable {
    private val recognizer: OnlineRecognizer
    private var stream: OnlineStream
    init {
        require(pack.kind == "stt" && pack.runtime.getBoolean("streaming"))
        // NeMo's offline graph has a different ABI. Reject it in managed code before sherpa
        // reads mandatory cache metadata in native code (some native failures terminate a process).
        OrtSession.SessionOptions().use { options ->
            OrtEnvironment.getEnvironment().createSession(pack.file("model").absolutePath, options).use { model ->
                require(model.inputNames.size == 5 && model.outputNames.size == 5) { "Expected cache-aware NeMo CTC input/output ABI" }
                val metadata = model.metadata.customMetadata
                val fields = listOf("window_size", "chunk_shift", "subsampling_factor", "vocab_size",
                    "cache_last_channel_dim1", "cache_last_channel_dim2", "cache_last_channel_dim3",
                    "cache_last_time_dim1", "cache_last_time_dim2", "cache_last_time_dim3")
                fields.forEach { field -> require((metadata[field]?.toIntOrNull() ?: 0) in 1..1_000_000) { "Missing or invalid sherpa streaming metadata: $field" } }
                val cacheNames = listOf("cache_last_channel", "cache_last_time", "cache_last_channel_len")
                require(model.inputNames.toList().drop(2) == cacheNames) { "Streaming state input order mismatch" }
                val tokenIds = pack.file("tokens").readLines().filter { it.isNotBlank() }.map { it.substringAfterLast(' ').toInt() }
                require(tokenIds.toSet() == (0 until metadata.getValue("vocab_size").toInt()).toSet()) { "Token vocabulary does not match model" }
            }
        }
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = pack.runtime.optInt("featureDim", 80)),
            modelConfig = OnlineModelConfig(
                neMoCtc = OnlineNeMoCtcModelConfig(model = pack.file("model").absolutePath),
                tokens = pack.file("tokens").absolutePath,
                numThreads = 2, provider = "cpu", modelType = "nemo_ctc"
            ), enableEndpoint = false
        )
        recognizer = OnlineRecognizer(config = config)
        stream = recognizer.createStream()
    }
    fun accept(samples: FloatArray): String? {
        stream.acceptWaveform(samples, 16000)
        var decoded = false
        while (recognizer.isReady(stream)) { recognizer.decode(stream); decoded = true }
        return if (decoded) recognizer.getResult(stream).text else null
    }
    fun finish(): String {
        stream.inputFinished()
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        val text = recognizer.getResult(stream).text
        stream.release(); stream = recognizer.createStream()
        return text
    }
    override fun close() { stream.release(); recognizer.release() }
}

data class SynthesizedAudio(val samples: FloatArray, val sampleRate: Int)

/** Two native ONNX sessions; tensor names/layouts come from the export's manifest, not guesses. */
class FastPitchHiFiGan(private val pack: ModelPack) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2); setInterOpNumThreads(1) }
    private val fastpitch = env.createSession(pack.file("fastpitch").absolutePath, options)
    private val hifigan: OrtSession
    private val vocab: Map<Int, Long>
    init {
        try {
            hifigan = env.createSession(pack.file("hifigan").absolutePath, options)
            val json = JSONObject(pack.file("vocabulary").readText())
            vocab = json.keys().asSequence().associate { it.toInt() to json.getLong(it) }
        } catch (e: Exception) { fastpitch.close(); options.close(); throw e }
    }
    fun synthesize(text: String): SynthesizedAudio {
        val config = pack.runtime
        val ids = Normalizer.normalize(text, Normalizer.Form.NFC).codePoints().toArray().map { code ->
            vocab[code] ?: error("Voice vocabulary does not cover U+${code.toString(16)}; transcript remains visible")
        }.toMutableList()
        if (config.has("bos")) ids.add(0, config.getLong("bos"))
        if (config.has("eos")) ids.add(config.getLong("eos"))
        require(ids.isNotEmpty() && ids.size <= 2048)
        val inputs = mutableMapOf<String, OnnxTensor>()
        try {
            inputs[config.getString("tokensInput")] = OnnxTensor.createTensor(env, LongBuffer.wrap(ids.toLongArray()), longArrayOf(1, ids.size.toLong()))
            if (config.has("lengthInput")) inputs[config.getString("lengthInput")] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(ids.size.toLong())), longArrayOf(1))
            // Speaker and pace are baked into the export; never invent optional input defaults.
            require(fastpitch.inputNames == inputs.keys) { "FastPitch export input contract mismatch" }
            fastpitch.run(inputs).use { melResult ->
                val mel = melResult.get(config.getString("melOutput")).orElseThrow() as OnnxTensor
                require(hifigan.inputNames == setOf(config.getString("melInput")))
                hifigan.run(mapOf(config.getString("melInput") to mel)).use { waveResult ->
                    val wave = waveResult.get(config.getString("waveOutput")).orElseThrow() as OnnxTensor
                    val buffer = wave.floatBuffer
                    require(buffer.remaining() in 1..4_800_000) { "Unbounded synthesis output" }
                    val samples = FloatArray(buffer.remaining()); buffer.get(samples)
                    require(samples.all { it.isFinite() })
                    return SynthesizedAudio(samples, config.getInt("sampleRate"))
                }
            }
        } finally { inputs.values.forEach(OnnxTensor::close) }
    }
    override fun close() { fastpitch.close(); hifigan.close(); options.close() }
}
