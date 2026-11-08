package app.retranslate.domain.books

import app.retranslate.providers.Languages

/**
 * Normalises a language code from the wild (EPUB metadata, user input)
 * to the app's canonical list (PLAN.md §7.1 boundary validation): exact
 * match → base-language match ("en-US" → "en", "zh" → "zh-CN") → legacy
 * ISO aliases → null (caller asks the user; never a dead end).
 */
object LanguageCodes {

    private val LEGACY_ALIASES = mapOf(
        "iw" to "he", "in" to "id", "ji" to "yi",
        "zh-hans" to "zh-CN", "zh-hant" to "zh-TW", "cmn" to "zh-CN"
    )

    fun normalise(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().replace('_', '-')
        // Canonicalise case shape: language lower, region/script title/upper.
        val parts = cleaned.split('-')
        val rebuilt = parts.mapIndexed { i, part ->
            when {
                i == 0 -> part.lowercase()
                part.length == 4 -> part.lowercase().replaceFirstChar { it.uppercase() }
                else -> part.uppercase()
            }
        }.joinToString("-")

        if (Languages.isKnown(rebuilt)) return rebuilt
        val lower = rebuilt.lowercase()
        LEGACY_ALIASES[lower]?.let { return it }
        val base = parts[0].lowercase()
        if (Languages.isKnown(base)) return base
        LEGACY_ALIASES[base]?.let { return it }
        // First canonical code with this base (Languages.ALL orders zh-CN first).
        return Languages.ALL.firstOrNull { it.code.startsWith("$base-") }?.code
    }
}

/**
 * Script-based language detection over a text sample (PLAN.md §7.1).
 * Deliberately conservative: returns a canonical code only when the
 * script evidence is decisive — otherwise null, and the review screen
 * asks the user (Korean / Chinese / English big buttons + full list).
 *
 * Detects the languages this app is for: Korean, Japanese, Chinese
 * (Simplified/Traditional), English, Russian. Latin-script text that
 * isn't recognisably English returns null rather than guessing.
 */
object LanguageDetector {

    private const val MIN_LETTERS = 40

    fun detect(sample: String): String? {
        var hangul = 0
        var kana = 0
        var han = 0
        var cyrillic = 0
        var latin = 0
        var simplifiedMarks = 0
        var traditionalMarks = 0

        val text = if (sample.length > 60_000) sample.substring(0, 60_000) else sample
        for (ch in text) {
            when (ch) {
                in '\uAC00'..'\uD7A3', in '\u1100'..'\u11FF', in '\u3130'..'\u318F' -> hangul++
                in '\u3040'..'\u30FF' -> kana++
                in '\u4E00'..'\u9FFF', in '\u3400'..'\u4DBF' -> {
                    han++
                    if (ch in SIMPLIFIED_ONLY) simplifiedMarks++
                    if (ch in TRADITIONAL_ONLY) traditionalMarks++
                }
                in '\u0400'..'\u04FF' -> cyrillic++
                in 'a'..'z', in 'A'..'Z', in '\u00C0'..'\u024F' -> latin++
            }
        }
        val letters = hangul + kana + han + cyrillic + latin
        if (letters < MIN_LETTERS) return null

        // Kana is decisive for Japanese even amid heavy kanji.
        if (kana >= 10 && kana * 20 >= letters) return "ja"
        if (hangul > 0 && hangul * 5 >= letters && hangul >= kana * 3) return "ko"
        if (han > 0 && (han + kana) * 5 >= letters * 3) {
            return if (traditionalMarks > simplifiedMarks * 2) "zh-TW" else "zh-CN"
        }
        if (cyrillic * 5 >= letters * 3) return "ru"
        if (latin * 10 >= letters * 7) {
            return if (looksEnglish(text)) "en" else null
        }
        return null
    }

    private val ENGLISH_MARKERS = listOf(
        " the ", " and ", " was ", " he ", " she ", " it was ", " of the ",
        " to the ", "chapter", " said ", " with ", " had ", " not ", " but "
    )

    private fun looksEnglish(text: String): Boolean {
        val padded = " " + text.lowercase().replace(Regex("\\s+"), " ") + " "
        return ENGLISH_MARKERS.count { it in padded } >= 3
    }

    // Characters that exist in common usage on only one side of the
    // simplified/traditional divide — the zh-CN vs zh-TW discriminator.
    private val SIMPLIFIED_ONLY: Set<Char> =
        "这么为国说时来对么学点还进远运近边连达过".toSet()
    private val TRADITIONAL_ONLY: Set<Char> =
        "這麼為國說時來對麼學點還進遠運近邊連達過".toSet()
}
