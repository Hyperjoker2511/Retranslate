package app.retranslate.domain.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN.md §5.4/§6.2: prompt assembly — caps, style capture, KB framing. */
class AiPromptsTest {

    @Test
    fun `chapter prompt carries pair, genre, KB and the output-only contract`() {
        val prompt = AiPrompts.chapterSystemPrompt(
            sourceLang = "ko", targetLang = "en",
            genrePresetId = "cultivation", kbBlock = "Jin Woo (male)"
        )
        assertTrue(prompt.contains("Korean"))
        assertTrue(prompt.contains("English"))
        assertTrue(prompt.contains("Cultivation novel"))
        assertTrue(prompt.contains("Jin Woo (male)"))
        assertTrue(prompt.contains("reads like")) // style framing, not just names
        assertTrue(prompt.contains("Output ONLY the translation"))
    }

    @Test
    fun `study prompts demand JSON, pairing and style rules`() {
        val user = AiPrompts.studyUserPrompt("TEXT", "English", hasOriginalSamples = true)
        assertTrue(user.contains("\"entries\""))
        assertTrue(user.contains("Pair them"))
        assertTrue(user.contains("STYLE rules"))
        assertTrue(user.contains("sentence flow"))
        assertTrue(user.contains("dialogue"))
        val repair = AiPrompts.studyRepairPrompt("{broken", "English")
        assertTrue(repair.contains("not valid JSON"))
        assertTrue(repair.contains("{broken"))
    }

    @Test
    fun `polish prompt carries the KB block and the locked-name law`() {
        val withKb = AiPrompts.polishSystemPrompt(
            targetLang = "en", genrePresetId = null,
            kbBlock = "Jin Woo (male, locked)"
        )
        assertTrue(withKb.contains("Jin Woo"))
        assertTrue(withKb.contains("LOCKED"))
        assertTrue(withKb.contains("Do not add, drop or merge paragraphs"))
        assertTrue(withKb.contains("Output ONLY the polished chapter"))

        val withoutKb = AiPrompts.polishSystemPrompt(
            targetLang = "es", genrePresetId = null, kbBlock = null
        )
        assertTrue(!withoutKb.contains("Novel Knowledge Base"))
        assertTrue(withoutKb.contains("Spanish"))
    }

    @Test
    fun `study text is capped and earliest chapters win`() {
        val big = "단락입니다.\n\n".repeat(4_000) // ~32k chars each
        val inputs = (1..4).map { n ->
            StudyChapterInput(label = "Chapter $n", text = "MARKER$n\n\n$big")
        }
        val text = AiPrompts.buildStudyText(inputs)
        assertTrue(text.length <= AiPrompts.STUDY_INPUT_MAX_CHARS)
        assertEquals(60_000, AiPrompts.STUDY_INPUT_MAX_CHARS)
        assertTrue(text.contains("Chapter 1"))
        // Earliest chapters are prioritised: chapter 4 never makes it.
        assertTrue(!text.contains("MARKER4"))
    }
}
