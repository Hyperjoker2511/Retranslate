package app.retranslate.domain.translate

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.providers.LANG_AUTO
import app.retranslate.providers.Languages
import app.retranslate.providers.QualityMode
import app.retranslate.providers.hub.ProviderRouter
import app.retranslate.providers.hub.TranslatePurpose

/** A completed one-off large-text translation (§9.3). */
data class LargeTextOutcome(
    val text: String,
    /** Provider display names that answered, in first-use order. */
    val viaNames: List<String>,
    val chunkCount: Int,
    /** True when any chunk had to switch away from the chain's first provider. */
    val switched: Boolean
)

/**
 * Large text / paste-a-chapter translation (PLAN.md §9.3): the input
 * is split with the same byte-exact [TextChunker] as book chapters and
 * each chunk goes through the chapter-budget fallback chain. Chunks
 * are verified with the same local checks as chapters; a failed chunk
 * fails the whole one-off result rather than silently returning a
 * partial translation. Nothing is stored — saving into a book is the
 * Library/book flow's job.
 */
class LargeTextTranslator(
    private val router: ProviderRouter,
    private val pacer: RequestPacer? = null
) {

    suspend fun translate(
        text: String,
        source: String,
        target: String,
        onProgress: (doneChunks: Int, totalChunks: Int) -> Unit = { _, _ -> },
        /**
         * v1.1.3 (§C): the standalone chapter's mini-base, when one was
         * built — carried into every chunk so names stay consistent
         * across the whole pasted chapter.
         */
        context: app.retranslate.providers.AiContext? = null,
        /**
         * v1.1.3 (§I): ONE specific engine the user picked for this
         * run. Its failure is reported plainly — never a silent
         * switch to another engine. Null = the Automatic chain.
         */
        providerOverride: app.retranslate.providers.Provider? = null
    ): AppResult<LargeTextOutcome> {
        val input = text.trim()
        if (input.isBlank()) {
            return AppResult.Err(
                AppError.VerificationFailed("type or paste the chapter text first")
            )
        }
        if (source != LANG_AUTO && source == target) {
            return AppResult.Err(AppError.SameLanguage(Languages.nameOf(target)))
        }
        if (input.length > MAX_INPUT_CHARS) {
            return AppResult.Err(
                AppError.VerificationFailed(
                    "this paste is over $MAX_INPUT_CHARS characters — " +
                        "import it as a book instead, so it can be translated in chapters"
                )
            )
        }

        val chunks = TextChunker.chunk(input)
        val outputs = ArrayList<String>(chunks.size)
        val viaNames = LinkedHashSet<String>()
        var switched = false

        for ((index, chunk) in chunks.withIndex()) {
            if (index > 0) pacer?.pace()
            when (
                val routed = if (providerOverride != null) {
                    providerOverride.translate(
                        app.retranslate.providers.TranslateRequest(
                            text = chunk,
                            source = source,
                            target = target,
                            context = context,
                            quality = QualityMode.BALANCED,
                            deadlineMs = Long.MAX_VALUE
                        )
                    ).let { single ->
                        when (single) {
                            is AppResult.Err -> single
                            is AppResult.Ok -> AppResult.Ok(
                                app.retranslate.providers.hub.RoutedTranslation(
                                    text = single.value.text,
                                    via = providerOverride.id,
                                    viaName = providerOverride.displayName,
                                    detectedSource = single.value.detectedSource,
                                    switchedFromName = null,
                                    attempted = listOf(providerOverride.id)
                                )
                            )
                        }
                    }
                } else {
                    router.translate(
                        text = chunk,
                        source = source,
                        target = target,
                        purpose = TranslatePurpose.CHAPTER,
                        context = context
                    )
                }
            ) {
                is AppResult.Err -> return AppResult.Err(routed.error)
                is AppResult.Ok -> {
                    val value = routed.value
                    val effectiveSource = value.detectedSource ?: source
                    val issues = ChapterVerifier.chunkIssues(
                        chunk, value.text, effectiveSource, target
                    )
                    if (issues.isNotEmpty()) {
                        return AppResult.Err(
                            AppError.VerificationFailed(
                                "part ${index + 1} of ${chunks.size}: ${issues.first()}"
                            )
                        )
                    }
                    outputs += value.text
                    viaNames += value.viaName
                    if (value.switchedFromName != null) switched = true
                    onProgress(index + 1, chunks.size)
                }
            }
        }

        return AppResult.Ok(
            LargeTextOutcome(
                text = outputs.joinToString(separator = ""),
                viaNames = viaNames.toList(),
                chunkCount = chunks.size,
                switched = switched
            )
        )
    }

    companion object {
        /**
         * One-off pasted text ceiling. Larger works belong in the book
         * pipeline, where progress is stored and jobs can resume.
         */
        const val MAX_INPUT_CHARS = 200_000
    }
}
