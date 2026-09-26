package `in`.itantra.offline

import `in`.itantra.offline.models.ModelPacks
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelPackTest {
    /** Bytes exercise container integrity only, never inference or claimed model quality. */
    private fun archive(tamper: Boolean = false, extra: String? = null, streaming: Boolean = true): ByteArray {
        val files = mapOf("encoder.onnx" to byteArrayOf(1, 2, 3), "tokens.txt" to "a 0".toByteArray(), "EXPORT.md" to "Test fixture; not a model".toByteArray())
        val manifest = JSONObject().put("schema", 1).put("id", "test-hi-v1").put("kind", "stt").put("language", "hi")
            .put("family", "indicconformer").put("license", "test-only").put("source", "test fixture")
            .put("runtime", JSONObject().put("streaming", streaming).put("architecture", "nemo_ctc").put("model", "encoder.onnx").put("tokens", "tokens.txt"))
            .put("files", JSONArray(files.map { (path, bytes) -> JSONObject().put("path", path).put("bytes", bytes.size)
                .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }) }))
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, body: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(body); zip.closeEntry() }
            entry("manifest.json", manifest.toString().toByteArray())
            files.forEach { (name, body) -> entry(name, if (tamper && name == "encoder.onnx") byteArrayOf(9, 9, 9) else body) }
            if (extra != null) entry(extra, byteArrayOf(1))
        }
        return output.toByteArray()
    }
    @Test fun validatedPackInstallsAtomicallyAndDuplicateDoesNotReplaceIt() {
        val root = Files.createTempDirectory("packs").toFile()
        try {
            val packs = ModelPacks(root)
            assertEquals("test-hi-v1", packs.import(ByteArrayInputStream(archive())).id)
            assertEquals(1, packs.installed().size)
            assertThrows(Exception::class.java) { packs.import(ByteArrayInputStream(archive())) }
            assertEquals(1, packs.installed().size)
            assertEquals(1, root.listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }
    @Test fun corruptionTraversalUndeclaredFilesAndOfflineExportsFailClosed() {
        val root = Files.createTempDirectory("packs").toFile()
        try {
            val packs = ModelPacks(root)
            for (bytes in listOf(archive(tamper = true), archive(extra = "../escape"), archive(extra = "undeclared"), archive(streaming = false))) {
                assertThrows(Exception::class.java) { packs.import(ByteArrayInputStream(bytes)) }
                assertTrue(packs.installed().isEmpty()); assertTrue(root.listFiles()!!.isEmpty())
            }
        } finally { root.deleteRecursively() }
    }
}
