package app.retranslate.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The app's light/dark choice (§9.3 Appearance). */
enum class ThemeMode { FOLLOW_SYSTEM, LIGHT, DARK }

/**
 * The whole Appearance state (PLAN.md §9.3/§9.5, completed in P7):
 * theme mode, Material You, the review screen's own theme + text
 * size, and the "Bouncy animations" motion switch (§9.4). One owner
 * (the `settings` DataStore, §3.5); MainActivity and the review
 * screen merely reflect it.
 */
data class AppearanceState(
    val themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    // v1.1: the Fluid Glass scheme is the default look; Material You
    // dynamic colour is opt-in (Appearance toggle), not the default.
    val dynamicColor: Boolean = false,
    /** [app.retranslate.ui.theme.ReviewTheme] id. */
    val reviewThemeId: String = "LIGHT",
    /** Review body text scale: 0.85–1.4, stepped from the Aa sheet. */
    val reviewTextScale: Float = 1.0f,
    val bouncyAnimations: Boolean = true
)

class AppearancePrefs(private val store: DataStore<Preferences>) {

    val state: Flow<AppearanceState> = store.data.map { prefs ->
        AppearanceState(
            themeMode = ThemeMode.entries.firstOrNull {
                it.name == prefs[KEY_THEME_MODE]
            } ?: ThemeMode.FOLLOW_SYSTEM,
            dynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: false,
            reviewThemeId = prefs[KEY_REVIEW_THEME] ?: "LIGHT",
            reviewTextScale = (prefs[KEY_REVIEW_TEXT_SCALE] ?: 1.0f)
                .coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE),
            bouncyAnimations = prefs[KEY_BOUNCY] ?: true
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(value: Boolean) {
        store.edit { it[KEY_DYNAMIC_COLOR] = value }
    }

    suspend fun setReviewTheme(id: String) {
        store.edit { it[KEY_REVIEW_THEME] = id }
    }

    suspend fun setReviewTextScale(value: Float) {
        store.edit {
            it[KEY_REVIEW_TEXT_SCALE] = value.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)
        }
    }

    suspend fun setBouncyAnimations(value: Boolean) {
        store.edit { it[KEY_BOUNCY] = value }
    }

    companion object {
        const val MIN_TEXT_SCALE = 0.85f
        const val MAX_TEXT_SCALE = 1.4f
        private val KEY_THEME_MODE = stringPreferencesKey("appearance_theme_mode")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("appearance_dynamic_color")
        private val KEY_REVIEW_THEME = stringPreferencesKey("appearance_review_theme")
        private val KEY_REVIEW_TEXT_SCALE = floatPreferencesKey("appearance_review_text_scale")
        private val KEY_BOUNCY = booleanPreferencesKey("appearance_bouncy_animations")
    }
}
