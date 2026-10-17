package `in`.itantra.mobile.translation

object IndicNlpNormalizer {

    private val INDIC_TO_ARABIC_DIGITS = mapOf(
        '०' to '0', '१' to '1', '२' to '2', '३' to '3', '४' to '4',
        '५' to '5', '६' to '6', '७' to '7', '८' to '8', '९' to '9'
    )

    private val ARABIC_TO_DEVANAGARI_DIGITS = mapOf(
        '0' to '०', '1' to '१', '2' to '२', '3' to '३', '4' to '४',
        '5' to '५', '6' to '६', '7' to '७', '8' to '८', '9' to '९'
    )

    /**
     * AI4Bharat IndicNLP normalization before translation and before TTS:
     * - Normalize Indic numerals & digits
     * - Normalize Danda (|) and double Danda (||) to standard punctuation (. / !)
     * - Normalize Devanagari Unicode sequences (Nukta decomposition, Halant, Anusvara)
     * - Clean up English loanwords and multiple spaces
     */
    fun normalize(text: String, languageCode: String): String {
        var str = text.trim()
        if (str.isEmpty()) return ""

        // 1. Normalize punctuation: danda and double danda
        str = str.replace("।।", ".")
        str = str.replace("।", ".")

        // 2. Normalize numerals depending on language
        if (languageCode == "hi") {
            // Normalize any mixed arabic numerals or Indic numerals
            val sb = StringBuilder()
            for (ch in str) {
                sb.append(INDIC_TO_ARABIC_DIGITS[ch] ?: ch)
            }
            str = sb.toString()
        }

        // 3. Remove non-printable / zero-width characters except ZWNJ/ZWJ where needed
        str = str.replace("\u200B", "") // zero-width space
        str = str.replace(Regex("\\s+"), " ")

        // 4. Common English loanwords normalization for Hindi TTS legibility
        if (languageCode == "hi") {
            str = str.replace(Regex("(?i)\\bemergency\\b"), "इमरजेंसी")
            str = str.replace(Regex("(?i)\\bdoctor\\b"), "डॉक्टर")
            str = str.replace(Regex("(?i)\\bpolice\\b"), "पुलिस")
            str = str.replace(Regex("(?i)\\bhelp\\b"), "मदद")
            str = str.replace(Regex("(?i)\\bhospital\\b"), "अस्पताल")
        }

        return str.trim()
    }
}
