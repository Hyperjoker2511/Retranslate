package app.retranslate.domain.translate

import app.retranslate.data.db.CachedTranslationDao
import app.retranslate.data.db.CachedTranslationEntity
import app.retranslate.providers.QualityMode
import java.security.MessageDigest

/**
 * The translation cache (PLAN.md §5.5): identical work is never paid
 * for twice — resuming a job, re-running a chapter, or the repeated
 * system-window lines web novels are full of all hit the cache.
 *
 * Lookup key = hash(source text + pair + quality mode + KB version).
 * The §5.5 text also names provider/model in the key; those are only
 * known AFTER a call, so they are recorded on the row (provenance)
 * instead of keying the lookup — a cached chunk is reused whoever
 * produced it. (Deviation noted in the P4 report.)
 */
class TranslationCache(
    private val dao: CachedTranslationDao,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    data class Hit(val text: String, val providerId: String)

    fun hash(
        text: String,
        sourceLang: String,
        targetLang: String,
        quality: QualityMode,
        kbVersion: Int?
    ): String {
        val material = buildString {
            append(sourceLang).append('\u0001')
            append(targetLang).append('\u0001')
            append(quality.name).append('\u0001')
            append(kbVersion?.toString() ?: "-").append('\u0001')
            append(text)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    suspend fun get(
        text: String,
        sourceLang: String,
        targetLang: String,
        quality: QualityMode,
        kbVersion: Int?
    ): Hit? {
        val row = dao.getByHash(hash(text, sourceLang, targetLang, quality, kbVersion))
            ?: return null
        return Hit(row.resultText, row.providerId)
    }

    suspend fun put(
        text: String,
        sourceLang: String,
        targetLang: String,
        quality: QualityMode,
        kbVersion: Int?,
        providerId: String,
        resultText: String
    ) {
        dao.put(
            CachedTranslationEntity(
                hash = hash(text, sourceLang, targetLang, quality, kbVersion),
                sourceLang = sourceLang,
                targetLang = targetLang,
                providerId = providerId,
                kbVersion = kbVersion,
                resultText = resultText,
                createdAt = clock()
            )
        )
    }
}
