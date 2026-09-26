package `in`.itantra.offline.models

import `in`.itantra.offline.crypto.sha256
import `in`.itantra.offline.protocol.Language
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

data class ModelPack(val directory: File, val manifest: JSONObject) {
    val id: String get() = manifest.getString("id")
    val kind: String get() = manifest.getString("kind")
    val language: Language get() = Language.fromTag(manifest.getString("language"))
    val runtime: JSONObject get() = manifest.getJSONObject("runtime")
    fun file(key: String): File {
        val name = runtime.getString(key)
        val declared = manifest.getJSONArray("files")
        require((0 until declared.length()).any { declared.getJSONObject(it).getString("path") == name }) { "Undeclared model dependency: $name" }
        return ModelPacks.safeFile(directory, name).also { require(it.isFile) { "Missing $name" } }
    }
}

/** Imports a bounded, checksummed .ilp ZIP atomically. Checksums establish integrity, not publisher trust. */
class ModelPacks(private val root: File) {
    init { root.mkdirs() }
    fun installed(): List<ModelPack> = root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }
        .mapNotNull { dir -> runCatching { ModelPack(dir, JSONObject(File(dir, "manifest.json").readText())) }.getOrNull() }
    fun find(kind: String, language: Language): ModelPack? = installed().firstOrNull { it.kind == kind && it.language == language }
    fun import(input: InputStream): ModelPack {
        val stage = File(root, ".import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val names = mutableSetOf<String>()
            var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory) { "Pack entries must be regular files" }
                    require(names.size < 128 && names.add(entry.name)) { "Duplicate or excessive entries" }
                    val output = safeFile(stage, entry.name)
                    output.parentFile!!.mkdirs()
                    output.outputStream().use { target ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buffer); if (n < 0) break
                            total += n; require(total <= MAX_BYTES) { "Pack exceeds installed size limit" }
                            if (entry.name == "manifest.json") require(output.length() + n <= 65536) { "Manifest too large" }
                            target.write(buffer, 0, n)
                        }
                    }
                    zip.closeEntry()
                }
            }
            val manifest = JSONObject(File(stage, "manifest.json").readText())
            validate(stage, manifest, names)
            val destination = File(root, manifest.getString("id"))
            require(!destination.exists()) { "Pack version already installed; use a new versioned ID" }
            require(stage.renameTo(destination)) { "Cannot install pack atomically" }
            return ModelPack(destination, manifest)
        } finally { if (stage.exists()) stage.deleteRecursively() }
    }
    companion object {
        const val MAX_BYTES = 500_000_000L
        fun safeFile(root: File, path: String): File {
            require(path.isNotBlank() && !path.contains('\\') && !path.contains(':') && !path.startsWith('/'))
            require(path.split('/').all { it.isNotBlank() && it != "." && it != ".." }) { "Unsafe pack path" }
            val target = File(root, path).canonicalFile
            require(target.toPath().startsWith(root.canonicalFile.toPath()))
            return target
        }
        fun validate(root: File, manifest: JSONObject, extracted: Set<String>) {
            require(manifest.getInt("schema") == 1)
            require(Regex("[a-z0-9][a-z0-9.-]{0,79}").matches(manifest.getString("id")))
            Language.fromTag(manifest.getString("language"))
            require(manifest.getString("license").isNotBlank() && manifest.getString("source").isNotBlank())
            val files = manifest.getJSONArray("files")
            val declared = mutableSetOf("manifest.json")
            require(files.length() in 1..127)
            for (i in 0 until files.length()) {
                val entry = files.getJSONObject(i); val name = entry.getString("path")
                require(declared.add(name))
                val file = safeFile(root, name)
                require(file.isFile && file.length() == entry.getLong("bytes")) { "Size mismatch: $name" }
                val hash = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { stream -> val buffer = ByteArray(65536); while (true) { val n = stream.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } }
                require(hash.digest().joinToString("") { "%02x".format(it) } == entry.getString("sha256")) { "Checksum mismatch: $name" }
            }
            require(declared == extracted) { "Missing or undeclared pack files" }
            val pack = ModelPack(root, manifest)
            require(declared.contains("EXPORT.md")) { "Export configuration and provenance README required" }
            when (pack.kind) {
                "stt" -> {
                    require(manifest.getString("family") == "indicconformer")
                    require(pack.runtime.getBoolean("streaming")) { "Offline-only ASR exports are not accepted" }
                    require(pack.runtime.getString("architecture") == "nemo_ctc") { "Only a verified streaming NeMo CTC export contract is supported" }
                    pack.file("model"); pack.file("tokens")
                }
                "tts" -> {
                    require(manifest.getString("family") == "indic-tts")
                    require(pack.runtime.getString("tokenization") == "unicode-codepoints") { "This export requires an unsupported phonemizer" }
                    require(pack.runtime.getInt("sampleRate") in setOf(16000, 22050, 24000, 44100, 48000))
                    pack.file("fastpitch"); pack.file("hifigan"); pack.file("vocabulary")
                }
                else -> error("Unsupported pack runtime: ${pack.kind}")
            }
        }
    }
}
