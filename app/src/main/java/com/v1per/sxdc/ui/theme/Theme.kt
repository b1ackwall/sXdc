package com.v1per.sxdc.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Dark only, by design -- no light scheme and no Material You dynamic colour.
 *
 * Dynamic colour would hand the accent over to the user's wallpaper, which is the one thing this
 * theme is actually about. A light scheme would be dead code: every entry point is a dark screen.
 */
private val SxdcColorScheme = darkColorScheme(
    primary = Neon,
    onPrimary = OnNeon,
    secondary = Neon,
    onSecondary = OnNeon,
    background = Void,
    onBackground = Ink,
    surface = Panel,
    onSurface = Ink,
    surfaceVariant = PanelInset,
    onSurfaceVariant = InkMuted,
    surfaceContainer = Panel,
    surfaceContainerHigh = PanelInset,
    outline = Edge,
    outlineVariant = Edge,
    error = Alert,
    onError = OnAlert,
    scrim = Void,
)

/** Near-square. 2dp reads as "cut metal" rather than "rounded card". */
private val SxdcShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(3.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(4.dp),
)

/**
 * The whole spacing system. Three values cover every screen in the app; anything more would be
 * ceremony for its own sake.
 */
object Spacing {
    /** Screen edge padding. */
    val Gutter = 20.dp

    /** Between sibling elements in a column. */
    val Item = 12.dp

    /** Inside a card, and between a label and its value. */
    val Tight = 8.dp
}

@Composable
fun SXdcTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SxdcColorScheme,
        typography = Typography,
        shapes = SxdcShapes,
        content = content,
    )
}
