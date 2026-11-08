package app.retranslate.domain.translate

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.data.db.KbEntryEntity
import app.retranslate.data.db.KbCategory
import app.retranslate.domain.kb.KbCompliance
import app.retranslate.domain.kb.KbEntryDraft
import app.retranslate.domain.kb.KbExtractionParser
import app.retranslate.domain.kb.KbInjection
import app.retranslate.providers.AiContext
import app.retranslate.providers.Languages
import app.retranslate.providers.Provider
import app.retranslate.providers.QualityMode

/**
 * §C's two visible choices for text that enters the app: the text is
 * in its ORIGINAL language (translate it) or it is already MTL —
 * machine-translated English (improve it). The user's option
 * decides; detection only pre-selects and explains (§C refinement).
 */
enum class ChapterTextKind(val label: String) {
    ORIGINAL("Original language"),
    MTL("MTL — improve it")
}

/**
 * The standalone chapter's mini-base (§C): the names/terms extracted
 * (or scanned) from THIS chapter before it is processed, so the one
 * chapter is consistent with itself — and so the flow can afterwards
 * ask the user's exact question: "Save this base for future
 * chapters" vs "Just this chapter".
 */
data class ChapterBase(
    val drafts: List<KbEntryDraft>,
    /** The same entries as entities (kbId 0) for prompt injection. */
    val entries: List<KbEntryEntity>,
    /** True when an AI extracted the base; false = the free name scan. */
    val fromAi: Boolean
) {
    val isEmpty: Boolean get() = entries.isEmpty()
}

/** The finished standalone chapter + the base that produced it. */
data class MakeBetterOutcome(
    val text: String,
    val viaNames: List<String>,
    val base: ChapterBase,
    /** Plain-words facts worth showing (e.g. "3 name spellings fixed"). */
    val notes: List<String>
)

/**
 * The standalone-chapter flow's engine (§C): build the chapter's
 * mini-base FIRST, then process it with the base riding along —
 *
 *  - ORIGINAL: the base becomes the translation's AI context
 *    (canonical renderings locked in), translated via
 *    [LargeTextTranslator] (Automatic chain, or the one engine the
 *    user picked, §I).
 *  - MTL: the base becomes the polish pass's locked list via
 *    [PolishCore]; with no AI key, the free name scan still fixes
 *    inconsistent spellings of the chapter's own names locally.
 *
 * Nothing is saved here — the souvenir book (when the user answers
 * "Save this base") is the caller's job, from [MakeBetterOutcome.base].
 */
