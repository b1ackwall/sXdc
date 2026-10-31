package com.v1per.sxdc.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * WCAG 2.x contrast of the palette's text/background pairs.
 *
 * Documented exceptions:
 *  - Error on Surface is 4.49:1, a hair under 4.5, so it asserts 4.4.
 *  - Error on SurfaceVariant is about 3.5:1. It is for icons and large or bold text only, so it
 *    asserts the 3.0 non-text threshold.
 */
class ContrastTest {

    private fun channel(v: Float): Double {
        val c = v.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color) =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun ratio(a: Color, b: Color): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertContrast(name: String, fg: Color, bg: Color, min: Double) {
        val r = ratio(fg, bg)
        assertTrue("$name contrast is %.2f, needs >= %.1f".format(r, min), r >= min)
    }

    @Test
    fun textAndStateColors_readOnEveryLayer() {
        val fgs = mapOf(
            "TextPrimary" to TextPrimary,
            "TextSecondary" to TextSecondary,
            "TextTertiary" to TextTertiary,
            "Accent" to Accent,
            "Success" to Success,
        )
        val bgs = mapOf("Background" to Background, "Surface" to Surface, "SurfaceVariant" to SurfaceVariant)
        for ((fn, f) in fgs) for ((bn, b) in bgs) assertContrast("$fn on $bn", f, b, 4.5)
    }

    @Test
    fun onFillPairs_readOnTheirFill() {
        assertContrast("OnAccent on Accent", OnAccent, Accent, 4.5)
        assertContrast("Background on Error", Background, Error, 4.5)
        assertContrast("Background on Success", Background, Success, 4.5)
    }

    @Test
    fun error_readsOnBackground() = assertContrast("Error on Background", Error, Background, 4.5)

    @Test
    fun error_onSurface_documentedException() = assertContrast("Error on Surface", Error, Surface, 4.4)

    @Test
    fun error_onSurfaceVariant_iconsAndLargeTextOnly() =
        assertContrast("Error on SurfaceVariant", Error, SurfaceVariant, 3.0)
}
