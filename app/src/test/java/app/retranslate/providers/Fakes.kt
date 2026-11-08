package app.retranslate.providers

import app.retranslate.core.error.AppResult
import app.retranslate.core.network.HttpResponse
import app.retranslate.core.network.HttpTransport
import app.retranslate.providers.hub.HealthStamp
import app.retranslate.providers.hub.HealthStore
import app.retranslate.providers.hub.InstanceStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** One HTTP call a test fake was asked to make (headers included: tests
 *  assert on auth-header shapes — the values are fake probe keys only). */
data class RecordedRequest(
    val method: String,
    val url: String,
    val body: String? = null,
    val headers: Map<String, String> = emptyMap()
)

/** Scripted transport: first matching URL rule wins; records every request. */
class FakeTransport : HttpTransport {
    private val rules = mutableListOf<Pair<(String) -> Boolean, AppResult<HttpResponse>>>()
    val requestedUrls = mutableListOf<String>()
    val requests = mutableListOf<RecordedRequest>()
    var fallback: AppResult<HttpResponse> =
        AppResult.Ok(HttpResponse(404, "no fake rule matched"))

    fun on(match: (String) -> Boolean, result: AppResult<HttpResponse>) = apply {
        rules += match to result
    }

    fun onUrlContaining(fragment: String, code: Int, body: String) = on(
        { it.contains(fragment) },
        AppResult.Ok(HttpResponse(code, body))
    )

    override suspend fun get(url: String, headers: Map<String, String>): AppResult<HttpResponse> {
        requestedUrls += url
        requests += RecordedRequest("GET", url, headers = headers)
        return rules.firstOrNull { (match, _) -> match(url) }?.second ?: fallback
    }

    override suspend fun post(
        url: String,
        jsonBody: String,
        headers: Map<String, String>
    ): AppResult<HttpResponse> {
        requestedUrls += url
        requests += RecordedRequest("POST", url, jsonBody, headers)
        return rules.firstOrNull { (match, _) -> match(url) }?.second ?: fallback
    }
}

/** In-memory health stamps. */
class FakeHealthStore(initial: Map<String, HealthStamp> = emptyMap()) : HealthStore {
    val stamps = initial.toMutableMap()
    override suspend fun loadStamps(): Map<String, HealthStamp> = stamps.toMap()
    override suspend fun saveStamp(key: String, stamp: HealthStamp) {
        stamps[key] = stamp
    }
}

/** Fixed instance lists per provider. */
class FakeInstanceStore(private val map: Map<ProviderId, List<String>>) : InstanceStore {
    override suspend fun instances(id: ProviderId): List<String> = map[id] ?: emptyList()
}

/** A manually advanced clock for deadline/cooldown tests. */
class FakeClock(var now: Long = 0L) {
    val lambda: () -> Long = { now }
}

/** In-memory Preferences DataStore — KeyVault/config-store logic without a device. */
class FakePreferencesDataStore(
    initial: Preferences = emptyPreferences()
) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences
    ): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
