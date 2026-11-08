package app.retranslate.core.error

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last line of defence (PLAN.md §10 row 16): if anything still manages to
 * throw past every boundary, the crash is recorded — timestamp, thread, error
 * class only; NEVER content, NEVER keys — to a local note the user can share,
 * and the previous default handler still runs (the app restarts into Library;
 * state is re-derivable from Room, so nothing is lost).
 *
 * [formatNote] is pure and unit-tested; the handler install is a thin shell.
 */
object CrashGuard {

    private const val TAG = "CrashGuard"
    private const val NOTES_DIR = "crash-notes"
    private const val MAX_NOTES = 5

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeNote(appContext, thread.name, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** The shareable crash note: when, which thread, which error CLASS. Nothing else. */
    fun formatNote(timestampMillis: Long, threadName: String, throwable: Throwable): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMillis))
        return "ReTranslate crash note\n" +
            "When: $stamp\n" +
            "Thread: $threadName\n" +
            "Error: ${throwable.javaClass.name}\n"
    }

    private fun writeNote(context: Context, threadName: String, throwable: Throwable) {
        val dir = File(context.filesDir, NOTES_DIR).apply { mkdirs() }
        val file = File(dir, "crash-${System.currentTimeMillis()}.txt")
        file.writeText(formatNote(System.currentTimeMillis(), threadName, throwable))
        // Keep only the most recent few notes.
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_NOTES)
            ?.forEach { it.delete() }
        Log.e(TAG, "Crash note written: ${file.name} (${throwable.javaClass.simpleName})")
    }
}
