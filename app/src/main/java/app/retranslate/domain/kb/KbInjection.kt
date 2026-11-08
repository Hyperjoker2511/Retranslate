package app.retranslate.domain.kb

import app.retranslate.data.db.KbCategory
import app.retranslate.data.db.KbEntryEntity
import app.retranslate.data.db.KbGender

/**
 * Serialises a Knowledge Base into the compact block injected into
 * every AI chapter call (§6.4):
 *  - one line per entry, locked entries first, then by frequency;
 *  - tone rules ride at the top, summarised to at most
 *    [MAX_TONE_RULE_LINES] lines;
 *  - the whole block is capped at [MAX_BLOCK_CHARS] chars — when a
 *    KB outgrows it, the lowest-frequency unlocked entries are
 *    dropped into a counts summary ("…and 40 more minor entries")
 *    rather than truncating an entry mid-line.
 * The cap is a named constant with a test (§6.4).
 */
object KbInjection {

    const val MAX_BLOCK_CHARS = 6_000
    const val MAX_TONE_RULE_LINES = 5

    fun buildBlock(entries: List<KbEntryEntity>): String {
        if (entries.isEmpty()) return ""
        val sorted = entries.sortedWith(
            compareByDescending<KbEntryEntity> { it.locked }
                .thenByDescending { it.frequency }
                .thenBy { it.canonicalForm.lowercase() }
        )
        val toneRules = sorted.filter { it.category == KbCategory.TONE_RULE.name }
        val rest = sorted.filter { it.category != KbCategory.TONE_RULE.name }

        val lines = mutableListOf<String>()
        toneRules.take(MAX_TONE_RULE_LINES).forEach { lines += "Rule: ${it.canonicalForm}" }
        if (toneRules.size > MAX_TONE_RULE_LINES) {
            lines += "Rule: (and ${toneRules.size - MAX_TONE_RULE_LINES} more tone rules)"
        }

        var used = lines.sumOf { it.length + 1 }
        var included = 0
        for (entry in rest) {
            val line = lineFor(entry)
            // Always keep room for the summary line if anything drops.
            val reserve = if (included < rest.size) SUMMARY_RESERVE else 0
            if (used + line.length + 1 > MAX_BLOCK_CHARS - reserve && included > 0) break
            lines += line
            used += line.length + 1
            included++
        }
        val dropped = rest.size - included
        if (dropped > 0) {
            lines += "…and $dropped more minor entries — keep names consistent with the samples."
        }
        return lines.joinToString("\n").take(MAX_BLOCK_CHARS)
    }

    /** `Name (male) — original: …; note` (§6.4's line shape). */
    fun lineFor(entry: KbEntryEntity): String = buildString {
        append(entry.canonicalForm)
        if (entry.category == KbCategory.CHARACTER.name) {
            when (app.retranslate.data.db.DbEnums.kbGender(entry.gender)) {
                KbGender.MALE -> append(" (male)")
                KbGender.FEMALE -> append(" (female)")
                else -> Unit
            }
        }
        entry.sourceForm?.takeIf { it.isNotBlank() }?.let { append(" — original: $it") }
        entry.note?.takeIf { it.isNotBlank() }?.let { append("; $it") }
        if (entry.locked) append(" [locked]")
    }

    private const val SUMMARY_RESERVE = 80
}
