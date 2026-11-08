package app.retranslate.domain.export

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.ChapterEntity
import app.retranslate.data.db.KbEntryEntity
import app.retranslate.data.db.KnowledgeBaseEntity
import app.retranslate.domain.books.DirectTxRunner
import app.retranslate.domain.books.FakeBookDao
import app.retranslate.domain.books.FakeChapterDao
import app.retranslate.domain.books.FakeKnowledgeBaseDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** PLAN.md §9.3/§12 flow 20: backup → restore onto a fresh install. */
class BackupManagerTest {

    private class FakeSettings : SettingsBackupSource {
        var settings = mutableMapOf<String, BackupSettingValue>()
        var instances = mutableMapOf<String, List<String>>()
        override suspend fun snapshotSettings(): Map<String, BackupSettingValue> =
            settings.toMap()
        override suspend fun applySettings(values: Map<String, BackupSettingValue>) {
            settings = values.toMutableMap()
        }
        override suspend fun snapshotProviderInstances(): Map<String, List<String>> =
            instances.toMap()
        override suspend fun applyProviderInstances(new: Map<String, List<String>>) {
            instances = new.toMutableMap()
        }
    }

    private class World {
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        val kbDao = FakeKnowledgeBaseDao()
        val settings = FakeSettings()
        val manager = BackupManager(bookDao, chapterDao, kbDao, DirectTxRunner, settings)
    }

    private suspend fun seed(world: World): Long {
        val bookId = world.bookDao.insert(
            BookEntity(
                titleOriginal = "원본", titleTranslated = "Road Home", author = "Kim",
                sourceLang = "ko", targetLang = "en", qualityMode = "QUALITY",
                sourceFileHash = "hash-1",
                createdAt = 10, updatedAt = 20
            )
        )
        world.chapterDao.insert(
            ChapterEntity(
                bookId = bookId, number = 1, title = "Chapter 1",
                sourceText = "본문", translatedText = "The road home.",
                status = "TRANSLATED", qualityScore = 88f, kbVersionUsed = 2,
                updatedAt = 15
            )
        )
        world.chapterDao.insert(
            ChapterEntity(
                bookId = bookId, number = 2, sourceText = "둘째",
                status = "PENDING", updatedAt = 15
            )
        )
        val kbId = world.kbDao.insertKb(
            KnowledgeBaseEntity(bookId = bookId, version = 2, createdAt = 12)
        )
        world.kbDao.insertEntries(
            listOf(
                KbEntryEntity(
                    kbId = kbId, category = "NAME", sourceForm = "진우",
                    canonicalForm = "Jin Woo",
                    gender = "MALE", locked = true, frequency = 40
                ),
                KbEntryEntity(
                    kbId = kbId, category = "TONE_RULE",
                    canonicalForm = "Keep sentences flowing."
                )
            )
        )
        world.settings.settings["theme_mode"] = BackupSettingValue("S", "DARK")
        world.settings.settings["pacer_min_gap_ms"] = BackupSettingValue("L", "900")
        world.settings.instances["MOZHI"] = listOf("https://mozhi.example")
        return bookId
    }

    private suspend fun backupBytes(world: World): ByteArray {
        val out = ByteArrayOutputStream()
        when (val r = world.manager.writeBackup(out) {}) {
            is AppResult.Ok -> Unit
            is AppResult.Err -> fail("backup failed: ${r.error}")
        }
        return out.toByteArray()
    }

    @Test
    fun `round trip restores books, chapters, KB and settings`() = runTest {
        val source = World()
        seed(source)
        val bytes = backupBytes(source)

        val target = World()
        val restored = when (val r = target.manager.restoreBackup { ByteArrayInputStream(bytes) }) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> fail("restore failed: ${r.error}").let { error("x") }
        }
        assertEquals(1, restored.restoredBooks)
        assertEquals(2, restored.restoredChapters)

        val book = target.bookDao.books.value.single()
        assertEquals("Road Home", book.titleTranslated)
        assertEquals("QUALITY", book.qualityMode)
        val chapters = target.chapterDao.chapters.sortedBy { it.number }
        assertEquals(2, chapters.size)
        assertEquals("The road home.", chapters[0].translatedText)
        assertEquals(88f, chapters[0].qualityScore!!, 0.001f)
        assertEquals(2, chapters[0].kbVersionUsed)

