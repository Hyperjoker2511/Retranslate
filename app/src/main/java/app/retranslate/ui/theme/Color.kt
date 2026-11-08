package app.retranslate.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * "Fluid Glass" palette (v1.1, Stitch design system — ColorOS 17
 * inspired): vivid azure primary, mint secondary, warm amber tertiary,
 * porcelain background, ink text. The exact tokens come from the
 * generated designs in `~/workspace/fresh-start/design-v1.1/`
 * (#2F6BFF / #00BFA6–#10B981 / #FFB020–#F59E0B / #F8FAFC / #0F172A /
 * #475569 / #DBEAFE / #FEF3C7 / #D1FAE5 / #EAEFF7 / #E2E8F0).
 */

// ---- Light --------------------------------------------------------------

val GlassPrimaryLight = Color(0xFF2F6BFF)
val GlassOnPrimaryLight = Color(0xFFFFFFFF)
val GlassPrimaryContainerLight = Color(0xFFDBEAFE)
val GlassOnPrimaryContainerLight = Color(0xFF1E3A8A)
val GlassSecondaryLight = Color(0xFF00BFA6)
val GlassOnSecondaryLight = Color(0xFF00201B)
val GlassSecondaryContainerLight = Color(0xFFD1FAE5)
val GlassOnSecondaryContainerLight = Color(0xFF065F46)
val GlassTertiaryLight = Color(0xFFF59E0B)
val GlassOnTertiaryLight = Color(0xFF291800)
val GlassTertiaryContainerLight = Color(0xFFFEF3C7)
val GlassOnTertiaryContainerLight = Color(0xFF7A4A00)
val GlassErrorLight = Color(0xFFBA1A1A)
val GlassOnErrorLight = Color(0xFFFFFFFF)
val GlassErrorContainerLight = Color(0xFFFFDAD6)
val GlassOnErrorContainerLight = Color(0xFF93000A)
val GlassBackgroundLight = Color(0xFFF8FAFC)
val GlassOnBackgroundLight = Color(0xFF0F172A)
val GlassSurfaceLight = Color(0xFFFFFFFF)
val GlassOnSurfaceLight = Color(0xFF0F172A)
val GlassSurfaceVariantLight = Color(0xFFEAEFF7)
val GlassOnSurfaceVariantLight = Color(0xFF475569)
val GlassOutlineLight = Color(0xFF94A3B8)
val GlassOutlineVariantLight = Color(0xFFE2E8F0)
val GlassSurfaceContainerLowestLight = Color(0xFFFFFFFF)
val GlassSurfaceContainerLowLight = Color(0xFFF4F6FB)
val GlassSurfaceContainerLight = Color(0xFFEFF3F9)
val GlassSurfaceContainerHighLight = Color(0xFFE8EEF6)
val GlassSurfaceContainerHighestLight = Color(0xFFE2E8F0)

// ---- Stitch design tokens (exact values from design-v1.1 HTML) --------
// The HTML ground truth names these directly: primary #2F6BFF in every
// file; mint #10B981 and amber #F59E0B in the jobs/more/translate HTML
// (the design-system asset rounds them to #00BFA6 / #FFB020, which the
// Material secondary/tertiary above keep); the More banner gradient is
// #2F6BFF → #7C3AED. Components that reproduce a specific design
// element (progress bars, status pills, the banner) use THESE tokens so
// the element's colour matches the design pixel-for-pixel in both themes.
val StitchAzure = Color(0xFF2F6BFF)
val StitchMint = Color(0xFF10B981)
val StitchAmber = Color(0xFFF59E0B)
val StitchAmberDesign = Color(0xFFFFB020)
val StitchViolet = Color(0xFF7C3AED)
val StitchInk = Color(0xFF0F172A)
val StitchSlate = Color(0xFF475569)

// ---- Dark: deep ink-navy, same azure / mint / amber accents ------------

val GlassPrimaryDark = Color(0xFF8FB4FF)
val GlassOnPrimaryDark = Color(0xFF002A6E)
val GlassPrimaryContainerDark = Color(0xFF1E3A8A)
val GlassOnPrimaryContainerDark = Color(0xFFDBEAFE)
val GlassSecondaryDark = Color(0xFF34D3BC)
val GlassOnSecondaryDark = Color(0xFF00382F)
val GlassSecondaryContainerDark = Color(0xFF005047)
val GlassOnSecondaryContainerDark = Color(0xFF9FF0E2)
val GlassTertiaryDark = Color(0xFFFBBF24)
val GlassOnTertiaryDark = Color(0xFF3A2700)
val GlassTertiaryContainerDark = Color(0xFF594200)
val GlassOnTertiaryContainerDark = Color(0xFFFFDF9E)
val GlassErrorDark = Color(0xFFFFB4AB)
val GlassOnErrorDark = Color(0xFF690005)
val GlassErrorContainerDark = Color(0xFF93000A)
val GlassOnErrorContainerDark = Color(0xFFFFDAD6)
val GlassBackgroundDark = Color(0xFF0A111F)
val GlassOnBackgroundDark = Color(0xFFE6EDF7)
val GlassSurfaceDark = Color(0xFF101A2C)
val GlassOnSurfaceDark = Color(0xFFE6EDF7)
val GlassSurfaceVariantDark = Color(0xFF1C2A44)
val GlassOnSurfaceVariantDark = Color(0xFFA8B6CC)
val GlassOutlineDark = Color(0xFF5A6E8F)
val GlassOutlineVariantDark = Color(0xFF27364F)
val GlassSurfaceContainerLowestDark = Color(0xFF070D18)
val GlassSurfaceContainerLowDark = Color(0xFF0D1626)
val GlassSurfaceContainerDark = Color(0xFF111C30)
val GlassSurfaceContainerHighDark = Color(0xFF17253D)
val GlassSurfaceContainerHighestDark = Color(0xFF1E3049)
