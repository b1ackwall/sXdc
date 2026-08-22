package com.v1per.sxdc.download

import android.util.Log
import java.io.File

/**
 * Removes a staged download and the sidecars yt-dlp may have left beside it.
 *
 * Named files only, derived from one exact path -- never a directory sweep. `paths.temp` and
 * `paths.home` are the same staging directory (see sxdc_download.py), so a glob here could delete
 * another job's in-flight download. That constraint is why this lives in one place: both the
 * success path in DownloadService and the recovery pass in DownloadQueue have to honour it.
 */
internal object StagingFiles {

    private const val TAG = "StagingFiles"

    /** What EmbedThumbnail may leave behind if it fails before deleting its own sidecar. */
    private val THUMBNAIL_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp")

    /** Partial yt-dlp output, which only ever exists for a download that did not finish. */
    private const val PARTIAL_EXTENSION = "part"

    fun clear(file: File) {
        if (file.isFile && !file.delete()) {
            Log.w(TAG, "Could not delete staged file ${file.path}")
        }

        val stem = file.name.substringBeforeLast('.')
        for (ext in THUMBNAIL_EXTENSIONS + PARTIAL_EXTENSION) {
            val sidecar = File(file.parentFile, "$stem.$ext")
            if (sidecar.isFile && !sidecar.delete()) {
                Log.w(TAG, "Could not delete sidecar ${sidecar.path}")
            }
        }
    }
}
