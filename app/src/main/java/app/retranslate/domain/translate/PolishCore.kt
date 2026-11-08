package app.retranslate.domain.translate

import app.retranslate.core.error.AppResult
import app.retranslate.data.db.KbEntryEntity
import app.retranslate.providers.QualityMode

/** The pure improve outcome (§5.2): verified text, or the reason not. */
sealed interface PolishCoreResult {
    data class Applied(val text: String) : PolishCoreResult
    data class Rejected(val reason: String) : PolishCoreResult
}

/**
 * The pure heart of Improve (v1.1.3, plan §C12/D8): text + prompts in,
 * verified improved text out — no DAO, no book. Extracted from
 * [ChapterPolisher]'s DAO-bound shell so the Review action, the bulk
 * job and the standalone paste flow all share ONE improve.
 *
 * Chunking: the old polisher was single-shot with a 30,000-char cap —
 * long chapters simply could not be improved. The core walks the
 * text in paragraph blocks under [BLOCK_MAX_CHARS]; every block is
 * verified with the same [ChapterPolisher.polishIssues] rules
 * (paragraph shape + locked KB terms) before the next is attempted,
 * and one bad block rejects the whole improve — a half-improved
 * chapter is never served as whole.
 */
class PolishCore(
    private val ai: PolishAi,
    private val pacer: RequestPacer? = null
) {

    suspend fun polishText(
        text: String,
        systemPrompt: String,
        targetLang: String,
        quality: QualityMode,
        lockedEntries: List<KbEntryEntity>
    ): AppResult<PolishCoreResult> {
        val blocks = blocksOf(text)
        if (blocks.isEmpty()) {
            return AppResult.Ok(PolishCoreResult.Rejected("there is no text to improve"))
        }
        val polishedBlocks = ArrayList<String>(blocks.size)
        for ((index, block) in blocks.withIndex()) {
            if (index > 0) pacer?.pace()
            val polished = when (
                val result = ai.complete(
                    systemPrompt = systemPrompt,
                    userPrompt = AiPrompts.polishUserPrompt(block),
                    targetLang = targetLang,
                    quality = quality
                )
            ) {
                is AppResult.Err -> return result
                is AppResult.Ok -> result.value.trim()
            }
            val issues = ChapterPolisher.polishIssues(block, polished, lockedEntries)
            if (issues.isNotEmpty()) {
                return AppResult.Ok(
                    PolishCoreResult.Rejected(
                        if (blocks.size > 1) {
                            "part ${index + 1} of ${blocks.size}: ${issues.first()}"
                        } else {
                            issues.first()
                        }
                    )
                )
            }
            polishedBlocks += polished
        }
        return AppResult.Ok(PolishCoreResult.Applied(polishedBlocks.joinToString("\n\n")))
    }

    companion object {
        /**
         * One AI call's worth of text: the same scale as a §5.1
         * chapter chunk, so output bounds (and provider truncation
         * behaviour) match the translation pipeline's.
         */
        const val BLOCK_MAX_CHARS = 8_000

        /** Paragraph blocks under the cap; a lone oversized paragraph stands alone. */
        fun blocksOf(text: String): List<String> {
            val paragraphs = TextChunker.paragraphsOf(text).filter { it.isNotBlank() }
            if (paragraphs.isEmpty()) return emptyList()
            val blocks = mutableListOf<String>()
            val current = StringBuilder()
            fun flush() {
                if (current.isNotEmpty()) {
                    blocks += current.toString()
                    current.clear()
                }
            }
            for (paragraph in paragraphs) {
                if (current.isNotEmpty() &&
                    current.length + 2 + paragraph.length > BLOCK_MAX_CHARS
                ) {
                    flush()
                }
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(paragraph)
            }
            flush()
            return blocks
        }
    }
}
