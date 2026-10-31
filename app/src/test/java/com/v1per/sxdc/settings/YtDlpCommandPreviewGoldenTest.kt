package com.v1per.sxdc.settings

import com.v1per.sxdc.GoldenOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpCommandPreviewGoldenTest {

    private val rendered = YtDlpCommandPreview.render()

    @Test
    fun codec_matchesGolden() {
        assertTrue(rendered, rendered.contains("--audio-format ${GoldenOptions.string("codec")}"))
    }

    @Test
    fun quality_matchesGolden() {
        assertTrue(rendered, rendered.contains("--audio-quality ${GoldenOptions.string("quality")}"))
    }

    @Test
    fun outputTemplate_matchesGolden() {
        assertTrue(rendered, rendered.contains("-o \"${GoldenOptions.string("outputTemplate")}\""))
    }

    @Test
    fun flags_presentExactlyWhenGoldenSaysSo() {
        mapOf(
            "embedMetadata" to "--embed-metadata",
            "embedThumbnail" to "--embed-thumbnail",
            "restrictFilenames" to "--restrict-filenames",
        ).forEach { (key, flag) ->
            assertEquals("$flag vs golden $key", GoldenOptions.flag(key), rendered.contains(flag))
        }
    }

    /** Characterization: the full string shown in Settings today. */
    @Test
    fun render_fullString() {
        assertEquals(
            "yt-dlp -x --audio-format flac --audio-quality 0 " +
                "--embed-metadata --embed-thumbnail --restrict-filenames " +
                "-o \"%(title)s.%(ext)s\" <url>",
            rendered,
        )
    }
}
