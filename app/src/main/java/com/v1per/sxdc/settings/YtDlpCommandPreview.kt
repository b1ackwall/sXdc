package com.v1per.sxdc.settings

/**
 * Mirrors the postprocessor chain `sxdc_download.py`'s `download_audio` actually builds: FLAC
 * extraction at quality 0, embedded metadata, embedded thumbnail, restricted filenames, and the
 * `%(title)s.%(ext)s` output template. Display-only -- if that Python file's `ydl_opts` chain
 * ever changes, this string must be updated to match.
 */
internal object YtDlpCommandPreview {
    private const val CODEC = "flac" // sxdc_download.DEFAULT_CODEC
    private const val QUALITY = "0" // FFmpegExtractAudio's preferredquality
    private const val OUTPUT_TEMPLATE = "%(title)s.%(ext)s"

    fun render(): String =
        "yt-dlp -x --audio-format $CODEC --audio-quality $QUALITY " +
            "--embed-metadata --embed-thumbnail --restrict-filenames " +
            "-o \"$OUTPUT_TEMPLATE\" <url>"
}
