package com.v1per.sxdc.download

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.v1per.sxdc.media.FfmpegBinaries
import com.v1per.sxdc.settings.SxdcSettings
import java.io.File

/**
 * Gate checked on the share path before a download is allowed to start, and again on the service
 * worker before yt-dlp runs.
 *
 * Two conditions today: a usable SAF destination folder, and usable ffmpeg binaries.
 */
object DownloadPreconditions {

    private const val TAG = "DownloadPreconditions"

    /** Shown when the bundled tools cannot be prepared, whatever the underlying cause. */
    const val REASON_MEDIA_TOOLS =
        "sXdc's audio tools couldn't be prepared. Reinstalling sXdc usually fixes this."

    /**
     * The two folder failures need different things from the user -- pick a folder for the first
     * time, versus re-grant a folder that was revoked, deleted or unmounted -- so they are
     * separate messages rather than one generic "no folder" line.
     */
    const val REASON_NO_FOLDER =
        "No download folder is configured yet. Choose one in Settings."
    const val REASON_FOLDER_LOST =
        "Access to the download folder was lost. Choose it again in Settings."

    sealed interface Result {
        /**
         * @param outputDir app-private staging directory yt-dlp writes into
         * @param destinationTreeUri user-chosen SAF tree the finished file is moved to
         */
        data class Ok(
            val outputDir: File,
            val destinationTreeUri: Uri,
            val ffmpegBinDir: String,
        ) : Result

        data class Failed(val reasons: List<String>) : Result
    }

    fun check(context: Context): Result {
        val reasons = mutableListOf<String>()

        // Re-asked on every check: a stored URI is not proof the grant behind it still holds.
        var destinationTreeUri: Uri? = null
        val treeUri = SxdcSettings.treeUri(context)
        if (treeUri == null) {
            reasons += REASON_NO_FOLDER
        } else if (!SxdcSettings.hasWriteGrant(context, treeUri)) {
            reasons += REASON_FOLDER_LOST
        } else {
            destinationTreeUri = treeUri
        }

        var ffmpegBinDir: String? = null
        try {
            val install = FfmpegBinaries.install(context)
            // Deliberately an existence/permission check, not a `-version` exec. This runs on the
            // main thread on a path that must reach startForegroundService() while the activity is
            // still foreground; spawning two processes here would add avoidable latency. The full
            // exec-based probe stays in sxdc_verify for on-device diagnostics.
            if (!install.ffmpeg.exists() || !install.ffmpeg.canExecute()) {
                Log.e(TAG, "ffmpeg missing or not executable at ${install.ffmpeg.path}")
                reasons += REASON_MEDIA_TOOLS
            } else if (!install.ffprobe.exists() || !install.ffprobe.canExecute()) {
                Log.e(TAG, "ffprobe missing or not executable at ${install.ffprobe.path}")
                reasons += REASON_MEDIA_TOOLS
            } else {
                ffmpegBinDir = install.binDirPath
            }
        } catch (e: Exception) {
            // The exception text names .so paths and SELinux states -- log-only material.
            Log.e(TAG, "ffmpeg install failed", e)
            reasons += REASON_MEDIA_TOOLS
        }

        return if (reasons.isEmpty() && ffmpegBinDir != null && destinationTreeUri != null) {
            Result.Ok(outputDir(context), destinationTreeUri, ffmpegBinDir)
        } else {
            Result.Failed(reasons)
        }
    }

    /**
     * Staging only. yt-dlp writes here, then DownloadService moves the finished file into the
     * user's SAF folder and clears the staged copy.
     *
     * Prefers the external app-private Music dir because it stays app-scoped while remaining
     * visible over USB/MTP and to file managers, which makes manual verification far easier than
     * an internal filesDir path would. Falls back to internal storage if external is unavailable.
     */
    fun outputDir(context: Context): File {
        val external = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        val dir = external ?: File(context.filesDir, "downloads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
