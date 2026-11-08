package app.retranslate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retranslate.data.prefs.AppearanceState
import app.retranslate.data.prefs.ThemeMode
import app.retranslate.ui.shell.MainScaffold
import app.retranslate.ui.theme.ReTranslateTheme

/** The single activity; everything else is Compose (PLAN.md §3.1). */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ReTranslateApp).container
        setContent {
            // §9.5 (P7): Appearance is real state now — theme mode and
            // the Material You toggle drive the app theme live.
            val appearance by container.appearancePrefs.state
                .collectAsStateWithLifecycle(initialValue = AppearanceState())
            val darkTheme = when (appearance.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
            }
            ReTranslateTheme(
                darkTheme = darkTheme,
                dynamicColor = appearance.dynamicColor
            ) {
                MainScaffold(container = container)
            }
        }
    }
}
