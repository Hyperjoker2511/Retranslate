package app.retranslate.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.retranslate.core.motion.MotionTokens
import app.retranslate.core.motion.pressScale
import app.retranslate.core.motion.rememberMotionEnabled
import app.retranslate.ui.theme.StitchAzure

/**
 * The app's one empty-state shape (PLAN.md §9.1 rule 7): a few plain lines
 * and ALWAYS the action that fixes the emptiness — never a dead grey page.
 * (A dead-end empty state is the exact failure the old app shipped.)
 */
@Composable
fun EmptyState(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                BounceButton(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) {
                    Text(actionLabel)
                }
            }
        }
    }
}

/**
 * A filled primary button with the §9.4 press-scale feedback (0.97, the
 * prompt's spring). All primary actions in the app are this shape: big,
 * visible, plain-word labelled (§9.1 rule 4).
 */
@Composable
fun BounceButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val motionOn = rememberMotionEnabled()
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        // v1.1 (Fluid Glass): fully rounded pill, like the designs.
        shape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50),
        interactionSource = interaction,
        modifier = modifier.pressScale(interaction, enabled = enabled && motionOn)
    ) { content() }
}

/**
 * v1.1 (small-problems sweep): a NON-interactive label shaped like a
 * chip. Status readouts (quality score, "Locked", "Detected: …") must
 * never be clickable chips with empty handlers — a normal person taps
 * those and gets nothing. If it looks tappable, it must do something;
 * if it only informs, it looks like this.
 */
@Composable
fun InfoChip(text: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50),
        color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/**
 * The tonal sibling of [BounceButton] (§9.4 press feedback), for big
 * secondary actions (Translate's "Large text / chapter", exports).
 * v1.1: pill shape, matching the Fluid Glass designs' soft buttons.
 */
@Composable
fun BounceTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val motionOn = rememberMotionEnabled()
    val interaction = remember { MutableInteractionSource() }
    androidx.compose.material3.FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50),
        interactionSource = interaction,
        modifier = modifier.pressScale(interaction, enabled = enabled && motionOn)
    ) { content() }
}

/**
 * v1.1: press-scale + click for plain rows/cards that are not
 * [BounceCard] (chapter rows, picker rows). No ripple — the scale IS
 * the feedback, exactly like the buttons.
 */
@Composable
fun Modifier.bounceClickable(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    val motionOn = rememberMotionEnabled()
    val interaction = remember { MutableInteractionSource() }
    return this
        .pressScale(interaction, enabled = enabled && motionOn)
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

/**
 * An [ElevatedCard] with the same §9.4 press feedback, for tappable rows.
 * v1.1: optional long-press (the Library shelf's "remove book" path) —
 * via combinedClickable, so press-scale and click keep working.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun BounceCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val motionOn = rememberMotionEnabled()
    val interaction = remember { MutableInteractionSource() }
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interaction, enabled = motionOn)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Box(modifier = Modifier.padding(16.dp)) { content() }
    }
}

// =====================================================================
// v1.1.2 — Stitch "Fluid Glass" component kit.
// Every number here is taken from the design HTML in
// ~/workspace/fresh-start/design-v1.1/ (the ground truth): headers are
// px-6 py-4 (24dp × 16dp) under the status bar; cards are rounded-3xl
// (24–28dp) frosted surfaces (bg-white/70 light; the same surface
// lifted on the deep-navy background in dark) with a hairline border
// and p-5/p-6 (20/24dp) padding; the primary button is py-4 (16dp)
// rounded-2xl azure #2F6BFF with a soft blue shadow feel.
// =====================================================================

/**
 * The designs' compact header (translate/jobs/more HTML: `px-6 py-4`,
 * headline text-2xl bold tracking-tight). This REPLACES the tall
 * LargeTopAppBar band that left the big empty gap above the title in
 * v1.1 (the user's "space above in top"): total height is the status
 * bar inset + ~56dp of content, nothing more.
 */
@Composable
fun StitchHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    subtitle: String? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        if (eyebrow != null) {
            Text(
                text = eyebrow,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                ),
                color = StitchAzure
            )
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * The designs' frosted card: a surface visibly lifted from the
 * background (white on the porcelain #F8FAFC light background;
 * #101A2C on the #0A111F deep-navy dark background), a hairline
 * outline, and rounded-3xl corners. [onClick] null = a plain
 * container; non-null = the §9.4 press-scale tap feedback.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(20.dp),
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    val colors = MaterialTheme.colorScheme
    var boxModifier = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(colors.surface)
        .border(1.dp, colors.outlineVariant, shape)
    if (onClick != null) {
        val motionOn = rememberMotionEnabled()
        val interaction = remember { MutableInteractionSource() }
        boxModifier = boxModifier
            .pressScale(interaction, enabled = motionOn)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    }
    Box(modifier = boxModifier.padding(contentPadding)) { content() }
}

/**
 * The designs' primary action: azure #2F6BFF in BOTH themes (the
 * design's hue, not the dark scheme's pale primary), white text,
 * rounded-2xl (16dp), 56dp tall, Jakarta bold. Press-scale included.
 */
@Composable
fun StitchPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val motionOn = rememberMotionEnabled()
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = StitchAzure,
            contentColor = Color.White,
            disabledContainerColor = StitchAzure.copy(alpha = 0.45f),
            disabledContentColor = Color.White.copy(alpha = 0.8f)
        ),
        interactionSource = interaction,
        modifier = modifier
            .height(56.dp)
            .pressScale(interaction, enabled = enabled && motionOn)
    ) { content() }
}

/** The designs' status pill: tinted container, bold 12sp label, optional dot. */
@Composable
fun StatusPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    showDot: Boolean = true
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (showDot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(contentColor)
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = contentColor
        )
    }
}

/**
 * The designs' progress bar (jobs HTML: h-2.5 = 10dp, rounded-full,
 * tinted fill on a slate track; library bars are the same bar at 6dp).
 * The fill tweens with the app's progress motion token.
 */
@Composable
fun ThickProgress(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 10.dp
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = MotionTokens.progressTween(),
        label = "thickProgress"
    )
    LinearProgressIndicator(
        progress = { animated },
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(percent = 50))
    )
}

/** The designs' tinted icon tile (jobs/more HTML: 48dp, rounded-2xl, icon tinted to match). */
@Composable
fun IconTile(
    containerColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor),
        contentAlignment = Alignment.Center
    ) { content() }
}
