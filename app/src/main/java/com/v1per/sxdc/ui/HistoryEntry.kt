package com.v1per.sxdc.ui

import com.v1per.sxdc.data.DownloadJob
import com.v1per.sxdc.data.DownloadStatus

/** Test tag of the ✓/✗ mark in front of each history row, for screen tests. */
internal const val HISTORY_MARK_TAG = "history-mark"

/**
 * How one finished job reads in the history list. Pure, so the status rules are JVM-tested.
 *
 * @param ok the ✓ of a completed job, rather than the ✗ of any other
 * @param detail the second line, or null for none
 * @param detailIsError red for a failure; muted for a completed job's saved-without note (#45)
 */
internal data class HistoryEntry(val ok: Boolean, val detail: String?, val detailIsError: Boolean)

/**
 * The status decides how `failureReason` reads (see [DownloadJob]): on a COMPLETED row it is the
 * saved-without note, never a failure. A CANCELLED job was never attempted, so its second line
 * is [cancelledLabel] rather than a reason.
 */
internal fun historyEntry(job: DownloadJob, cancelledLabel: String): HistoryEntry = when (job.status) {
    DownloadStatus.COMPLETED -> HistoryEntry(ok = true, detail = job.failureReason, detailIsError = false)
    DownloadStatus.CANCELLED -> HistoryEntry(ok = false, detail = cancelledLabel, detailIsError = true)
    else -> HistoryEntry(ok = false, detail = job.failureReason, detailIsError = true)
}
