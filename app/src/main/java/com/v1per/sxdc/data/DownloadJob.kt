package com.v1per.sxdc.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/**
 * Where a job is in its life. Stored as the constant's name, so the DAO's SQL can compare
 * against plain string literals ('QUEUED', 'DOWNLOADING', ...) and stay readable.
 *
 * [DOWNLOADING] is a *claim*, not a durable state: the only writer is the one service worker in
 * this process, so a row still holding it at process start belongs to a run that was killed. See
 * DownloadQueue.recoverOrphans.
 */
enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETED, FAILED }

/**
 * Explicit rather than leaning on Room's automatic enum handling: the mapping is what the DAO's
 * string literals depend on, so it is worth having it written down in one visible place.
 */
class DownloadStatusConverter {
    @TypeConverter
    fun toName(status: DownloadStatus): String = status.name

    @TypeConverter
    fun fromName(name: String): DownloadStatus = DownloadStatus.valueOf(name)
}

/**
 * One shared URL and everything learned about it along the way.
 *
 * Deliberately **not** `@Parcelize`. Room 2.8.4 + KSP 2.3.x under AGP 9's built-in Kotlin fails
 * type resolution with `[MissingType]` when one class carries both `@Entity` and `@Parcelize`
 * (google/ksp#3053). Nothing here needs to travel through an Intent -- the service receives a URL
 * and does its own insert -- so the combination never has to arise.
 *
 * @param status see [DownloadStatus]
 * @param title yt-dlp's title, unknown until extraction succeeds
 * @param stagedFilePath the file yt-dlp wrote, held only while it exists; cleared once the move
 *   into the user's SAF folder is confirmed, so a non-null value on a live row is exactly the set
 *   of bytes a recovery pass has to clean up
 * @param failureReason the classified, user-facing line -- the same text the notification shows
 * @param attempts incremented every time recovery requeues this row, to bound a poison pill
 */
@Entity(
    tableName = "download_jobs",
    indices = [Index("status"), Index("url")],
)
data class DownloadJob(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val url: String,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val title: String? = null,
    val stagedFilePath: String? = null,
    val failureReason: String? = null,
    val attempts: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
)
