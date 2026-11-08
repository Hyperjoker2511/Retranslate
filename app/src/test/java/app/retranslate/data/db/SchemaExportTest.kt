package app.retranslate.data.db

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Schema v1 export (PLAN.md §8.3): the exported schema JSON is checked
 * into the project and asserted here — all nine §8.1 tables exist at
 * version 1, so future phases migrate from a known shape.
 * (Room's SQL itself needs an Android runtime to execute; DAO
 * behaviour is covered at the repository layer with fake DAOs.)
 */
class SchemaExportTest {

    @Test
    fun `schema v1 contains every section-8 table`() {
        val schemaFile = locateSchema()
        val json = schemaFile.readText()
        assertTrue(json.contains("\"version\": 1"))
        for (table in listOf(
            "books", "chapters", "knowledge_bases", "kb_entries",
            "sample_sets", "sample_chapters", "cached_translations",
            "provider_languages", "job_runs"
        )) {
            assertTrue("missing table $table", json.contains("\"tableName\": \"$table\""))
        }
        // The v1.2-bug-class guard: chapter text columns are nullable.
        assertTrue(json.contains("\"columnName\": \"sourceText\""))
        assertTrue(json.contains("\"columnName\": \"translatedText\""))
    }

    private fun locateSchema(): File {
        val relative = "schemas/app.retranslate.data.db.ReTranslateDb/1.json"
        val candidates = listOf(
            File(relative),
            File("app/$relative"),
            File(System.getProperty("user.dir"), relative)
        )
        val found = candidates.firstOrNull { it.isFile }
        if (found == null) {
            throw AssertionError("schema export not found (user.dir=${System.getProperty("user.dir")})")
        }
        return found
    }
}
