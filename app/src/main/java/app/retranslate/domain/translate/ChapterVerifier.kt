package app.retranslate.domain.translate

/**
 * The cheap, local quality checks (PLAN.md §5.2): no AI involved. They
 * run per chunk (a failed chunk is retried, never silently mangled —
 * §10 #12) and produce the chapter's 0–100 quality score shown in the
 * UI. Knowledge-Base term checks join in P5; the script/paragraph/length
 * checks here are the ones that catch the classic MTL failures:
 * untranslated leftovers and dropped paragraphs.
 */
object ChapterVerifier {

    /** Leftover-script tolerance: above this share, the chunk "failed". */
    const val LEFTOVER_FAIL_FRACTION = 0.05

    /** Absolute leftover characters below which noise is forgiven. */
    const val LEFTOVER_MIN_CHARS = 10

    /**
     * Problems with one translated chunk, as plain one-line reasons
     * (they surface via `AppError.VerificationFailed`). Empty = passed.
     */
    fun chunkIssues(
        sourceChunk: String,
        translatedChunk: String,
        sourceLang: String,
        targetLang: String
    ): List<String> {
        val issues = mutableListOf<String>()
        if (translatedChunk.isBlank()) {
            issues += "the translation came back empty"
            return issues
        }
        val srcParagraphs = TextChunker.countParagraphs(sourceChunk)
        val tgtParagraphs = TextChunker.countParagraphs(translatedChunk)
        if (srcParagraphs >= 3) {
            val drift = kotlin.math.abs(srcParagraphs - tgtParagraphs)
            if (drift > maxOf(1, srcParagraphs / 5)) {
                issues += "paragraphs changed from $srcParagraphs to $tgtParagraphs"
            }
        }
        val leftover = leftoverFraction(translatedChunk, sourceLang, targetLang)
        val leftoverChars = leftover * translatedChunk.length
        if (leftover > LEFTOVER_FAIL_FRACTION && leftoverChars > LEFTOVER_MIN_CHARS) {
            issues += "leftover ${scriptName(sourceLang)} text in the translation"
        }
        return issues
    }

    /**
     * §6.5 KB compliance for a finished chapter: the wrong renderings
     * of known names found in the translation (spacing variants of a
     * canonical form). A chapter with violations is FLAGGED via its
     * score and the book page's warning count — never silently
     * rewritten; fixing is the explicit "Re-apply names" action.
     */
    fun kbViolations(
        translatedText: String,
        entries: List<app.retranslate.data.db.KbEntryEntity>
    ): List<app.retranslate.domain.kb.KbViolation> =
        app.retranslate.domain.kb.KbCompliance.findViolations(translatedText, entries)

    /** Score penalty for KB violations: −10 per distinct name, capped. */
    fun kbPenalty(violations: List<app.retranslate.domain.kb.KbViolation>): Float =
        minOf(30f, 10f * violations.map { it.entryId }.distinct().size)

    /** 0–100 score for a finished chapter: 100 minus measured penalties. */
    fun qualityScore(
        sourceText: String,
        translatedText: String,
        sourceLang: String,
        targetLang: String
    ): Float {
        if (translatedText.isBlank() || sourceText.isBlank()) return 0f
        var score = 100f
        val leftover = leftoverFraction(translatedText, sourceLang, targetLang)
        score -= minOf(60f, (leftover * 300).toFloat())
        val srcP = TextChunker.countParagraphs(sourceText)
        val tgtP = TextChunker.countParagraphs(translatedText)
        if (srcP > 0) {
            val drift = kotlin.math.abs(srcP - tgtP).toFloat() / srcP
            score -= minOf(25f, drift * 50f)
        }
        val ratio = translatedText.length.toFloat() / sourceText.length
        if (ratio < 0.25f || ratio > 4f) score -= 15f
        return score.coerceIn(0f, 100f)
    }

    /**
     * v1.1.3 (§A3): a score for a chapter that has NO source text —
     * imported MTL chapters were never scored (null forever), which
     * left them invisible to the Rough/Okay/Good labels. Local
     * signals only: KB violations, plus a run-on deduction for the
     * wall-of-text sentences raw MTL produces. 100 minus penalties.
     */
    fun localQualityScore(
        translatedText: String,
        entries: List<app.retranslate.data.db.KbEntryEntity>
    ): Float {
        if (translatedText.isBlank()) return 0f
        var score = 100f
        score -= kbPenalty(kbViolations(translatedText, entries))
        // Run-on sentences: average words per sentence across the
        // chapter. Readable prose sits far below these marks; raw
        // MTL's comma-chained walls of text sail past them.
        val sentences = translatedText.split(SENTENCE_END)
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (sentences.isNotEmpty()) {
            val words = sentences.sumOf { sentence ->
                sentence.split(WHITESPACE).count { it.isNotBlank() }
            }
            val average = words.toFloat() / sentences.size
            if (average > 55f) score -= 30f else if (average > 40f) score -= 15f
        }
        return score.coerceIn(0f, 100f)
    }

    private val SENTENCE_END = Regex("[.!?…]+")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Share of characters in [translated] written in the SOURCE
     * language's script — only meaningful when source and target use
     * different scripts (a Korean→English leftover is Hangul; a
     * Korean→Korean "translation" is a different feature's problem).
     */
    fun leftoverFraction(translated: String, sourceLang: String, targetLang: String): Double {
        val ranges = scriptRanges(sourceLang) ?: return 0.0
        val targetRanges = scriptRanges(targetLang).orEmpty()
        if (translated.isEmpty()) return 0.0
        var letters = 0
        var leftover = 0
        for (c in translated) {
            if (c.isLetter()) {
                letters++
                // A character only counts as leftover if the target
                // script doesn't legitimately use it too (Han shared
                // by Chinese and Japanese must not false-positive).
                if (ranges.any { c in it } && targetRanges.none { c in it }) leftover++
            }
        }
        return if (letters == 0) 0.0 else leftover.toDouble() / letters
    }

    private fun scriptName(sourceLang: String): String = when (sourceLang) {
        "ko" -> "Korean"
        "ja" -> "Japanese"
        "zh-CN", "zh-TW", "zh" -> "Chinese"
        else -> "original-language"
    }

    private fun scriptRanges(lang: String): List<CharRange>? = when (lang) {
        "ko" -> listOf('\uAC00'..'\uD7A3', '\u1100'..'\u11FF')
        "ja" -> listOf('\u3040'..'\u30FF', '\u4E00'..'\u9FFF')
        "zh-CN", "zh-TW", "zh" -> listOf('\u4E00'..'\u9FFF', '\u3400'..'\u4DBF')
        "ru" -> listOf('\u0400'..'\u04FF')
        else -> null
    }
}
