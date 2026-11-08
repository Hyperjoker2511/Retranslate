package app.retranslate.domain.translate

import app.retranslate.domain.books.GenrePreset
import app.retranslate.providers.Languages

/**
 * Every AI prompt template in one file (PLAN.md §5.4 — "prompts are
 * code, reviewed like code"). Chapter translation, Context-Learning
 * extraction (§6.2) and the extraction repair retry all build their
 * text here, and the size caps they promise are named constants with
 * tests.
 */
object AiPrompts {

    /** §6.2: study input cap — earliest sample chapters win. */
    const val STUDY_INPUT_MAX_CHARS = 60_000

    /**
     * The chapter system prompt (§5.4): fixed instruction block +
     * genre preset hint + the §6.4 Knowledge Base block when the book
     * has one + the output-only contract. Also the fallback Gemini
     * used before P5 assembled one itself, so it must state the
     * language pair explicitly.
     */
    fun chapterSystemPrompt(
        sourceLang: String,
        targetLang: String,
        genrePresetId: String?,
        kbBlock: String?
    ): String = buildString {
        append("You are an expert literary translator. Translate the user's text from ")
        append(Languages.nameOf(sourceLang)).append(" to ").append(Languages.nameOf(targetLang))
        append(", preserving meaning, tone, paragraph breaks and formatting exactly.")
        val hint = GenrePreset.fromId(genrePresetId)?.promptHint.orEmpty()
        if (hint.isNotBlank()) {
            append("\n\n").append(hint)
        }
        if (!kbBlock.isNullOrBlank()) {
            append("\n\nNovel Knowledge Base — use EXACTLY these renderings for names ")
            append("and terms, and write in the style the rules describe, so the ")
            append("chapter reads like the book's good chapters, not like literal ")
            append("machine translation. The Knowledge Base overrides your own choices:\n")
            append(kbBlock)
        }
        append("\n\nOutput ONLY the translation — no explanations, no quotes, no notes.")
    }

    /** The extraction system prompt (§6.2): strict JSON or nothing. */
    fun studySystemPrompt(targetLangName: String): String =
        "You are a meticulous novel-continuity analyst. You read sample chapters " +
            "of a translated web novel and extract its Knowledge Base: the exact " +
            "names and terms every future chapter must keep using, AND the writing " +
            "style every future chapter must read like — how sentences flow, how " +
            "dialogue sounds, how formal the narration is. You answer with " +
            "ONE JSON object and nothing else — no markdown fences, no commentary."

    /**
     * The extraction user prompt. [hasOriginalSamples] switches on the
     * paired mode (§6.2): when the user supplied both the original and
     * the translated samples, entries must pair source ↔ canonical.
     * Canonical renderings are always in [targetLangName] (the book's
     * target language; the user's samples are the gold reference).
     */
    fun studyUserPrompt(
        studyText: String,
        targetLangName: String,
        hasOriginalSamples: Boolean
    ): String = buildString {
        append("Extract the Knowledge Base from the sample chapters below.\n\n")
        append("Return JSON of exactly this shape:\n")
        append(
            "{\"entries\":[{\"category\":\"CHARACTER\",\"sourceForm\":\"… or null\"," +
                "\"canonicalForm\":\"…\",\"gender\":\"MALE|FEMALE|UNKNOWN or null\"," +
                "\"note\":\"short evidence or null\",\"frequency\":3}]}\n\n"
        )
        append("Categories: CHARACTER, LOCATION, ORGANISATION, TECHNIQUE, ITEM, TERM, ")
        append("TONE_RULE.\n")
        append("- canonicalForm is the exact ").append(targetLangName)
        append(" rendering used in the samples.\n")
        append("- For CHARACTER entries, set gender from the pronouns used in the ")
        append("text and put the pronoun evidence in note (e.g. \"referred to as 'she'\").\n")
        append("- TONE_RULE entries: canonicalForm is the rule itself, in one short ")
        append("sentence (address forms, honorifics, system-window style).\n")
        append("- ALSO extract STYLE rules as TONE_RULE entries (up to 6): how the good ")
        append("translation WRITES, so later chapters read like these samples instead of ")
        append("literal machine translation. Describe concretely: sentence flow (short ")
        append("punched sentences vs flowing ones), dialogue style (contractions, ")
        append("interruptions, speech tags), formality/register of the narration, ")
        append("paragraph rhythm (how often paragraphs break), how inner thoughts and ")
        append("system windows are rendered, and any words/phrasings the translation ")
        append("favours or avoids. Each style rule must be an instruction a translator ")
        append("can follow, e.g. \"Narration is plain and direct; short sentences; ")
        append("dialogue uses contractions and sounds spoken, not written\".\n")
        append("- frequency = in roughly how many sample chapters the entry appears.\n")
        if (hasOriginalSamples) {
            append("- The samples include BOTH the original text and its translation. ")
            append("Pair them: sourceForm is the name/term in the original language, ")
            append("canonicalForm is how the translation renders it.\n")
        } else {
            append("- sourceForm is null unless the original-language form is known ")
            append("from the text itself.\n")
        }
        append("- Include every recurring character, place, organisation, technique, ")
        append("item and special term — completeness matters more than brevity.\n\n")
        append("SAMPLE CHAPTERS:\n")
        append(studyText)
    }

