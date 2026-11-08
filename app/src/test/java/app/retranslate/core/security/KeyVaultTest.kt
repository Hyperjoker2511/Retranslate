package app.retranslate.core.security

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.retranslate.providers.FakePreferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reversible toy cipher with a magic header: decrypting bytes it did
 * not produce throws — the same contract the real AES-GCM cipher has
 * (wrong key / tampered data ⇒ exception), which is what lets the
 * vault's defensive-read tests run on the JVM. The Android Keystore
 * itself cannot run here; KeyVault's storage logic is what is under
 * test (stated honestly in the P2 report).
 */
private class PrefixCipher(private val magic: Byte = 0x2A) : SecretCipher {
    override fun encrypt(plain: ByteArray): ByteArray =
        byteArrayOf(magic) + plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

    override fun decrypt(data: ByteArray): ByteArray {
        require(data.isNotEmpty() && data[0] == magic) { "not my ciphertext" }
        return data.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
}

/**
 * One vault + its store on an Unconfined scope of its own: the vault's
 * presence collector runs forever by design, so it must not live in
 * runTest's scope (which fails on uncompleted children) — and
 * Unconfined makes collection synchronous, so no clock games.
 */
private class Fixture(cipher: SecretCipher = PrefixCipher()) {
    val store = FakePreferencesDataStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val vault = KeyVault(store, cipher, scope)
    fun close() = scope.cancel()
}

/** PLAN.md §2.5: encrypted-at-rest secrets, defensive reads, names-only cache. */
class KeyVaultTest {

    @Test
    fun `put then get returns the secret`() = runTest {
        val f = Fixture()
        try {
            f.vault.put("GEMINI", "probe-secret-123")
            assertEquals("probe-secret-123", f.vault.get("GEMINI"))
        } finally {
            f.close()
        }
    }

    @Test
    fun `the stored value is ciphertext, never the secret itself`() = runTest {
        val f = Fixture()
        try {
            f.vault.put("GEMINI", "probe-secret-123")
            val raw = f.store.data.first()[stringPreferencesKey("vault_GEMINI")]
            assertTrue(raw != null)
            assertFalse(raw!!.contains("probe-secret-123"))
            assertFalse(raw.contains("probe"))
        } finally {
            f.close()
        }
    }

    @Test
    fun `missing key reads as null`() = runTest {
        val f = Fixture()
        try {
            assertNull(f.vault.get("DEEPL"))
            assertFalse(f.vault.isPresent("DEEPL"))
        } finally {
            f.close()
        }
    }

    @Test
    fun `presence cache tracks put and remove`() = runTest {
        val f = Fixture()
        try {
            f.vault.put("AZURE", "k|r")
            assertTrue(f.vault.isPresent("AZURE"))
            assertTrue("AZURE" in f.vault.presentKeys.value)
            f.vault.remove("AZURE")
            assertFalse(f.vault.isPresent("AZURE"))
            assertNull(f.vault.get("AZURE"))
            assertFalse("AZURE" in f.vault.presentKeys.value)
        } finally {
            f.close()
        }
    }

    @Test
    fun `a corrupt stored entry reads as no key instead of crashing`() = runTest {
        val f = Fixture()
        try {
            f.store.edit { it[stringPreferencesKey("vault_GEMINI")] = "!!! not base64 !!!" }
            assertNull(f.vault.get("GEMINI"))
        } finally {
            f.close()
        }
    }

    @Test
    fun `an entry written by a different cipher reads as no key`() = runTest {
        val store = FakePreferencesDataStore()
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            KeyVault(store, PrefixCipher(magic = 0x2A), scopeA).put("GEMINI", "s3cret")
            val otherVault = KeyVault(store, PrefixCipher(magic = 0x11), scopeB)
            assertNull(otherVault.get("GEMINI"))
        } finally {
            scopeA.cancel()
            scopeB.cancel()
        }
    }

    @Test
    fun `entries are independent per provider`() = runTest {
        val f = Fixture()
        try {
            f.vault.put("GEMINI", "g-key")
            f.vault.put("DEEPL", "d-key")
            assertEquals("g-key", f.vault.get("GEMINI"))
            assertEquals("d-key", f.vault.get("DEEPL"))
            f.vault.remove("GEMINI")
            assertEquals("d-key", f.vault.get("DEEPL"))
        } finally {
            f.close()
        }
    }

    @Test
    fun `a fresh vault over the same store sees the persisted keys`() = runTest {
        val f = Fixture()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            f.vault.put("GEMINI", "persisted")
            val reopened = KeyVault(f.store, PrefixCipher(), scope)
            // Unconfined collection has already delivered the stored names.
            assertTrue(reopened.isPresent("GEMINI"))
            assertEquals("persisted", reopened.get("GEMINI"))
        } finally {
            scope.cancel()
            f.close()
        }
    }
}
