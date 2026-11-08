package app.retranslate.core.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.retranslate.core.error.runCatchingAppSuspend
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Where the user's API keys live (PLAN.md §2.5) — the ONLY class that
 * touches them. Secrets are encrypted with [SecretCipher] (Keystore
 * AES-256/GCM on device) and the ciphertext is stored as Base64 in a
 * private DataStore file. Plaintext secrets exist only as the return
 * value of [get], in the caller's hands, for one request at a time.
 *
 * Keys are never written to Room, never included in backups (§9.3),
 * and never logged — this class has no logging at all.
 *
 * A synchronous presence cache ([presentKeys]) lets providers answer
 * "am I configured?" inside the router's non-suspending pre-filter
 * (§4.4.1). It holds NAMES ONLY, never secret material.
 *
 * Defensive reads (§10): a stored entry that fails to decode/decrypt
 * (corrupt write, Keystore key invalidated by a lock-screen reset…)
 * reads as "no key" instead of crashing — the provider then reports
 * itself unconfigured and the chain simply routes around it.
 */
class KeyVault(
    private val store: DataStore<Preferences>,
    private val cipher: SecretCipher,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val _presentKeys = MutableStateFlow<Set<String>>(emptySet())

    /** Names of the entries currently stored — names only, never values. */
    val presentKeys: StateFlow<Set<String>> = _presentKeys.asStateFlow()

    init {
        // Keeps the presence cache in step with storage from process
        // start (a supervisor scope owned by the vault: a storage hiccup
        // must never take the app down, §10 coroutine discipline).
        scope.launch {
            store.data.collect { prefs ->
                _presentKeys.value = prefs.asMap().keys
                    .map { it.name }
                    .filter { it.startsWith(ENTRY_PREFIX) }
                    .map { it.removePrefix(ENTRY_PREFIX) }
                    .toSet()
            }
        }
    }

    /** Synchronous presence check for the §4.4.1 chain pre-filter. */
    fun isPresent(name: String): Boolean = name in _presentKeys.value

    /** The decrypted secret, or null when absent/unreadable. */
    suspend fun get(name: String): String? {
        val raw = store.data.first()[entryKey(name)] ?: return null
        return runCatchingAppSuspend { decode(raw) }.getOrNull()
    }

    suspend fun put(name: String, secret: String) {
        val encoded = encode(secret)
        store.edit { prefs -> prefs[entryKey(name)] = encoded }
        _presentKeys.update { it + name }
    }

    suspend fun remove(name: String) {
        store.edit { prefs -> prefs.remove(entryKey(name)) }
        _presentKeys.update { it - name }
    }

    /** Plaintext → Base64(IV ‖ ciphertext). Internal so tests can pin the format. */
    internal fun encode(secret: String): String =
        Base64.getEncoder().encodeToString(cipher.encrypt(secret.toByteArray(Charsets.UTF_8)))

    /** Inverse of [encode]; throws on any malformed input (caller coerces). */
    internal fun decode(raw: String): String =
        String(cipher.decrypt(Base64.getDecoder().decode(raw)), Charsets.UTF_8)

    private fun entryKey(name: String) = stringPreferencesKey(ENTRY_PREFIX + name)

    companion object {
        /** DataStore key prefix; entry names never carry secret material. */
        const val ENTRY_PREFIX = "vault_"
    }
}
