package app.retranslate.domain.kb

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.data.db.BookDao
import app.retranslate.data.db.DbEnums
import app.retranslate.data.db.JobRunDao
import app.retranslate.data.db.JobState
import app.retranslate.data.db.SampleDao
import app.retranslate.domain.translate.AiPrompts
import app.retranslate.domain.translate.StudyChapterInput
import app.retranslate.providers.Languages
import app.retranslate.providers.QualityMode

/** Which chapters of which sample set to study (§6.1 range pickers). */
data class StudySelection(val ranges: List<SetRange>) {
    data class SetRange(val setId: Long, val fromChapter: Int, val toChapter: Int)

    val totalChapters: Int get() = ranges.sumOf { (it.toChapter - it.fromChapter + 1).coerceAtLeast(0) }
    val isEmpty: Boolean get() = ranges.isEmpty() || totalChapters == 0
}

/** How a study run ended — the worker maps these onto WorkManager results. */
sealed interface StudyResult {
    /** KB written: [entries] names/terms/rules, from [chapters] sample chapters. */
    data class Finished(val entries: Int, val chapters: Int, val kbVersion: Int) : StudyResult

    /** Study failed; NOTHING was written (§6.2: no half-built KB). */
    data class Failed(val error: AppError) : StudyResult

    /** The user cancelled; nothing was written. */
    data object Cancelled : StudyResult
}

/**
 * Runs one Context-Learning study (PLAN.md §6.2), WorkManager-free
 * like the translation engine so the whole flow — extraction,
 * repair retry, no-write-on-failure — is JVM-testable:
 *
 *  selected sample chapters → cleaned, capped study text (§6.2)
 *  → AI extraction (strict JSON) → defensive parse
 *  → on garbage: ONE repair retry with the model's own output
 *  → still garbage (or any provider failure): run FAILED, no KB.
 *
 * Cancellation is cooperative via the JobRun row, exactly like
 * chapter jobs: the row is checked before each AI call and again
 * before the KB write, so a cancelled study never lands a KB.
 */
