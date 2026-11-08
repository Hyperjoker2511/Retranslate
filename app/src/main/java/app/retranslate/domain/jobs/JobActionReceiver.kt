package app.retranslate.domain.jobs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.retranslate.ReTranslateApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The notification's Pause action (PLAN.md §7.4). Pause is a state
 * write, not a work cancellation: the running engine notices the
 * PAUSED row at the next chunk boundary and stops with all progress
 * persisted (see [JobScheduler.pause]).
 */
class JobActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PAUSE) return
        val bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
        if (bookId <= 0) return
        val app = context.applicationContext as? ReTranslateApp ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.container.jobScheduler.pause(bookId)
            } catch (t: Throwable) {
                // The Jobs screen still shows the truth; a failed pause
                // write leaves the job running, never corrupted.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "app.retranslate.action.PAUSE_JOB"
        const val EXTRA_BOOK_ID = "bookId"
    }
}
