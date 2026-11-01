package com.v1per.sxdc.settings

import com.v1per.sxdc.GoldenOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpCommandPreviewGoldenTest {

    private val rendered = YtDlpCommandPreview.render()

    /** Golden key -> the preview text it must produce, for every key that has a CLI form. */
    private fun valueFragments() = mapOf(
        "format" to "-f ${GoldenOptions.string("format")}",
        "codec" to "--audio-format ${GoldenOptions.string("codec")}",
        "quality" to "--audio-quality ${GoldenOptions.string("quality")}",
        "retries" to "--retries ${GoldenOptions.string("retries")}",
        "fragmentRetries" to "--fragment-retries ${GoldenOptions.string("fragmentRetries")}",
        "outputTemplate" to "-o \"${GoldenOptions.string("outputTemplate")}\"",
    )

    /** Golden boolean key -> its flag; present exactly when the golden value is true. */
    private val flagKeys = mapOf(
        "embedMetadata" to "--embed-metadata",
        "embedThumbnail" to "--embed-thumbnail",
        "restrictFilenames" to "--restrict-filenames",
        "noplaylist" to "--no-playlist",
    )

    /** Golden keys that are real options but deliberately not shown in the preview. */
    private val realOptionsOnly = listOf("quiet", "noWarnings", "noprogress", "writeThumbnail")

    @Test
    fun valueOptions_reflectGolden() {
        valueFragments().forEach { (key, fragment) ->
            assertTrue("$key: '$fragment' missing from $rendered", rendered.contains(fragment))
        }
    }

    @Test
    fun flags_presentExactlyWhenGoldenSaysSo() {
        flagKeys.forEach { (key, flag) ->
            assertEquals("$flag vs golden $key", GoldenOptions.flag(key), rendered.contains(flag))
        }
    }

    @Test
    fun writeThumbnail_isImpliedByEmbedThumbnail() {
        // --embed-thumbnail writes the thumbnail itself, so writeThumbnail has no flag of its own.
        if (GoldenOptions.flag("writeThumbnail")) {
            assertTrue(rendered, rendered.contains("--embed-thumbnail"))
        }
    }

    @Test
    fun everyFlagInThePreview_hasAGoldenKeyBehindIt() {
        val backed = setOf("-x", "-f", "-o") +
            flagKeys.filterKeys { GoldenOptions.flag(it) }.values +
            valueFragments().values.map { it.substringBefore(' ') }
        val shown = rendered.split(' ').filter { it.startsWith("-") }
        val unbacked = shown.filterNot { it in backed }
        assertTrue("preview flags without a golden key: $unbacked", unbacked.isEmpty())
    }

    @Test
    fun realOnlyOptions_areInGolden_butNotInThePreview() {
        realOptionsOnly.forEach { GoldenOptions.string(it) } // throws if the key vanished
        listOf("--quiet", "--no-warnings", "--no-progress", "--write-thumbnail").forEach {
            assertTrue("$it must not be in the preview", !rendered.contains(it))
        }
    }

    /** Characterization: the full string shown in Settings today. */
    @Test
    fun render_fullString() {
        assertEquals(
            "yt-dlp -f bestaudio/best -x --audio-format flac --audio-quality 0 " +
                "--embed-metadata --embed-thumbnail --restrict-filenames --no-playlist " +
                "--retries 3 --fragment-retries 3 " +
                "-o \"%(title).100s [%(id)s].%(ext)s\" <url>",
            rendered,
        )
    }
}
