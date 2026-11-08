package app.retranslate.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.retranslate.providers.LANG_AUTO
import app.retranslate.providers.LangCode
import app.retranslate.providers.Languages
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The Translate screen's from/to pair. */
data class LanguagePair(
    val source: LangCode = LANG_AUTO,
    val target: LangCode = "en"
)

/**
 * The ONE owner of the Translate screen's language pair (PLAN.md §3.5 —
 * the class whose absence corrupted the old app: two writers stored two
 * different shapes into the same preference slots, and books ended up
 * with a JSON blob as their "language").
 *
 * Storage: the SAME slot names the old app used (`sourceLanguage` /
 * `targetLanguage`), written in ONE canonical shape — a JSON object
 * `{"code":"ko","name":"Korean"}`. Reads are defensive on purpose:
 * every shape that has ever existed in these slots must decode —
 * the canonical JSON, a bare code ("en"), a quoted string, junk —
 * because a language preference the app can't read must degrade to a
 * sane default, never to a crash or a garbage code sent to providers.
 */
class SavedLanguages(private val store: DataStore<Preferences>) {

    val pair: Flow<LanguagePair> = store.data.map { prefs ->
        LanguagePair(
            source = decode(prefs[KEY_SOURCE], isSource = true),
            target = decode(prefs[KEY_TARGET], isSource = false)
        )
    }

    suspend fun current(): LanguagePair = pair.first()

    suspend fun save(pair: LanguagePair) {
        store.edit { prefs ->
            prefs[KEY_SOURCE] = encode(pair.source, isSource = true)
            prefs[KEY_TARGET] = encode(pair.target, isSource = false)
        }
    }

    companion object {
        private val KEY_SOURCE = stringPreferencesKey("sourceLanguage")
        private val KEY_TARGET = stringPreferencesKey("targetLanguage")

        const val DEFAULT_SOURCE: LangCode = LANG_AUTO
        const val DEFAULT_TARGET: LangCode = "en"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** The canonical stored shape. */
        fun encode(code: LangCode, isSource: Boolean): String {
            val valid = validate(code, isSource)
            val name = if (valid == LANG_AUTO) Languages.AUTO.name else Languages.nameOf(valid)
            val obj: JsonObject = buildJsonObject {
                put("code", valid)
                put("name", name)
            }
            return obj.toString()
        }

        /**
         * Decodes ANY historical shape of a stored language value:
         * canonical JSON object, bare code, JSON-quoted string, blank,
         * unknown code, wrong-slot `auto`. Anything unusable → default.
         */
        fun decode(raw: String?, isSource: Boolean): LangCode {
            val fallback = if (isSource) DEFAULT_SOURCE else DEFAULT_TARGET
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return fallback
            val candidate: String = when {
                text.startsWith("{") -> extractJsonCode(text) ?: return fallback
                text.startsWith("\"") -> try {
                    json.parseToJsonElement(text).jsonPrimitive.content
                } catch (t: Throwable) {
                    return fallback
                }
                else -> text
            }
            return validate(candidate, isSource)
        }

        private fun validate(candidate: LangCode, isSource: Boolean): LangCode {
            val fallback = if (isSource) DEFAULT_SOURCE else DEFAULT_TARGET
            val code = candidate.trim()
            if (code == LANG_AUTO) return if (isSource) LANG_AUTO else fallback
            return if (Languages.isKnown(code)) code else fallback
        }

        private fun extractJsonCode(text: String): String? = try {
            val element = json.parseToJsonElement(text)
            val obj = element as? JsonObject ?: return null
            (obj["code"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            null
        }
    }
}