class ChapterMakeBetter(
    private val largeText: LargeTextTranslator,
    private val polishAi: PolishAi?,
    private val pacer: RequestPacer? = null
) {

    /** Builds the mini-base without processing the chapter. */
    suspend fun buildBase(
        text: String,
        kind: ChapterTextKind,
        targetLang: String
    ): ChapterBase {
        val ai = polishAi
        if (ai != null && ai.isConfigured()) {
            val extracted = ai.complete(
                systemPrompt = AiPrompts.miniGlossarySystemPrompt(),
                userPrompt = AiPrompts.miniGlossaryUserPrompt(
                    text, Languages.nameOf(targetLang)
                ),
                targetLang = targetLang,
                quality = QualityMode.BALANCED
            )
            if (extracted is AppResult.Ok) {
                val parsed = KbExtractionParser.parse(extracted.value, "chapter-base")
                if (parsed is AppResult.Ok && parsed.value.isNotEmpty()) {
                    return ChapterBase(
                        drafts = parsed.value,
                        entries = parsed.value.map { it.toEntity() },
                        fromAi = true
                    )
                }
            }
            // Extraction failed or found nothing: the free scan is
            // the honest fallback (never a silent empty base).
        }
        val scanned = NameFrequencyScan.scan(text)
        return ChapterBase(
            drafts = scanned,
            entries = scanned.map { it.toEntity() },
            fromAi = false
        )
    }

    suspend fun makeBetter(
        text: String,
        kind: ChapterTextKind,
        sourceLang: String,
        targetLang: String,
        providerOverride: Provider? = null,
        onProgress: (doneChunks: Int, totalChunks: Int) -> Unit = { _, _ -> }
    ): AppResult<MakeBetterOutcome> {
        if (text.isBlank()) {
            return AppResult.Err(
                AppError.VerificationFailed("paste the chapter text first")
            )
        }
        val base = buildBase(text, kind, targetLang)
        val notes = mutableListOf<String>()
        if (base.fromAi && base.entries.isNotEmpty()) {
            notes += "Base built from this chapter: ${base.entries.size} names and terms."
        }
        return when (kind) {
            ChapterTextKind.ORIGINAL -> {
                val kbBlock = KbInjection.buildBlock(base.entries)
                val context = AiContext(
                    systemPrompt = AiPrompts.chapterSystemPrompt(
                        sourceLang, targetLang, genrePresetId = null,
                        kbBlock = kbBlock.ifBlank { null }
                    )
                )
                when (
                    val translated = largeText.translate(
                        text = text,
                        source = sourceLang,
                        target = targetLang,
                        onProgress = onProgress,
                        context = context,
                        providerOverride = providerOverride
                    )
                ) {
                    is AppResult.Err -> AppResult.Err(translated.error)
                    is AppResult.Ok -> AppResult.Ok(
                        MakeBetterOutcome(
                            text = translated.value.text,
                            viaNames = translated.value.viaNames,
                            base = base,
                            notes = notes
                        )
                    )
                }
            }
            ChapterTextKind.MTL -> {
                val ai = polishAi
                if (ai == null || !ai.isConfigured()) {
                    // No key: the free local pass — fix this chapter's
                    // own inconsistent name spellings, say plainly
                    // that a full AI improve needs a key (§G honesty).
                    val (fixed, replacements) = fixNameSpellings(text, base.entries)
                    if (replacements > 0) {
                        notes += "$replacements name spelling(s) made consistent."
                    }
                    notes += "Add an AI key under More → Providers & keys for a " +
                        "full improve — without one, only name spellings were fixed."
                    return AppResult.Ok(
                        MakeBetterOutcome(
                            text = fixed,
                            viaNames = listOf("Name check (on this device)"),
                            base = base,
                            notes = notes
                        )
                    )
                }
                val kbBlock = KbInjection.buildBlock(base.entries)
                val core = PolishCore(ai, pacer)
                when (
                    val polished = core.polishText(
                        text = text,
                        systemPrompt = AiPrompts.polishSystemPrompt(
                            targetLang, genrePresetId = null,
                            kbBlock = kbBlock.ifBlank { null }
                        ),
                        targetLang = targetLang,
                        quality = QualityMode.BALANCED,
                        lockedEntries = base.entries
                    )
                ) {
                    is AppResult.Ok -> when (val outcome = polished.value) {
                        is PolishCoreResult.Applied -> AppResult.Ok(
                            MakeBetterOutcome(
                                text = outcome.text,
                                viaNames = listOf(ai.displayName),
                                base = base,
                                notes = notes
                            )
                        )
                        is PolishCoreResult.Rejected -> AppResult.Err(
                            AppError.AiBadOutput(ai.displayName)
                        )
                    }
                    is AppResult.Err -> AppResult.Err(polished.error)
                }
            }
        }
    }

    companion object {
        /**
         * The free local improvement: replace every variant spelling
         * the compliance check finds with the canonical form.
         * Returns the fixed text and how many replacements were made.
         */
        fun fixNameSpellings(
            text: String,
            entries: List<KbEntryEntity>
        ): Pair<String, Int> {
            if (entries.isEmpty()) return text to 0
            var fixed = text
            var replacements = 0
            for (violation in KbCompliance.findViolations(text, entries)) {
                val before = fixed
                fixed = fixed.replace(violation.variantFound, violation.canonicalForm)
                if (fixed != before) replacements += violation.occurrences
            }
            return fixed to replacements
        }
    }
}

private fun KbEntryDraft.toEntity(): KbEntryEntity = KbEntryEntity(
    kbId = 0,
    category = category.name,
    sourceForm = sourceForm,
    canonicalForm = canonicalForm,
    gender = gender?.name,
    note = note,
    locked = true,
    frequency = frequency
)

/**
 * The free name scan (§C): recurring capitalised names in the text,
 * grouped by a spacing-insensitive key so "Jin Woo" and "Jinwoo"
 * count as one name — the most-used surface form becomes canonical.
 * Pure and deterministic; the MTL path's base when no AI key exists,
 * and the fallback when AI extraction finds nothing.
 */
object NameFrequencyScan {

    private val NAME_PATTERN =
        Regex("[A-Z][A-Za-z'\\u2019\\-]*(?: [A-Z][A-Za-z'\\u2019\\-]*){0,2}")

    fun scan(text: String, minOccurrences: Int = 3, maxEntries: Int = 40): List<KbEntryDraft> {
        // normalized key → (surface form → count)
        val groups = mutableMapOf<String, MutableMap<String, Int>>()
        for (match in NAME_PATTERN.findAll(text)) {
            val form = match.value.trim()
            if (form.length < 2) continue
            if (form in STOPWORDS) continue
            val key = form.lowercase().replace(Regex("[\\s\\-]"), "")
            val surfaces = groups.getOrPut(key) { mutableMapOf() }
            surfaces[form] = (surfaces[form] ?: 0) + 1
        }
        return groups.values
            .mapNotNull { surfaces ->
                val total = surfaces.values.sum()
                if (total < minOccurrences) return@mapNotNull null
                val canonical = surfaces.maxByOrNull { it.value }!!.key
                KbEntryDraft(
                    category = KbCategory.TERM,
                    sourceForm = null,
                    canonicalForm = canonical,
                    gender = null,
                    note = "Found by the name scan in this chapter.",
                    frequency = total
                )
            }
            .sortedByDescending { it.frequency }
            .take(maxEntries)
    }

    /** Sentence-start furniture, not names. */
    private val STOPWORDS = setOf(
        "The", "This", "That", "Chapter", "However", "Suddenly", "Meanwhile",
        "After", "Before", "When", "While", "Then", "Now", "But", "And",
        "His", "Her", "Their", "One", "Two", "First", "Second"
    )
}
