package app.retranslate.core.error

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The crash note (§10 row 16) carries class + time + thread — never content. */
class CrashGuardTest {

    @Test
    fun `note contains timestamp thread and error class`() {
        val note = CrashGuard.formatNote(0L, "main", IllegalStateException("secret text"))
        assertTrue(note.contains("main"))
        assertTrue(note.contains("java.lang.IllegalStateException"))
    }

    @Test
    fun `note never contains the exception message`() {
        val note = CrashGuard.formatNote(0L, "main", IllegalStateException("secret text"))
        assertFalse(note.contains("secret text"))
    }
}