        val kbs = target.kbDao.getKbsForBook(book.id)
        assertEquals(1, kbs.size)
        assertEquals(2, kbs[0].version)
        val entries = target.kbDao.getEntries(kbs[0].id)
        assertEquals(2, entries.size)
        val name = entries.first { it.category == "NAME" }
        assertEquals("Jin Woo", name.canonicalForm)
        assertEquals("MALE", name.gender)
        assertTrue(name.locked)
        assertEquals(40, name.frequency)

        assertEquals(BackupSettingValue("S", "DARK"), target.settings.settings["theme_mode"])
        assertEquals(
            listOf("https://mozhi.example"),
            target.settings.instances["MOZHI"]
        )
    }

    @Test
    fun `restoring the same backup twice skips the duplicate book`() = runTest {
        val source = World()
        seed(source)
        val bytes = backupBytes(source)
        val target = World()
        target.manager.restoreBackup { ByteArrayInputStream(bytes) }
        val second = when (val r = target.manager.restoreBackup { ByteArrayInputStream(bytes) }) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> fail("second restore failed: ${r.error}").let { error("x") }
        }
        assertEquals(0, second.restoredBooks)
        assertEquals(1, second.skippedBooks)
        assertEquals(1, target.bookDao.books.value.size)
    }

    @Test
    fun `garbage and newer-format files are honest BadFile errors`() = runTest {
        val world = World()
        val garbage = world.manager.restoreBackup { ByteArrayInputStream("not a backup".toByteArray()) }
        assertTrue((garbage as AppResult.Err).error is AppError.BadFile)

        val newer = (
            "{\"type\":\"header\",\"header\":{\"formatVersion\":99," +
                "\"createdAt\":1}}\n"
            ).toByteArray()
        val future = world.manager.restoreBackup { ByteArrayInputStream(newer) }
        val err = (future as AppResult.Err).error as AppError.BadFile
        assertTrue(err.reason.contains("newer"))
        assertEquals(0, world.bookDao.books.value.size)
    }

    @Test
    fun `a stream cut mid-book keeps finished books and drops the partial one`() = runTest {
        val source = World()
        seed(source)
        // A second, complete book after the first.
        source.bookDao.insert(
            BookEntity(
                titleOriginal = "둘째 책", sourceLang = "ko", targetLang = "en",
                sourceFileHash = "hash-2", createdAt = 30, updatedAt = 30
            )
        )
        source.chapterDao.insert(
            ChapterEntity(bookId = 2, number = 1, sourceText = "본문2", updatedAt = 30)
        )
        val bytes = backupBytes(source)
        // Cut the stream inside the LAST book's records.
        val text = bytes.toString(Charsets.UTF_8)
        val secondBookAt = text.indexOf("hash-2")
        assertTrue(secondBookAt > 0)
        val truncated = text.substring(0, secondBookAt + 10).toByteArray()

        val target = World()
        val result = target.manager.restoreBackup { ByteArrayInputStream(truncated) }
        assertTrue("expected Err, got $result", result is AppResult.Err)
        // The first book is complete; the truncated one was rolled back.
        assertEquals(1, target.bookDao.books.value.size)
        assertEquals("Road Home", target.bookDao.books.value[0].titleTranslated)
    }

    @Test
    fun `backup bytes carry no key material by construction`() = runTest {
        val world = World()
        seed(world)
        val text = backupBytes(world).toString(Charsets.UTF_8)
        assertFalse(text.contains("apiKey"))
        assertFalse(text.contains("keyVault"))
        val kinds = text.lineSequence().filter { it.isNotBlank() }
            .map { Regex("\"type\":\"(\\w+)\"").find(it)?.groupValues?.get(1) }
            .toSet()
        assertEquals(
            setOf("header", "settings", "book", "chapter", "kb", "kbEntry", "bookEnd"),
            kinds
        )
    }

    @Test
    fun `empty library still backs up and restores settings`() = runTest {
        val source = World()
        source.settings.settings["target_lang"] = BackupSettingValue("S", "en")
        val bytes = backupBytes(source)
        val target = World()
        val restored = when (val r = target.manager.restoreBackup { ByteArrayInputStream(bytes) }) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> fail("restore failed: ${r.error}").let { error("x") }
        }
        assertEquals(0, restored.restoredBooks)
        assertEquals(BackupSettingValue("S", "en"), target.settings.settings["target_lang"])
    }
}
