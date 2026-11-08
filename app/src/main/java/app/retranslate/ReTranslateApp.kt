package app.retranslate

import android.app.Application
import app.retranslate.core.error.CrashGuard
import app.retranslate.di.AppContainer

/**
 * Application entry point (PLAN.md §3.1): installs the crash guard first
 * (§10 row 16), then builds the single [AppContainer] (§2.7 manual DI).
 */
class ReTranslateApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
        container = AppContainer(this)
    }
}
