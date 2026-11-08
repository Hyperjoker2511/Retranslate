package app.retranslate.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The review screen's own colour themes (PLAN.md §9.5): independent of
 * the app theme — a dark app can check chapters on Paper. Five named
 * themes, chosen in Appearance and in the review screen's Aa sheet.
 * Only the reading surface is themed (background, body text, muted
 * text, the system-window card tint); the app's chrome stays Material.
 */
data class ReviewTheme(
    val id: String,
    val displayName: String,
    val background: Color,
    val text: Color,
    val mutedText: Color,
    val systemWindowBackground: Color,
    val systemWindowText: Color
)

object ReviewThemes {

    val LIGHT = ReviewTheme(
        id = "LIGHT",
        displayName = "Light",
        background = Color(0xFFFFFFFF),
        text = Color(0xFF1A1C1C),
        mutedText = Color(0xFF5A6060),
        systemWindowBackground = Color(0xFFDCE9E7),
        systemWindowText = Color(0xFF123B39)
    )

    val PAPER = ReviewTheme(
        id = "PAPER",
        displayName = "Paper",
        background = Color(0xFFFBF7EE),
        text = Color(0xFF2B2620),
        mutedText = Color(0xFF7A7264),
        systemWindowBackground = Color(0xFFF0E6D2),
        systemWindowText = Color(0xFF4A3F2A)
    )

    val SEPIA = ReviewTheme(
        id = "SEPIA",
        displayName = "Sepia",
        background = Color(0xFFF3E3C3),
        text = Color(0xFF43331C),
        mutedText = Color(0xFF87744E),
        systemWindowBackground = Color(0xFFEAD9AE),
        systemWindowText = Color(0xFF57431F)
    )

    val NIGHT = ReviewTheme(
        id = "NIGHT",
        displayName = "Night",
        background = Color(0xFF1C2120),
        text = Color(0xFFD8DEDC),
        mutedText = Color(0xFF98A19E),
        systemWindowBackground = Color(0xFF2C3533),
        systemWindowText = Color(0xFFBFE8E4)
    )

    val TRUE_BLACK = ReviewTheme(
        id = "TRUE_BLACK",
        displayName = "True black",
        background = Color(0xFF000000),
        text = Color(0xFFC9CFCC),
        mutedText = Color(0xFF7E8581),
        systemWindowBackground = Color(0xFF16181A),
        systemWindowText = Color(0xFF9AD6D0)
    )

    val ALL: List<ReviewTheme> = listOf(LIGHT, PAPER, SEPIA, NIGHT, TRUE_BLACK)

    /** Defensive lookup (§10): an unknown stored id degrades to Light. */
    fun byId(id: String?): ReviewTheme = ALL.firstOrNull { it.id == id } ?: LIGHT
}
