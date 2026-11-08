package app.retranslate.domain.translate

import app.retranslate.providers.QualityMode

/**
 * v1.1.3 (§G candidate 5): how hard Improve rewrites. The lite-first
 * cascade can return near-identical text for already-readable MTL —
 * "applied, but visibly unchanged" was the original "polish did
 * nothing" complaint — so the user gets a plain strength choice.
 * Gentle is today's behaviour; Strong starts AI calls at the standard
 * tier (more quota, visibly better rewriting of bad MTL).
 */
enum class ImproveStrength(val label: String, val quality: QualityMode) {
    GENTLE("Gentle — light touch, light on quota", QualityMode.BALANCED),
    STRONG("Strong — deeper rewrite, uses more quota", QualityMode.BEST);

    companion object {
        /** Defensive decode for the value stored on a JobRun row. */
        fun fromName(name: String?): ImproveStrength =
            entries.firstOrNull { it.name == name } ?: GENTLE
    }
}

/**
 * v1.1.3 (§G candidate 4): which chapters a bulk Improve run covers.
 * Stored by name on the JobRun row so a quota-paused run resumes with
 * the same scope.
 */
enum class PolishScope(val label: String) {
    /** Every translated chapter that has text. */
    ALL("All translated chapters"),

    /** Only chapters whose quality label is Rough (or unlabelled). */
    ROUGH("Only Rough ones"),

    /** Only chapters the user flagged a paragraph in (Review screen). */
    FLAGGED("Chapters I flagged"),

    /**
     * Chapters never improved, or improved against an older Knowledge
     * Base version — re-running a job never re-spends quota on work
     * already done against the current KB (§G candidate 4).
     */
    NOT_YET_IMPROVED("Not improved yet"),

    /** v1.1.3 (§H): chapters translated by the Offline provider. */
    OFFLINE_TRANSLATED("Offline-translated");

    companion object {
        /** Defensive decode for the value stored on a JobRun row. */
        fun fromName(name: String?): PolishScope =
            entries.firstOrNull { it.name == name } ?: ALL
    }
}

/**
 * The plain quality vocabulary (plan F17): every chapter with a score
 * gets one of three words — in the chapter list, the Review header
 * and the bulk-Improve scope picker alike. Thresholds are shared so
 * "Rough" means the same thing everywhere it appears.
 */
object QualityLabel {
    const val ROUGH_BELOW = 50f
    const val GOOD_FROM = 80f

    /** Null when the chapter has no score yet (nothing honest to say). */
    fun of(score: Float?): String? = when {
        score == null -> null
        score < ROUGH_BELOW -> "Rough"
        score < GOOD_FROM -> "Okay"
        else -> "Good"
    }

    /** True when the label would be Rough (or there is no label yet). */
    fun isRough(score: Float?): Boolean = score == null || score < ROUGH_BELOW
}
