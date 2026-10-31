package com.v1per.sxdc.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * sXdc's palette: a dark plum background, one cool accent, warm cream text, and green/red state
 * colours.
 *
 * Independent of synthX -- the aesthetic direction is shared, the values are not.
 *
 * Error text is for Background and Surface; on SurfaceVariant use it for icons or large/bold text
 * only (3.5:1).
 */

/** The single brand accent: buttons, active indicators. */
val Accent = Color(0xFF52C9E0)

/** Text and icons drawn on [Accent]. */
val OnAccent = Color(0xFF084C61)

/** App background. */
val Background = Color(0xFF1D0C14)

/** Cards and raised panels. */
val Surface = Color(0xFF321523)

/** Inputs and inset blocks; the lightest dark layer. */
val SurfaceVariant = Color(0xFF492738)

/** Main text. */
val TextPrimary = Color(0xFFF7ECDE)

/** Secondary text. */
val TextSecondary = Color(0xFFEFD9BD)

/** Hints, muted text, disabled. */
val TextTertiary = Color(0xFFE0C6A3)

/** Success state: text, icons, fills. */
val Success = Color(0xFF3CDD57)

/** Failure state. */
val Error = Color(0xFFFA3838)

/** Alpha applied to [TextTertiary] for the `outline` slot. */
const val BORDER_ALPHA = 0.40f

/** Alpha applied to [TextTertiary] for the `outlineVariant` slot. */
const val BORDER_VARIANT_ALPHA = 0.20f
