package app.retranslate.domain.kb

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.data.db.KbCategory
import app.retranslate.data.db.KbGender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN.md §6.2/§10 #11: extraction parsing must be defensive, never crash. */
class KbExtractionParserTest {

    private val validJson = """
        {"entries":[
          {"category":"CHARACTER","sourceForm":"진우","canonicalForm":"Jin Woo",
           "gender":"MALE","note":"referred to as 'he'","frequency":8},
          {"category":"LOCATION","sourceForm":null,"canonicalForm":"Hunter Academy",
           "gender":null,"note":null,"frequency":5},
          {"category":"TONE_RULE","sourceForm":null,
           "canonicalForm":"Narration is plain and direct; short sentences.",
           "gender":null,"note":null,"frequency":10}
        ]}
    """.trimIndent()

    @Test
    fun `valid strict JSON parses fully`() {
        val result = KbExtractionParser.parse(validJson, "GEMINI")
        val drafts = (result as AppResult.Ok).value
        assertEquals(3, drafts.size)
        val jin = drafts.first { it.canonicalForm == "Jin Woo" }
        assertEquals(KbCategory.CHARACTER, jin.category)
        assertEquals("진우", jin.sourceForm)
        assertEquals(KbGender.MALE, jin.gender)
        assertEquals(8, jin.frequency)
        assertEquals(KbCategory.TONE_RULE, drafts[2].category)
    }

    @Test
    fun `markdown fenced JSON is salvaged`() {
        val fenced = "Here is the Knowledge Base:\n```json\n$validJson\n```\nHope that helps!"
        val drafts = (KbExtractionParser.parse(fenced, "GEMINI") as AppResult.Ok).value
        assertEquals(3, drafts.size)
    }

    @Test
    fun `prose around the object is salvaged`() {
        val wrapped = "I studied the chapters carefully. $validJson Let me know if you need more."
        val drafts = (KbExtractionParser.parse(wrapped, "GEMINI") as AppResult.Ok).value
        assertEquals(3, drafts.size)
    }

    @Test
    fun `truncated JSON is AiBadOutput, never a crash`() {
        val truncated = validJson.substring(0, validJson.length / 2)
        val result = KbExtractionParser.parse(truncated, "GEMINI")
        assertTrue((result as AppResult.Err).error is AppError.AiBadOutput)
    }

    @Test
    fun `pure garbage is AiBadOutput`() {
        val result = KbExtractionParser.parse("the names are many and varied", "GEMINI")
        assertTrue((result as AppResult.Err).error is AppError.AiBadOutput)
        assertEquals(
            "GEMINI",
            ((result as AppResult.Err).error as AppError.AiBadOutput).providerId
        )
    }

    @Test
    fun `empty entries list is AiBadOutput`() {
        val result = KbExtractionParser.parse("""{"entries":[]}""", "GEMINI")
        assertTrue((result as AppResult.Err).error is AppError.AiBadOutput)
    }

    @Test
    fun `unknown categories coerce to TERM and bad genders drop`() {
        val json = """
            {"entries":[
              {"category":"WEAPON","canonicalForm":"Soul Sword","gender":"X","frequency":2},
              {"category":"character","canonicalForm":"Mi Ra","gender":"female","frequency":1}
            ]}
        """.trimIndent()
        val drafts = (KbExtractionParser.parse(json, "GEMINI") as AppResult.Ok).value
        assertEquals(KbCategory.TERM, drafts[0].category)
        assertNull(drafts[0].gender) // gender only survives on characters
        assertEquals(KbGender.FEMALE, drafts[1].gender) // case-insensitive
    }

    @Test
    fun `blank canonical forms are dropped and duplicates merge`() {
        val json = """
            {"entries":[
              {"category":"TERM","canonicalForm":"  ","frequency":1},
              {"category":"TERM","canonicalForm":"Qi","frequency":1},
              {"category":"TERM","canonicalForm":"qi","frequency":9}
            ]}
        """.trimIndent()
        val drafts = (KbExtractionParser.parse(json, "GEMINI") as AppResult.Ok).value
        assertEquals(1, drafts.size)
        assertEquals("Qi", drafts[0].canonicalForm)
    }
}
