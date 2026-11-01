package com.v1per.sxdc.settings

/**
 * An illustration of the options `sxdc_download.py`'s `download_audio` actually uses: audio-only
 * format, FLAC extraction at quality 0, embedded metadata and thumbnail, restricted filenames, no
 * playlist expansion, the retry counts and the `%(title).100s [%(id)s].%(ext)s` output template.
 * It is not a byte-exact command: staging paths, ffmpeg location, quiet flags and hooks have no place here.
 * Display-only -- every value below is pinned to tests/golden/ytdlp-options.properties by
 * YtDlpCommandPreviewGoldenTest, and the Python side by test_options_golden.py.
 */
internal object YtDlpCommandPreview {
    private const val FORMAT = "bestaudio/best" // download_audio's `format`
    private const val CODEC = "flac" // sxdc_download.DEFAULT_CODEC
    private const val QUALITY = "0" // FFmpegExtractAudio's preferredquality
    private const val RETRIES = "3" // `retries`
    private const val FRAGMENT_RETRIES = "3" // `fragment_retries`
    private const val OUTPUT_TEMPLATE = "%(title).100s [%(id)s].%(ext)s"

    fun render(): String =
        "yt-dlp -f $FORMAT -x --audio-format $CODEC --audio-quality $QUALITY " +
            "--embed-metadata --embed-thumbnail --restrict-filenames --no-playlist " +
            "--retries $RETRIES --fragment-retries $FRAGMENT_RETRIES " +
            "-o \"$OUTPUT_TEMPLATE\" <url>"
}
