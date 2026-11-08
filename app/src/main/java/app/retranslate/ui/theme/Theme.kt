package app.retranslate.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightScheme = lightColorScheme(
    primary = GlassPrimaryLight,
    onPrimary = GlassOnPrimaryLight,
    primaryContainer = GlassPrimaryContainerLight,
    onPrimaryContainer = GlassOnPrimaryContainerLight,
    secondary = GlassSecondaryLight,
    onSecondary = GlassOnSecondaryLight,
    secondaryContainer = GlassSecondaryContainerLight,
    onSecondaryContainer = GlassOnSecondaryContainerLight,
    tertiary = GlassTertiaryLight,
    onTertiary = GlassOnTertiaryLight,
    tertiaryContainer = GlassTertiaryContainerLight,
    onTertiaryContainer = GlassOnTertiaryContainerLight,
    error = GlassErrorLight,
    onError = GlassOnErrorLight,
    errorContainer = GlassErrorContainerLight,
    onErrorContainer = GlassOnErrorContainerLight,
    background = GlassBackgroundLight,
    onBackground = GlassOnBackgroundLight,
    surface = GlassSurfaceLight,
    onSurface = GlassOnSurfaceLight,
    surfaceVariant = GlassSurfaceVariantLight,
    onSurfaceVariant = GlassOnSurfaceVariantLight,
    outline = GlassOutlineLight,
    outlineVariant = GlassOutlineVariantLight,
    surfaceContainerLowest = GlassSurfaceContainerLowestLight,
    surfaceContainerLow = GlassSurfaceContainerLowLight,
    surfaceContainer = GlassSurfaceContainerLight,
    surfaceContainerHigh = GlassSurfaceContainerHighLight,
    surfaceContainerHighest = GlassSurfaceContainerHighestLight
)

private val DarkScheme = darkColorScheme(
    primary = GlassPrimaryDark,
    onPrimary = GlassOnPrimaryDark,
    primaryContainer = GlassPrimaryContainerDark,
    onPrimaryContainer = GlassOnPrimaryContainerDark,
    secondary = GlassSecondaryDark,
    onSecondary = GlassOnSecondaryDark,
    secondaryContainer = GlassSecondaryContainerDark,
    onSecondaryContainer = GlassOnSecondaryContainerDark,
    tertiary = GlassTertiaryDark,
    onTertiary = GlassOnTertiaryDark,
    tertiaryContainer = GlassTertiaryContainerDark,
    onTertiaryContainer = GlassOnTertiaryContainerDark,
    error = GlassErrorDark,
    onError = GlassOnErrorDark,
    errorContainer = GlassErrorContainerDark,
    onErrorContainer = GlassOnErrorContainerDark,
    background = GlassBackgroundDark,
    onBackground = GlassOnBackgroundDark,
    surface = GlassSurfaceDark,
    onSurface = GlassOnSurfaceDark,
    surfaceVariant = GlassSurfaceVariantDark,
    onSurfaceVariant = GlassOnSurfaceVariantDark,
    outline = GlassOutlineDark,
    outlineVariant = GlassOutlineVariantDark,
    surfaceContainerLowest = GlassSurfaceContainerLowestDark,
    surfaceContainerLow = GlassSurfaceContainerLowDark,
    surfaceContainer = GlassSurfaceContainerDark,
    surfaceContainerHigh = GlassSurfaceContainerHighDark,
    surfaceContainerHighest = GlassSurfaceContainerHighestDark
)

/**
 * Fluid Glass shapes (v1.1.2, from the design HTML): the designs round
 * cards at `rounded-3xl` (1.5–1.75rem = 24–28dp), buttons/pills at
 * `rounded-2xl`/`rounded-full`, tiles at `rounded-2xl` (16dp). So:
 * medium (the Card default) = 24dp, large = 28dp, extraLarge = 32dp.
 */
val ReTranslateShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

/**
 * Material 3 theme (PLAN.md §9.5 + v1.1 "Fluid Glass"): the bundled
 * azure/mint/amber scheme is the app's identity and the DEFAULT look;
 * Material You dynamic colour is an opt-in in Appearance (it replaces
 * the scheme on Android 12+ only when the user turns it on). Dark is a
 * deep ink-navy adaptation of the same accents.
 */
@Composable
fun ReTranslateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ReTranslateTypography,
        shapes = ReTranslateShapes,
        content = content
    )
}
