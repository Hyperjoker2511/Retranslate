package app.retranslate.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * The app's DataStore files (PLAN.md §2.5/§3.5). Three files, one owner
 * class each:
 *  - `settings` → owned by [SavedLanguages] (more settings join it later)
 *  - `provider_config` → owned by `ProviderConfigStore`
 *  - `key_vault` → owned by `KeyVault` (ciphertext only — §2.5)
 * The delegates live here so each file has exactly one instance.
 */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

val Context.providerConfigDataStore: DataStore<Preferences> by preferencesDataStore(name = "provider_config")

val Context.keyVaultDataStore: DataStore<Preferences> by preferencesDataStore(name = "key_vault")
