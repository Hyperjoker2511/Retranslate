package app.retranslate.providers.key

import app.retranslate.core.error.AppResult

/**
 * A provider whose calls are authorised by a secret the user pastes
 * (PLAN.md §4.3). The secret lives ONLY in the KeyVault; providers fetch
 * it per call through an injected lambda and never retain it.
 *
 * The key-entry screen (§9.3) must prove a candidate secret works
 * BEFORE anything is saved — §11's milestone: "fake key → plain error,
 * nothing saved" — which is what [testWithSecret] is for.
 */
interface KeyTestable {
    /**
     * One bounded check using [secret] exactly as the user typed it
     * (for Azure, the assembled `key|region` vault shape). Implementations
     * must not persist [secret] anywhere.
     */
    suspend fun testWithSecret(secret: String): AppResult<Unit>
}

/** The vault entry name for a provider's secret: its [app.retranslate.providers.ProviderId] name. */
fun keyVaultEntry(id: app.retranslate.providers.ProviderId): String = id.name
