package app.retranslate.domain.kb

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.data.db.KbCategory
import app.retranslate.data.db.KbGender
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One Knowledge Base entry as the AI (or a later merge) proposes it. */
data class KbEntryDraft(
    val category: KbCategory,
    val sourceForm: String?,
    val canonicalForm: String,
    val gender: KbGender?,
    val note: String?,
    val frequency: Int
)

/**
 * Defensive parsing of the study extraction (§6.2, §10 #11): the AI
 * was told "strict JSON" and will, sooner or later, answer with
 * markdown fences, a paragraph of prose around the JSON, unknown
 * categories, or a truncated object. Everything salvageable is
 * salvaged (fences/prose stripped, unknown categories coerced to
 * TERM, unknown genders dropped); a payload with no usable object or
 * zero usable entries is [AppError.AiBadOutput] — study then makes
 * its one repair retry, and writes nothing if that also fails.
 */
object KbExtractionParser {

    /** Hard cap on entries per study — a runaway list helps no one. */
    const val MAX_ENTRIES = 400

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String, providerId: String): AppResult<List<KbEntryDraft>> {
        val candidate = extractJsonObject(raw)
            ?: return AppResult.Err(AppError.AiBadOutput(providerId))
        val dto = try {
            json.decodeFromString<ExtractionDto>(candidate)
        } catch (t: Throwable) {
            return AppResult.Err(AppError.AiBadOutput(providerId))
        }
        val drafts = dto.entries.orEmpty()
            .mapNotNull { entry -> entry.toDraft() }
            .distinctBy { it.category to it.canonicalForm.lowercase() }
            .take(MAX_ENTRIES)
        return if (drafts.isEmpty()) {
            AppResult.Err(AppError.AiBadOutput(providerId))
        } else {
            AppResult.Ok(drafts)
        }
    }

    /** Strips fences/prose: the outermost `{ … }` span is the payload. */
    fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return raw.substring(start, end + 1)
    }

    private fun EntryDto.toDraft(): KbEntryDraft? {
        val canonical = canonicalForm?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val category = category?.trim()?.uppercase()
            ?.let { name -> KbCategory.entries.firstOrNull { it.name == name } }
            ?: KbCategory.TERM
        val gender = gender?.trim()?.uppercase()
            ?.let { name -> KbGender.entries.firstOrNull { it.name == name } }
            ?.takeIf { category == KbCategory.CHARACTER }
        return KbEntryDraft(
            category = category,
            sourceForm = sourceForm?.trim()?.takeIf { it.isNotBlank() },
            canonicalForm = canonical,
            gender = gender,
            note = note?.trim()?.takeIf { it.isNotBlank() },
            frequency = (frequency ?: 1).coerceIn(1, 10_000)
        )
    }

    @Serializable
    private data class ExtractionDto(val entries: List<EntryDto>? = null)

    @Serializable
    private data class EntryDto(
        val category: String? = null,
        val sourceForm: String? = null,
        val canonicalForm: String? = null,
        val gender: String? = null,
        val note: String? = null,
        val frequency: Int? = null
    )
}
