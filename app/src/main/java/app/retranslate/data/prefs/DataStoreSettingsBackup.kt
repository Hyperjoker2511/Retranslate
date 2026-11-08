package app.retranslate.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.retranslate.domain.export.BackupSettingValue
import app.retranslate.domain.export.SettingsBackupSource
import app.retranslate.providers.ProviderId
import kotlinx.coroutines.flow.first

/**
 * The backup bridge for settings (§9.3): snapshots the `settings`
 * DataStore generically (every key, typed — SavedLanguages' pair,
 * JobPrefs' delay, and whatever joins the file later) plus the
 * provider instance lists via [ProviderConfigStore]'s own API.
 *
 * Keys are NEVER part of this: the vault lives in its own DataStore
 * (§2.5) which this class never touches — a backup file can be
 * shared without leaking a credential.
 */
class DataStoreSettingsBackup(
    private val settingsStore: DataStore<Preferences>,
    private val providerConfig: ProviderConfigStore
) : SettingsBackupSource {

    override suspend fun snapshotSettings(): Map<String, BackupSettingValue> {
        val prefs = settingsStore.data.first()
        val out = linkedMapOf<String, BackupSettingValue>()
        for ((key, value) in prefs.asMap()) {
            val encoded = when (value) {
                is String -> BackupSettingValue("S", value)
                is Long -> BackupSettingValue("L", value.toString())
                is Int -> BackupSettingValue("I", value.toString())
                is Boolean -> BackupSettingValue("B", value.toString())
                is Float -> BackupSettingValue("F", value.toString())
                is Double -> BackupSettingValue("D", value.toString())
                is Set<*> -> BackupSettingValue(
                    "SS", value.filterIsInstance<String>().joinToString(SET_SEPARATOR)
                )
                else -> null // unknown future type: skipped, never guessed
            }
            if (encoded != null) out[key.name] = encoded
        }
        return out
    }

    override suspend fun applySettings(values: Map<String, BackupSettingValue>) {
        if (values.isEmpty()) return
        settingsStore.edit { prefs ->
            for ((name, setting) in values) {
                when (setting.kind) {
                    "S" -> prefs[stringPreferencesKey(name)] = setting.value
                    "L" -> setting.value.toLongOrNull()?.let {
                        prefs[longPreferencesKey(name)] = it
                    }
                    "I" -> setting.value.toIntOrNull()?.let {
                        prefs[intPreferencesKey(name)] = it
                    }
                    "B" -> setting.value.toBooleanStrictOrNull()?.let {
                        prefs[booleanPreferencesKey(name)] = it
                    }
                    "F" -> setting.value.toFloatOrNull()?.let {
                        prefs[floatPreferencesKey(name)] = it
                    }
                    "D" -> setting.value.toDoubleOrNull()?.let {
                        prefs[doublePreferencesKey(name)] = it
                    }
                    "SS" -> prefs[stringSetPreferencesKey(name)] =
                        if (setting.value.isEmpty()) emptySet()
                        else setting.value.split(SET_SEPARATOR).toSet()
                    // unknown kinds are ignored — a newer backup on an
                    // older app degrades instead of corrupting (§10).
                }
            }
        }
    }

    override suspend fun snapshotProviderInstances(): Map<String, List<String>> =
        ProviderId.entries.associate { id -> id.name to providerConfig.instances(id) }

    override suspend fun applyProviderInstances(instances: Map<String, List<String>>) {
        for ((name, list) in instances) {
            val id = ProviderId.entries.firstOrNull { it.name == name } ?: continue
            if (list.isNotEmpty()) providerConfig.saveInstances(id, list)
        }
    }

    companion object {
        /** String-set join separator (a control char user text never holds). */
        private const val SET_SEPARATOR = "\u0001"
    }
}
