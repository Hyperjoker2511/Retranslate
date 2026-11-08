package app.retranslate.core.motion

import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/**
 * The whole motion system in one file (PLAN.md §9.4) so every screen
 * animates identically. Spring/tween values are the user's prompt §5 specs,
 * verbatim:
 *  - press feedback: scale to [PRESS_SCALE] (0.97) with
 *    spring(StiffnessMediumLow, DampingRatioLowBouncy)
 *  - progress: tween(300, FastOutSlowInEasing)
 *  - screen transitions: Shared Axis X (forward/back), sheets: Shared Axis Y
 *  - text swaps: crossfade (in the screens, via AnimatedContent + the fades here)
 *
 * Reduce motion: when the system animator scale is 0 — or the user turns off
 * "Bouncy animations" (§9.3 Appearance) — everything here degrades to plain
 * fades / no movement. One decision point: [animationsEnabled].
 */
object MotionTokens {

    const val PRESS_SCALE = 0.97f
    const val PROGRESS_TWEEN_MS = 300

    /** The prompt's exact press spring. */
    fun <T> pressSpring() = spring<T>(
        stiffness = Spring.StiffnessMediumLow,
        dampingRatio = Spring.DampingRatioLowBouncy
    )

    /** Spatial spring for navigation (Shared Axis) and list placement. */
    fun <T> spatialSpring() = spring<T>(
        stiffness = Spring.StiffnessMediumLow,
        dampingRatio = Spring.DampingRatioMediumBouncy
    )

    /** The prompt's exact progress curve. */
    fun <T> progressTween() = tween<T>(
        durationMillis = PROGRESS_TWEEN_MS,
        easing = FastOutSlowInEasing
    )

    /** The prompt's fade curve for text crossfades. */
    fun <T> fadeTween() = tween<T>(
        durationMillis = PROGRESS_TWEEN_MS,
        easing = FastOutSlowInEasing
    )

    /** The single reduce-motion decision (pure — unit-tested). */
    fun animationsEnabled(systemAnimatorScale: Float, userEnabled: Boolean): Boolean =
        userEnabled && systemAnimatorScale > 0f

    // --- Shared Axis X (PLAN §9.4): forward navigation slides + fades along X.

    fun sharedAxisXEnter(reduceMotion: Boolean): EnterTransition =
        if (reduceMotion) fadeIn(fadeTween())
        else slideInHorizontally(spatialSpring()) { it / 4 } + fadeIn(fadeTween())

    fun sharedAxisXExit(reduceMotion: Boolean): ExitTransition =
        if (reduceMotion) fadeOut(fadeTween())
        else slideOutHorizontally(spatialSpring()) { -it / 4 } + fadeOut(fadeTween())

    fun sharedAxisXPopEnter(reduceMotion: Boolean): EnterTransition =
        if (reduceMotion) fadeIn(fadeTween())
        else slideInHorizontally(spatialSpring()) { -it / 4 } + fadeIn(fadeTween())

    fun sharedAxisXPopExit(reduceMotion: Boolean): ExitTransition =
        if (reduceMotion) fadeOut(fadeTween())
        else slideOutHorizontally(spatialSpring()) { it / 4 } + fadeOut(fadeTween())
}

/**
 * True when motion should play: system animator scale > 0 AND the user's
 * "Bouncy animations" setting on. P0 wires the system half; the setting joins
 * in P7's Appearance completion — the parameter is already here so call
 * sites never change.
 */
@Composable
fun rememberMotionEnabled(userEnabled: Boolean = true): Boolean {
    val context = LocalContext.current
    val systemScale = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        }.getOrDefault(1f)
    }
    return MotionTokens.animationsEnabled(systemScale, userEnabled)
}

/**
 * The shared press feedback (PLAN §9.4): press down → scale to 0.97 with the
 * prompt's spring; release → spring back. Apply to every clickable card /
 * button that also passes its own [interactionSource] to the clickable.
 */
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
    pressedScale: Float = MotionTokens.PRESS_SCALE
): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> pressed = true
                is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
            }
        }
    }
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = MotionTokens.pressSpring(),
        label = "pressScale"
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