    /**
     * The one repair retry (§6.2): the model is shown its own broken
     * answer and asked for the same JSON, valid this time. If this also
     * fails, study writes nothing.
     */
    fun studyRepairPrompt(brokenOutput: String, targetLangName: String): String = buildString {
        append("Your previous answer (below) was not valid JSON for the Knowledge Base ")
        append("extraction. Answer again with ONLY the corrected JSON object — the ")
        append("same shape: {\"entries\":[{\"category\":…,\"sourceForm\":…,")
        append("\"canonicalForm\":…,\"gender\":…,\"note\":…,\"frequency\":…}]} — ")
        append("canonical forms in ").append(targetLangName)
        append(", no markdown fences, no commentary.\n\nYOUR PREVIOUS ANSWER:\n")
        append(brokenOutput.take(MAX_REPAIR_ECHO_CHARS))
    }

    /**
     * The polish system prompt (§5.2, Model B — the editor): grammar,
     * tone and readability ONLY. Names and terms are untouchable — the
     * Knowledge Base block rides along exactly as in translation, and
     * the post-polish verifier (§5.2) rejects any polish that alters a
     * locked KB term, so this prompt states the rule the verifier
     * enforces.
     */
    fun polishSystemPrompt(
        targetLang: String,
        genrePresetId: String?,
        kbBlock: String?
    ): String = buildString {
        append("You are an expert literary editor. Polish the user's ")
        append(Languages.nameOf(targetLang))
        append(" translation of a novel chapter: fix grammar, awkward phrasing and ")
        append("unnatural machine-translation word order; make sentences flow the ")
        append("way a skilled human translator writes, so the chapter is genuinely ")
        append("easy to read and understand. Keep the meaning, every fact, the ")
        append("paragraph breaks and the formatting exactly as they are.")
        val hint = GenrePreset.fromId(genrePresetId)?.promptHint.orEmpty()
        if (hint.isNotBlank()) {
            append("\n\n").append(hint)
        }
        if (!kbBlock.isNullOrBlank()) {
            append("\n\nNovel Knowledge Base — names and terms from this list are ")
            append("LOCKED: never change their spelling, spacing or capitalisation, ")
            append("and keep the style rules in force:\n")
            append(kbBlock)
        }
        append("\n\nDo not add, drop or merge paragraphs. Do not translate anything ")
        append("that is not already translated. Output ONLY the polished chapter — ")
        append("no explanations, no quotes, no notes.")
    }

    /**
     * The polish user prompt (§5.2): the chapter's current translation,
     * whole — polish is a single-chapter, on-demand pass, so no
     * chunking; chapters beyond [POLISH_INPUT_MAX_CHARS] are refused
     * by the caller rather than truncated mid-text.
     */
    fun polishUserPrompt(translatedChapter: String): String =
        "Polish this chapter:\n\n" + translatedChapter

    /**
     * v1.1.3 (§C): the standalone chapter's MINI-BASE extraction — the
     * same strict-JSON contract as the book study, scoped to ONE
     * chapter, so a lone pasted chapter gets a name/term base before
     * it is translated or improved (the base the flow then offers to
     * save for future chapters).
     */
    fun miniGlossarySystemPrompt(): String =
        "You are a meticulous novel-continuity analyst. You read ONE chapter " +
            "and extract the exact names and terms it uses, so the chapter " +
            "itself stays consistent. You answer with ONE JSON object and " +
            "nothing else — no markdown fences, no commentary."

