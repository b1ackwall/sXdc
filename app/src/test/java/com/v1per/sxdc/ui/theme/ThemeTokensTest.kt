package com.v1per.sxdc.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Locks the palette. Gradle runs module unit tests with app/ as the working directory. */
class ThemeTokensTest {

    private fun assertToken(name: String, expected: Long, actual: Color) =
        assertEquals("$name should be 0x${expected.toString(16).uppercase()}", Color(expected), actual)

    @Test
    fun tokens_matchLockedHex() {
        assertToken("Accent", 0xFF52C9E0, Accent)
        assertToken("OnAccent", 0xFF084C61, OnAccent)
        assertToken("Background", 0xFF1D0C14, Background)
        assertToken("Surface", 0xFF321523, Surface)
        assertToken("SurfaceVariant", 0xFF492738, SurfaceVariant)
        assertToken("TextPrimary", 0xFFF7ECDE, TextPrimary)
        assertToken("TextSecondary", 0xFFEFD9BD, TextSecondary)
        assertToken("TextTertiary", 0xFFE0C6A3, TextTertiary)
        assertToken("Success", 0xFF3CDD57, Success)
        assertToken("Error", 0xFFFA3838, Error)
    }

    @Test
    fun borderAlphas_matchLockedValues() {
        assertEquals(0.40f, BORDER_ALPHA, 0f)
        assertEquals(0.20f, BORDER_VARIANT_ALPHA, 0f)
    }

    @Test
    fun windowBackgroundResource_mirrorsBackgroundToken() {
        val file = File("src/main/res/values/colors.xml")
        if (!file.isFile) {
            fail("colors.xml not found at ${file.absolutePath} (working dir ${File("").absolutePath})")
        }
        val match = Regex("""<color\s+name="sxdc_background">#([0-9A-Fa-f]{8})</color>""")
            .find(file.readText())
            ?: throw AssertionError("sxdc_background is missing from ${file.path}")
        val argb = match.groupValues[1].toLong(16)
        assertEquals("sxdc_background must equal the Background token", Color(argb), Background)
    }
}
