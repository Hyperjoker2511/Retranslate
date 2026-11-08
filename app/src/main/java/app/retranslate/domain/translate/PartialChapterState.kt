package app.retranslate.domain.translate

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Which chunks of a half-done chapter are finished, persisted on the
 * chapter row (`partialStateJson`, PLAN.md §7.4) after EVERY chunk —
 * so a killed process loses at most the in-flight chunk and a resume
 * continues mid-chapter instead of paying for the chapter again.
 *
 * [sourceHash] pins the state to the exact cleaned source it was built
 * from: if the chapter's text changed since (re-import, edit), the
 * state no longer describes it and the translator starts the chapter
 * fresh rather than stitching translations of two different texts.
 */
@Serializable
data class PartialChapterState(
    val sourceHash: String,
    val totalChunks: Int,
    /** Chunk index → translated text. */
    val done: Map<Int, String> = emptyMap(),
    /** Chunk index → provider id that produced it (provenance, §4.4.3). */
    val providers: Map<Int, String> = emptyMap()
) {
    fun withChunk(index: Int, text: String, providerId: String): PartialChapterState =
        copy(done = done + (index to text), providers = providers + (index to providerId))

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Defensive decode (§10): corrupt state reads as "no state". */
        fun decode(raw: String?): PartialChapterState? {
            if (raw.isNullOrBlank()) return null
            return try {
                json.decodeFromString(serializer(), raw)
            } catch (t: Throwable) {
                null
            }
        }
    }
}