class StudyEngine(
    private val bookDao: BookDao,
    private val sampleDao: SampleDao,
    private val jobRunDao: JobRunDao,
    private val kbRepository: KbRepository,
    private val ai: StudyAi,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun run(
        bookId: Long,
        jobRunId: Long,
        selection: StudySelection
    ): StudyResult {
        val book = bookDao.getById(bookId)
            ?: return fail(jobRunId, AppError.StorageFailed("loading the book"))
        if (isCancelled(jobRunId)) return StudyResult.Cancelled

        jobRunDao.getById(jobRunId)?.let { run ->
            jobRunDao.update(
                run.copy(
                    state = JobState.RUNNING.name,
                    totalChapters = selection.totalChapters,
                    doneChapters = 0,
                    failedChapters = 0,
                    finishedAt = null,
                    lastErrorClass = null
                )
            )
        }

        if (!ai.isConfigured()) {
            return fail(
                jobRunId,
                AppError.AuthFailed(GeminiStudyAi.PROVIDER_ID.name)
            )
        }

        // ---- Gather the selected sample chapters -------------------
        val sets = sampleDao.getSets(bookId).associateBy { it.id }
        val inputs = mutableListOf<StudyChapterInput>()
        var studiedChapters = 0
        var hasOriginalSamples = false
        for (range in selection.ranges) {
            val set = sets[range.setId] ?: continue
            val lang = set.detectedLang
            if (lang.isNotBlank() && lang == book.sourceLang && lang != book.targetLang) {
                hasOriginalSamples = true
            }
            val role = when {
                lang.isBlank() -> ""
                lang == book.targetLang -> " — ${Languages.nameOf(lang)}, already translated"
                lang == book.sourceLang -> " — ${Languages.nameOf(lang)}, original"
                else -> " — ${Languages.nameOf(lang)}"
            }
            for (chapter in sampleDao.getChapters(set.id)) {
                if (chapter.number in range.fromChapter..range.toChapter) {
                    inputs += StudyChapterInput(
                        label = "From '${set.fileName}', chapter ${chapter.number}$role",
                        text = chapter.text
                    )
                    studiedChapters++
                }
            }
        }
        val studyText = AiPrompts.buildStudyText(inputs)
        if (studyText.isBlank() || studiedChapters == 0) {
            return fail(
                jobRunId,
                AppError.VerificationFailed("the selected sample chapters are empty")
            )
        }

        // ---- Extract, with one repair retry -------------------------
        val targetName = Languages.nameOf(book.targetLang)
        val systemPrompt = AiPrompts.studySystemPrompt(targetName)
        if (isCancelled(jobRunId)) return StudyResult.Cancelled
        val firstRaw = when (
            val first = ai.complete(
                systemPrompt,
                AiPrompts.studyUserPrompt(studyText, targetName, hasOriginalSamples),
                book.targetLang,
                QualityMode.BALANCED
            )
        ) {
            is AppResult.Ok -> first.value
            is AppResult.Err -> {
                if (first.error is AppError.AiBadOutput) {
                    "" // garbled/empty answer — goes down the repair path
                } else {
                    return fail(jobRunId, first.error)
                }
            }
        }

        var drafts = KbExtractionParser.parse(firstRaw, GeminiStudyAi.PROVIDER_ID.name)
            .getOrNull()
        if (drafts == null) {
            if (isCancelled(jobRunId)) return StudyResult.Cancelled
            val repairedRaw = when (
                val repair = ai.complete(
                    systemPrompt,
                    AiPrompts.studyRepairPrompt(firstRaw, targetName),
                    book.targetLang,
                    QualityMode.BALANCED
                )
            ) {
                is AppResult.Ok -> repair.value
                is AppResult.Err -> return fail(jobRunId, repair.error)
            }
            drafts = KbExtractionParser.parse(repairedRaw, GeminiStudyAi.PROVIDER_ID.name)
                .getOrNull()
                ?: return fail(
                    jobRunId,
                    AppError.AiBadOutput(GeminiStudyAi.PROVIDER_ID.name)
                )
        }

        // ---- Land the KB (one transaction inside the repository) ----
        if (isCancelled(jobRunId)) return StudyResult.Cancelled
        val kb = when (
            val created = kbRepository.createFromStudy(
                bookId = bookId,
                drafts = drafts,
                sourceNote = "Studied $studiedChapters sample chapters"
            )
        ) {
            is AppResult.Ok -> created.value
            is AppResult.Err -> return fail(jobRunId, created.error)
        }
        for (range in selection.ranges) {
            sets[range.setId]?.let { set ->
                sampleDao.updateSet(set.copy(studiedInKbVersion = kb.version))
            }
        }
        jobRunDao.getById(jobRunId)?.let { run ->
            jobRunDao.update(
                run.copy(
                    state = JobState.FINISHED.name,
                    doneChapters = studiedChapters,
                    finishedAt = clock(),
                    lastErrorClass = null
                )
            )
        }
        return StudyResult.Finished(
            entries = drafts.size,
            chapters = studiedChapters,
            kbVersion = kb.version
        )
    }

    private suspend fun isCancelled(jobRunId: Long): Boolean =
        jobRunDao.getById(jobRunId)?.let { DbEnums.jobState(it.state) } == JobState.CANCELLED

    private suspend fun fail(jobRunId: Long, error: AppError): StudyResult {
        jobRunDao.getById(jobRunId)?.let { run ->
            if (DbEnums.jobState(run.state) != JobState.CANCELLED) {
                jobRunDao.update(
                    run.copy(
                        state = JobState.FAILED.name,
                        finishedAt = clock(),
                        lastErrorClass = error::class.simpleName
                    )
                )
            }
        }
        return StudyResult.Failed(error)
    }
}
