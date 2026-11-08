package app.retranslate.providers.local

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The production [OfflineTranslateEngine] over Google ML Kit
 * (§H): 59 languages including Korean and Chinese, language models
 * download to the phone and translation runs entirely on-device — free,
 * no key, no account, nothing leaves the device. Gemini Nano/AICore
 * is deliberately NOT used (the user's M31 doesn't have it).
 */
class MlKitTranslateEngine : OfflineTranslateEngine {

    override fun supportedTags(): Set<String> =
        TranslateLanguage.getAllLanguages().toSet()

    override suspend fun downloadedPairs(): Set<OfflinePair> {
        // ML Kit models are per LANGUAGE, not per pair (verified against
        // the real 17.0.3 API: TranslateRemoteModel.Builder takes ONE
        // language code and the model exposes getLanguage()). A pair is
        // usable — in either direction — when both languages' models are
        // on the device.
        val languages = downloadedLanguages()
        return languages.flatMap { source ->
            languages
                .filter { target -> target != source }
                .map { target -> OfflinePair(source, target) }
        }.toSet()
    }

    /** The single languages whose models are on the phone right now. */
    private suspend fun downloadedLanguages(): Set<String> {
        val models = RemoteModelManager.getInstance()
            .getDownloadedModels(TranslateRemoteModel::class.java)
            .awaitTask()
            ?: return emptySet()
        return models.map { it.language }.toSet()
    }

    override suspend fun download(
        sourceTag: String,
        targetTag: String,
        wifiOnly: Boolean
    ): AppResult<Unit> {
        val conditions = DownloadConditions.Builder().apply {
            if (wifiOnly) requireWifi()
        }.build()
        val manager = RemoteModelManager.getInstance()
        // Both languages' models make the pair — fetch each one.
        for (tag in setOf(sourceTag, targetTag)) {
            val model = TranslateRemoteModel.Builder(tag).build()
            when (val result = manager.download(model, conditions).awaitUnit()) {
                is AppResult.Ok -> Unit
                is AppResult.Err -> return result
            }
        }
        return AppResult.Ok(Unit)
    }

    override suspend fun delete(sourceTag: String, targetTag: String): AppResult<Unit> {
        val manager = RemoteModelManager.getInstance()
        for (tag in setOf(sourceTag, targetTag)) {
            val model = TranslateRemoteModel.Builder(tag).build()
            when (val result = manager.deleteDownloadedModel(model).awaitUnit()) {
                is AppResult.Ok -> Unit
                is AppResult.Err -> return result
            }
        }
        return AppResult.Ok(Unit)
    }

    override suspend fun translate(
        text: String,
        sourceTag: String,
        targetTag: String
    ): AppResult<String> {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceTag)
            .setTargetLanguage(targetTag)
            .build()
        val client = Translation.getClient(options)
        return try {
            when (val result = client.translate(text).awaitValue()) {
                is AppResult.Ok -> AppResult.Ok(result.value)
                is AppResult.Err -> result
            }
        } finally {
            client.close()
        }
    }

    /** Awaits a Task, mapping failure onto the §10 catalogue. */
    private suspend fun <T> Task<T>.awaitValue(): AppResult<T> =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { value ->
                if (continuation.isActive) {
                    continuation.resume(AppResult.Ok(value))
                }
            }
            addOnFailureListener { error ->
                if (continuation.isActive) {
                    continuation.resume(
                        AppResult.Err(
                            AppError.ProviderDown(
                                "OFFLINE",
                                retryable = false,
                                detail = error.javaClass.simpleName
                            )
                        )
                    )
                }
            }
        }

    private suspend fun Task<Void>.awaitUnit(): AppResult<Unit> =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener {
                if (continuation.isActive) continuation.resume(AppResult.Ok(Unit))
            }
            addOnFailureListener { error ->
                if (continuation.isActive) {
                    continuation.resume(
                        AppResult.Err(
                            AppError.ProviderDown(
                                "OFFLINE",
                                retryable = false,
                                detail = error.javaClass.simpleName
                            )
                        )
                    )
                }
            }
        }

    /** For reads where failure honestly means "nothing there". */
    private suspend fun <T> Task<T>.awaitTask(): T? =
        when (val result = awaitValue()) {
            is AppResult.Ok -> result.value
            is AppResult.Err -> null
        }
}
