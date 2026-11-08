package app.retranslate.domain.books

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.data.db.TxRunner
import app.retranslate.data.files.BookFiles
import app.retranslate.data.files.TempFiles
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset

/**
 * End-to-end import on the JVM (PLAN.md §12.1 integration): real
 * parsers + planner + repository against fake Room DAOs. Covers the
 * §7.1 contract: staging, detection, duplicates, batched commit,
 * origin/status assignment, failure cleanup.
 */
class BookImporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val directTx = DirectTxRunner

    private class Fixture(root: File) {
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        val repository = BookRepository(
            bookDao, chapterDao, FakeKnowledgeBaseDao(),
            DirectTxRunner,
            clock = { 1_000L }
        )
        val bookFiles = BookFiles(File(root, "books"))
        val tempFiles = TempFiles(File(root, "tmp"))
        val importer = BookImporter(tempFiles, repository, bookFiles)
    }

    private fun fixture(): Fixture = Fixture(temp.newFolder())

    private val koreanTxt = buildString {
        append("제1장 시작\n그는 천천히 눈을 떴다. 마을은 조용했다. 수련의 길은 멀고도 험했다.\n\n")
        append("제2장 여정\n그는 가방을 챙겼다. 그리고 마을을 떠났다. 어머니의 말을 떠올렸다.\n")
    }

    private val englishTxt = buildString {
        append("Chapter 1 The Start\nHe opened his eyes slowly and the village was quiet. " +
            "The road ahead was long and he was not afraid.\n\n")
        append("Chapter 2 The Road\nHe packed his bag and he left the village before dawn " +
            "and the wind was cold on his face.\n")
    }

    @Test
    fun `Korean TXT imports end-to-end as a source book`() = runTest {
        val f = fixture()
        val session = unwrap(
            f.importer.importFile(
                openStream = { koreanTxt.byteInputStream(Charset.forName("EUC-KR")) },
                displayName = "novel.txt",
                sizeBytes = -1
            ) {}
        )
        assertEquals(ImportFormat.TXT, session.format)
        assertEquals("ko", session.detectedLang)
        assertEquals(2, session.planned.chapters.size)
        assertNull(session.duplicate)

        val bookId = unwrap(
            f.importer.commit(
                session,
                ImportChoices(
                    title = "검의 길",
                    author = null,
                    sourceLang = "ko",
                    targetLang = "en",
                    genrePresetId = GenrePreset.CULTIVATION.id,
                    qualityMode = "BALANCED"
                )
            )
        )
        val book = f.bookDao.getById(bookId)!!
        assertEquals("검의 길", book.titleOriginal)
        assertEquals(2, book.chapterCountCache)
        assertTrue(book.sizeBytesCache > 0)
        assertNotNull(book.sourceFileHash)

        val chapters = f.chapterDao.getChapters(bookId)
        assertEquals(2, chapters.size)
        assertEquals("IMPORTED_SOURCE", chapters[0].origin)
        assertEquals("PENDING", chapters[0].status)
        assertTrue(chapters[0].sourceText!!.contains("눈을 떴다"))
        assertNull(chapters[0].translatedText)
        // Session temp dir cleaned after commit.
        assertTrue(!session.workDir.exists())
    }

    @Test
    fun `English book imports as an already-translated book`() = runTest {
        val f = fixture()
        val session = unwrap(
            f.importer.importFile(
                openStream = { englishTxt.byteInputStream(Charsets.UTF_8) },
                displayName = "story.txt",
                sizeBytes = englishTxt.length.toLong()
            ) {}
        )
        assertEquals("en", session.detectedLang)
        val bookId = unwrap(
            f.importer.commit(
                session,
                ImportChoices(
                    title = "Story",
                    author = "Anon",
                    sourceLang = "en",
                    targetLang = "en",
                    genrePresetId = GenrePreset.OTHER.id,
                    qualityMode = "FAST"
                )
            )
        )
        val chapters = f.chapterDao.getChapters(bookId)
        assertEquals("IMPORTED_TRANSLATION", chapters[0].origin)
        assertEquals("TRANSLATED", chapters[0].status)
        assertNull(chapters[0].sourceText)
        assertTrue(chapters[0].translatedText!!.contains("He opened his eyes"))
    }

    @Test
    fun `duplicate is detected by hash plus title`() = runTest {
        val f = fixture()
        val first = unwrap(
            f.importer.importFile(
                { koreanTxt.byteInputStream(Charset.forName("EUC-KR")) }, "novel.txt", -1
            ) {}
        )
        unwrap(
            f.importer.commit(
                first,
                ImportChoices("같은 책", null, "ko", "en", "", "BALANCED")
            )
        )
        val second = unwrap(
            f.importer.importFile(
                { koreanTxt.byteInputStream(Charset.forName("EUC-KR")) }, "novel.txt", -1
            ) {}
        )
        // The planned title falls back to the file name, so no duplicate
        // is flagged until the user picks the same title — the review
        // ViewModel rechecks on title change; check the rule directly:
        assertNull(second.duplicate)
        val dup = f.repository.findDuplicate(second.sha256, "같은 책")
        assertNotNull("same hash + same title must be a duplicate", dup)
        val notDup = f.repository.findDuplicate(second.sha256, "다른 제목")
        assertNull("same file, different title is a legitimate separate copy", notDup)
        f.importer.discard(second)
    }

    @Test
    fun `commit inserts chapters in batches of 50`() = runTest {
        val f = fixture()
        val manyChapters = buildString {
            for (i in 1..120) {
                append("Chapter $i\nThis is the text of chapter $i, long enough to matter. ")
                append("그는 앞으로 나아갔다. 바람이 불었다. 마을은 조용했다. 그는 검을 쥐었다.\n\n")
            }
        }
        val session = unwrap(
            f.importer.importFile(
                { manyChapters.byteInputStream(Charsets.UTF_8) }, "many.txt", -1
            ) {}
        )
        assertEquals(120, session.planned.chapters.size)
        val bookId = unwrap(
            f.importer.commit(
                session,
                ImportChoices("Many", null, "ko", "en", "", "BALANCED")
            )
        )
        assertEquals(listOf(50, 50, 20), f.chapterDao.insertBatchSizes)
        assertEquals(120, f.chapterDao.countForBook(bookId))
        val numbers = f.chapterDao.getChapters(bookId).map { it.number }
        assertEquals((1..120).toList(), numbers)
    }

    @Test
    fun `EPUB imports end-to-end with cover`() = runTest {
        val f = fixture()
        val epubFile = File(temp.root, "fixture.epub")
        // Chapter bodies exceed 300 chars so the planner's EPUB
        // merge-forward rule (title-page-sized items) doesn't fold them.
        val body1 = "그는 천천히 눈을 떴다. 마을은 조용했다. 수련의 길은 멀고도 험했지만 그는 결코 포기하지 않았다. " +
            "어머니의 마지막 말을 떠올리며 그는 검을 고쳐 쥐었다. 오늘도 그는 앞으로 나아간다. 바람이 불었다. "
        val body2 = "그는 가방을 챙겼다. 그리고 마을을 떠났다. 길은 멀고 험했지만 그의 마음은 단단했다. " +
            "마을 사람들은 그의 뒷모습을 바라보았다. 그는 뒤돌아보지 않았다. 새로운 삶이 그를 기다리고 있었다. "
        EpubFixtures.buildEpub(
            epubFile,
            chapters = listOf(
                EpubFixtures.chapter(1, "제1장", body1.repeat(4)),
                EpubFixtures.chapter(2, "제2장", body2.repeat(4))
            ),
            bookTitle = "픽스처 소설",
            author = "작가",
            language = "ko",
            withCover = true
        )
        val bytes = epubFile.readBytes()
        val session = unwrap(
            f.importer.importFile({ bytes.inputStream() }, "fixture.epub", bytes.size.toLong()) {}
        )
        assertEquals(ImportFormat.EPUB, session.format)
        assertEquals("ko", session.detectedLang)
        assertEquals("픽스처 소설", session.planned.title)
        val bookId = unwrap(
            f.importer.commit(
                session,
                ImportChoices("픽스처 소설", "작가", "ko", "en", "", "BALANCED")
            )
        )
        val book = f.bookDao.getById(bookId)!!
        assertNotNull(book.coverPath)
        assertTrue(File(book.coverPath!!).isFile)
        assertEquals(2, f.chapterDao.countForBook(bookId))
    }

    @Test
    fun `corrupt EPUB import fails plainly and leaves no book`() = runTest {
        val f = fixture()
        val junk = "definitely not an epub".toByteArray()
        when (
            val result = f.importer.importFile({ junk.inputStream() }, "book.epub", -1) {}
        ) {
            is AppResult.Ok -> fail("junk must not import")
            is AppResult.Err -> assertTrue(result.error is AppError.BadFile)
        }
        assertTrue(f.bookDao.books.value.isEmpty())
    }

    @Test
    fun `empty TXT is refused with no readable text`() = runTest {
        val f = fixture()
        when (
            val result = f.importer.importFile({ "".byteInputStream() }, "empty.txt", 0) {}
        ) {
            is AppResult.Ok -> fail("empty file must not import")
            is AppResult.Err -> assertTrue(result.error is AppError.BadFile)
        }
    }

    @Test
    fun `zip that is not an EPUB is refused`() = runTest {
        val f = fixture()
        val zipFile = File(temp.root, "plain.zip")
        java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("a.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
        }
        val bytes = zipFile.readBytes()
        when (
            val result = f.importer.importFile({ bytes.inputStream() }, "book.epub", -1) {}
        ) {
            is AppResult.Ok -> fail("plain zip must not import")
            is AppResult.Err -> assertTrue(result.error is AppError.BadFile)
        }
    }

    @Test
    fun `discarding a session cleans its temp dir`() = runTest {
        val f = fixture()
        val session = unwrap(
            f.importer.importFile(
                { koreanTxt.byteInputStream(Charset.forName("EUC-KR")) }, "novel.txt", -1
            ) {}
        )
        assertTrue(session.workDir.exists())
        f.importer.discard(session)
        assertTrue(!session.workDir.exists())
        assertTrue(f.bookDao.books.value.isEmpty())
    }
}