    fun miniGlossaryUserPrompt(chapterText: String, targetLangName: String): String =
        buildString {
            append("Extract the names and terms from the chapter below. ")
            append("Canonical forms must be in ").append(targetLangName).append(".\n\n")
            append("Return JSON of exactly this shape:\n")
            append("{\"entries\":[{\"category\":\"CHARACTER\",\"sourceForm\":\"… or null\",")
            append("\"canonicalForm\":\"…\",\"gender\":\"MALE|FEMALE|UNKNOWN or null\",")
            append("\"note\":\"short evidence or null\",\"frequency\":3}]}\n\n")
            append("Categories: CHARACTER, LOCATION, ORGANISATION, TECHNIQUE, ITEM, ")
            append("TERM, TONE_RULE.\n")
            append("Rules: include every recurring character name, place, technique, ")
            append("item, organization and special term; canonical = the spelling the ")
            append("chapter itself uses most; frequency = how often it appears.\n\n")
            append("CHAPTER:\n")
            append(chapterText.take(MINI_GLOSSARY_MAX_CHARS))
        }

    /** The mini-base reads at most this much of the chapter. */
    const val MINI_GLOSSARY_MAX_CHARS = 20_000

    /** §5.2: one polish call takes one chapter; longer chapters are refused, never cut. */
    const val POLISH_INPUT_MAX_CHARS = 30_000

    /**
     * The merge system prompt (§5.6, AI Compare & Merge — the judge):
     * one AI compares several translations of the same source and
     * returns ONE merged best result. The output-only contract is the
     * enforcement point — the caller sanity-checks the reply with the
     * §5.2 verifier basics and discards a merge that fails them.
     */
    fun mergeSystemPrompt(): String =
        "You are an expert translation editor. You are given a source text and " +
            "several independent translations of it into the same language. " +
            "Compare them for accuracy, correct names and terms, grammar and " +
            "naturalness. Take the strongest rendering as the base and repair " +
            "its weak spans from the others. Output ONLY the single merged " +
            "translation — no commentary, no candidate labels, no quotes, " +
            "no notes."

    /**
     * The merge user prompt (§5.6): the source, then the candidates
     * numbered with their provider names (the judge may weigh a
     * provider's strengths, but the OUTPUT must never carry labels).
     */
    fun mergeUserPrompt(
        sourceText: String,
        sourceLang: String,
        targetLang: String,
        candidates: List<MergeCandidateText>
    ): String = buildString {
        append("Source (").append(Languages.nameOf(sourceLang)).append("):\n")
        append(sourceText)
        append("\n\nTranslations into ").append(Languages.nameOf(targetLang)).append(":\n")
        candidates.forEachIndexed { index, candidate ->
            append("\n[").append(index + 1).append("] (via ")
            append(candidate.providerName).append(")\n")
            append(candidate.text)
            append("\n")
        }
        append("\nReturn the single best merged translation only.")
    }

    /**
     * Builds the study input (§6.2): the selected sample chapters,
     * cleaned with the same rules chapters get (§5.1), earliest
     * chapters first, capped at [STUDY_INPUT_MAX_CHARS] — a chapter
     * that doesn't fit whole is cut at a paragraph boundary.
     * Each chapter is labelled with its origin so the model can pair
     * original ↔ translated when both are present.
     */
    fun buildStudyText(chapters: List<StudyChapterInput>): String {
        val builder = StringBuilder()
        for (chapter in chapters) {
            if (builder.length >= STUDY_INPUT_MAX_CHARS) break
            val cleaned = CleanupRules.clean(chapter.text)
            if (cleaned.isBlank()) continue
            val header = "\n\n===== ${chapter.label} =====\n"
            val room = STUDY_INPUT_MAX_CHARS - builder.length - header.length
            if (room <= 0) break
            builder.append(header)
            builder.append(
                if (cleaned.length <= room) cleaned
                else cleaned.substring(0, room).substringBeforeLast("\n\n", cleaned.take(room))
            )
        }
        return builder.toString().trim()
    }

    private const val MAX_REPAIR_ECHO_CHARS = 4_000
}

/** One sample chapter feeding study: a display label + its raw text. */
data class StudyChapterInput(
    val label: String,
    val text: String
)

/** One raw translation feeding the §5.6 merge judge: who made it + its text. */
data class MergeCandidateText(
    val providerName: String,
    val text: String
)
