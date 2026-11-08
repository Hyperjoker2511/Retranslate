package app.retranslate.data.files

import java.io.File

/**
 * App-private per-book storage (PLAN.md §8.2): `filesDir/books/<bookId>/`.
 * Covers and sample files live here; chapter text lives in Room (§8.2).
 * Pure [File] logic — JVM-testable, no Android types.
 */
class BookFiles(private val booksRoot: File) {

    fun bookDir(bookId: Long): File = File(booksRoot, bookId.toString())

    fun ensureBookDir(bookId: Long): File = bookDir(bookId).apply { mkdirs() }

    /** Cover image (extension preserved from the source file, e.g. `.jpg`). */
    fun coverFile(bookId: Long, extension: String): File =
        File(ensureBookDir(bookId), "cover$extension")

    fun findCover(bookId: Long): File? =
        bookDir(bookId).listFiles()?.firstOrNull { it.name.startsWith("cover.") && it.isFile }

    fun samplesDir(bookId: Long): File = File(ensureBookDir(bookId), "samples").apply { mkdirs() }

    /** Deleting a book deletes its files in the same flow (§8.2). */
    fun deleteBook(bookId: Long) {
        bookDir(bookId).deleteRecursively()
    }

    companion object {
        fun rootOf(filesDir: File): File = File(filesDir, "books")
    }
}

/**
 * Temporary working files (PLAN.md §7.1/§7.5): import staging, per-chapter
 * parse output, export staging. Everything lives under one cache root so
 * the maintenance pass can sweep it; import sessions clean up after
 * themselves, and [cleanupStale] removes anything a crash left behind
 * (temp files older than 7 days, §7.5).
 */
class TempFiles(private val tempRoot: File) {

    /** A fresh, empty directory for one import session. */
    fun newImportDir(): File {
        tempRoot.mkdirs()
        val dir = File(tempRoot, "import-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    fun newExportDir(): File {
        tempRoot.mkdirs()
        val dir = File(tempRoot, "export-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    fun cleanup(dir: File) {
        if (dir == tempRoot) return // never sweep the root itself by accident
        dir.deleteRecursively()
    }

    /** Deletes temp entries whose newest file is older than [maxAgeMs]. */
    fun cleanupStale(nowMs: Long, maxAgeMs: Long = SEVEN_DAYS_MS) {
        val children = tempRoot.listFiles() ?: return
        for (child in children) {
            val newest = newestModified(child)
            if (newest != null && nowMs - newest > maxAgeMs) {
                child.deleteRecursively()
            }
        }
    }

    private fun newestModified(file: File): Long? {
        if (file.isFile) return file.lastModified()
        var newest: Long? = null
        file.walkTopDown().forEach { f ->
            if (f.isFile) {
                val m = f.lastModified()
                if (newest == null || m > newest!!) newest = m
            }
        }
        return newest
    }

    companion object {
        const val SEVEN_DAYS_MS: Long = 7L * 24 * 60 * 60 * 1000
        fun rootOf(cacheDir: File): File = File(cacheDir, "retranslate-temp")
    }
}
