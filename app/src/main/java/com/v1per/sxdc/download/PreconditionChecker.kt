package com.v1per.sxdc.download

import android.content.Context

internal sealed interface PreconditionResult {
    /**
     * @param outputDir staging directory's absolute path, the exact string yt-dlp receives
     * @param ffmpegBinDir directory holding the ffmpeg/ffprobe entries
     * @param destination the SAF tree URI in its string form
     */
    data class Ok(val outputDir: String, val ffmpegBinDir: String, val destination: String) : PreconditionResult

    data class Failed(val reasons: List<String>) : PreconditionResult
}

internal interface PreconditionChecker {
    fun check(): PreconditionResult
}

/** Adapts [DownloadPreconditions.check], which stays the single source of the rules. */
internal class DevicePreconditionChecker(private val context: Context) : PreconditionChecker {
    override fun check(): PreconditionResult = when (val pre = DownloadPreconditions.check(context)) {
        is DownloadPreconditions.Result.Failed -> PreconditionResult.Failed(pre.reasons)
        is DownloadPreconditions.Result.Ok -> PreconditionResult.Ok(
            outputDir = pre.outputDir.absolutePath,
            ffmpegBinDir = pre.ffmpegBinDir,
            destination = pre.destinationTreeUri.toString(),
        )
    }
}
