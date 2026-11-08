package app.retranslate.providers.free

import kotlinx.serialization.json.Json

/** The one JSON configuration for provider payloads (PLAN.md §2.3). */
val ProviderJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}
