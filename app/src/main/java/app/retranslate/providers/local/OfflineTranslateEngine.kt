package app.retranslate.providers.local

import app.retranslate.core.error.AppResult

/** One downloaded on-device language pair, in ML Kit language tags. */
data class OfflinePair(val source: String, val target: String)

/**
 * The on-device translation engine behind the Offline provider
 * (§H, v1.1.3): an interface so the provider and the Providers
 * screen's download manager are JVM-testable with a fake, while the
 * production implementation talks to Google ML Kit. All languages
 * here are ML Kit tags ("ko", "zh", "en") — the provider maps the
 * app's codes onto them.
 */
interface OfflineTranslateEngine {

    /** ML Kit tags this device can translate between at all. */
    fun supportedTags(): Set<String>

    /** Pairs whose models are on the phone right now. */
    suspend fun downloadedPairs(): Set<OfflinePair>

    /** Downloads one pair's model (~30 MB); [wifiOnly] honours §H's default. */
    suspend fun download(sourceTag: String, targetTag: String, wifiOnly: Boolean): AppResult<Unit>

    /** Deletes one pair's model (the removability law, §H). */
    suspend fun delete(sourceTag: String, targetTag: String): AppResult<Unit>

    /** Translates with an already-downloaded pair. */
    suspend fun translate(text: String, sourceTag: String, targetTag: String): AppResult<String>

    companion object {
        /** The honest size line: ML Kit pairs are ~30 MB each. */
        const val APPROX_PAIR_SIZE_MB = 30
    }
}
