package com.v1per.sxdc.download

import android.content.Context
import android.os.Environment
import com.v1per.sxdc.media.FfmpegBinaries
import java.io.File

/**
 * Gate checked on the share path before a download is allowed to start.
 *
 * Two conditions today: a configured destination folder, and usable ffmpeg binaries.
 */
object DownloadPreconditions {

    private const val PREFS = "sxdc.settings"

    /**
     * Placeholder for the real SAF tree URI that Task 4's folder picker will store.
     *
     * Defaults to *configured* rather than *unconfigured*. Task 3 writes to app-private storage
     * regardless (Task 4 owns the SAF handoff), so defaulting this to false would make the entire
     * share flow untestable while providing no real safety. Setting the key to false is the way
     * to exercise the fix-it path by hand.
     */
    const val KEY_FOLDER_CONFIGURED = "download_folder_configured"

    sealed interface Result {
        data class Ok(val outputDir: File, val ffmpegBinDir: String) : Result
        data class Failed(val reasons: List<String>) : Result
    }

    fun check(context: Context): Result {
        val reasons = mutableListOf<String>()

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_FOLDER_CONFIGURED, true)) {
            reasons += "No download folder is configured yet."
        }

        var ffmpegBinDir: String? = null
        try {
            val install = FfmpegBinaries.install(context)
            // Deliberately an existence/permission check, not a `-version` exec. This runs on the
            // main thread on a path that must reach startForegroundService() while the activity is
            // still foreground; spawning two processes here would add avoidable latency. The full
            // exec-based probe stays in sxdc_verify for on-device diagnostics.
            if (!install.ffmpeg.exists() || !install.ffmpeg.canExecute()) {
                reasons += "The bundled ffmpeg binary is missing or not executable."
            } else if (!install.ffprobe.exists() || !install.ffprobe.canExecute()) {
                reasons += "The bundled ffprobe binary is missing or not executable."
            } else {
                ffmpegBinDir = install.binDirPath
            }
        } catch (e: Exception) {
            reasons += "ffmpeg could not be installed: ${e.message}"
        }

        return if (reasons.isEmpty() && ffmpegBinDir != null) {
            Result.Ok(outputDir(context), ffmpegBinDir)
        } else {
            Result.Failed(reasons)
        }
    }

    /**
     * Task 3 writes to app-private storage only; the SAF move is Task 4.
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
