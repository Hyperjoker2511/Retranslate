package app.retranslate.domain.kb

import app.retranslate.data.db.KbCategory
import app.retranslate.data.db.KbEntryEntity

/** One place a translated text renders a KB name wrong (§6.5). */
data class KbViolation(
    val entryId: Long,
    val canonicalForm: String,
    /** The wrong rendering actually found in the text. */
    val variantFound: String,
    val occurrences: Int
)

/**
 * The local, free KB-compliance check (§6.5): finds renderings of a
 * canonical name that differ only in spacing/hyphenation — the
 * classic MTL slip ("Jin Woo" in the KB, "Jinwoo" or "Jin-Woo" in a
 * chapter). Pure text work, no AI. Tone rules carry no checkable
 * form and are skipped; single-word canonicals have no spacing
 * variants and can't slip this way.
 */
object KbCompliance {

    /** Surface forms that count as "the same name, spelled wrong". */
    fun variantsOf(canonicalForm: String): List<String> {
        val canonical = canonicalForm.trim()
        if (!canonical.contains(' ') && !canonical.contains('-')) return emptyList()
        val variants = linkedSetOf<String>()
        variants += canonical.replace(" ", "")
        variants += canonical.replace("-", "")
        variants += canonical.replace(" ", "-")
        variants += canonical.replace("-", " ")
        variants += canonical.replace(" ", "").replace("-", "")
        // §6.5's own example: KB "Jin Woo" vs chapter "Jinwoo" — when
        // MT glues the words it also drops the second word's capital.
        val words = canonical.split(' ', '-').filter { it.isNotEmpty() }
        if (words.size > 1) {
            variants += words.first() +
                words.drop(1).joinToString("") { it.lowercase() }
        }
        variants.remove(canonical)
        return variants.filter { it.isNotBlank() }
    }

    /** All violations in one translated text, one per (entry, variant). */
    fun findViolations(text: String, entries: List<KbEntryEntity>): List<KbViolation> {
        if (text.isBlank()) return emptyList()
        val violations = mutableListOf<KbViolation>()
        for (entry in entries) {
            if (entry.category == KbCategory.TONE_RULE.name) continue
            for (variant in variantsOf(entry.canonicalForm)) {
                val count = countOccurrences(text, variant)
                if (count > 0) {
                    violations += KbViolation(
                        entryId = entry.id,
                        canonicalForm = entry.canonicalForm,
                        variantFound = variant,
                        occurrences = count
                    )
                }
            }
        }
        return violations
    }

    /**
     * Whole-word, case-sensitive occurrence count. Case matters: the
     * spaceless form of a name ("Jinwoo") appearing lower-case inside
     * another word is not a name slip.
     */
    fun countOccurrences(text: String, needle: String): Int {
        if (needle.isBlank()) return 0
        var count = 0
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return count
            val beforeOk = at == 0 || !text[at - 1].isLetterOrDigit()
            val end = at + needle.length
            val afterOk = end >= text.length || !text[end].isLetterOrDigit()
            if (beforeOk && afterOk) count++
            from = at + 1
        }
    }

    /**
     * Replaces every whole-word occurrence of [variant] with
     * [replacement] — the "Re-apply names" write (§6.3). Returns the
     * new text and how many places changed.
     */
    fun replaceVariant(text: String, variant: String, replacement: String): Pair<String, Int> {
        if (variant.isBlank() || variant == replacement) return text to 0
        val builder = StringBuilder()
        var count = 0
        var from = 0
        while (true) {
            val at = text.indexOf(variant, from)
            if (at < 0) {
                builder.append(text, from, text.length)
                break
            }
            val beforeOk = at == 0 || !text[at - 1].isLetterOrDigit()
            val end = at + variant.length
            val afterOk = end >= text.length || !text[end].isLetterOrDigit()
            if (beforeOk && afterOk) {
                builder.append(text, from, at)
                builder.append(replacement)
                count++
                from = end
            } else {
                builder.append(text, from, at + 1)
                from = at + 1
            }
        }
        return builder.toString() to count
    }
}
