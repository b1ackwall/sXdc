package com.v1per.sxdc.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * sXdc's palette: near-black surfaces, one neon accent, and a warning red reserved for failure.
 *
 * Independent of synthX -- the aesthetic direction is shared, the values are not. Deliberately
 * few: three screens do not justify a token system.
 *
 * The accent is the only saturated colour in the app and is spent only on things the user can
 * act on. Anything decorative uses [Ink] or [InkMuted], which is what keeps it from reading as
 * garish.
 */

/** App background. Not pure black -- OLED smear on scroll is worse than a near-black. */
val Void = Color(0xFF07090C)

/** Cards and raised panels. */
val Panel = Color(0xFF10151B)

/** Input fields and inset blocks, one step up from [Panel]. */
val PanelInset = Color(0xFF181F27)

/** Hairline borders. Visible against [Panel] without drawing attention. */
val Edge = Color(0xFF232D38)

/** The single accent: buttons, focused fields, active states. */
val Neon = Color(0xFF00E5C7)

/** Text and icons drawn on [Neon]; near-black for contrast on a bright accent. */
val OnNeon = Color(0xFF00201B)

/** Primary text. */
val Ink = Color(0xFFE3ECF2)

/** Secondary text: hints, explanations, diagnostics. */
val InkMuted = Color(0xFF808F9E)

/** Failure only -- never decorative, so a red on screen always means something went wrong. */
val Alert = Color(0xFFFF4D6D)

/** Text drawn on [Alert]. */
val OnAlert = Color(0xFF2B0009)
