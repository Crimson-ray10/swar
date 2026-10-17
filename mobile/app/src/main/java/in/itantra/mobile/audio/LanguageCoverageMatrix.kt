package `in`.itantra.mobile.audio

data class LanguageExportStatus(
    val languageCode: String,
    val languageName: String,
    val inBaseIndicConformerCheckpoint: Boolean,
    val inExportedStreamingOnnx: Boolean,
    val statusNote: String
)

object LanguageCoverageMatrix {

    // All 22 official languages targeted by AI4Bharat IndicConformer
    val ALL_22_LANGUAGES = listOf(
        "as" to "Assamese", "bn" to "Bengali", "brx" to "Bodo", "doi" to "Dogri",
        "gu" to "Gujarati", "hi" to "Hindi", "kn" to "Kannada", "ks" to "Kashmiri",
        "kok" to "Konkani", "mai" to "Maithili", "ml" to "Malayalam", "mni" to "Manipuri",
        "mr" to "Marathi", "ne" to "Nepali", "or" to "Odia", "pa" to "Punjabi",
        "sa" to "Sanskrit", "sat" to "Santali", "sd" to "Sindhi", "ta" to "Tamil",
        "te" to "Telugu", "ur" to "Urdu"
    )

    fun checkCoverage(exportedOnnxLanguages: Set<String>): List<LanguageExportStatus> {
        val report = ArrayList<LanguageExportStatus>()

        for ((code, name) in ALL_22_LANGUAGES) {
            val inBase = true // Present in AI4Bharat IndicConformer base checkpoint
            val inExport = exportedOnnxLanguages.contains(code)

            val note = when {
                inExport -> "Ready: Included in streaming ONNX export"
                inBase -> "Conversion gap: Present in base IndicConformer checkpoint, NeMo->ONNX export script available"
                else -> "Missing from base"
            }

            report.add(
                LanguageExportStatus(
                    languageCode = code,
                    languageName = name,
                    inBaseIndicConformerCheckpoint = inBase,
                    inExportedStreamingOnnx = inExport,
                    statusNote = note
                )
            )
        }

        return report
    }
}
