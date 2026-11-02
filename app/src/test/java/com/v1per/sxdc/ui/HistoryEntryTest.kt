package com.v1per.sxdc.ui

import com.v1per.sxdc.data.DownloadJob
import com.v1per.sxdc.data.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** The history row's reading of status and failureReason (#45). */
class HistoryEntryTest {

    private fun entry(status: DownloadStatus, reason: String? = null) =
        historyEntry(DownloadJob(url = "https://youtu.be/x", status = status, failureReason = reason), "Cancelled")

    @Test
    fun completedWithANote_isOk_andTheNoteIsMutedNotAnError() {
        assertEquals(
            HistoryEntry(ok = true, detail = "Saved without cover art.", detailIsError = false),
            entry(DownloadStatus.COMPLETED, "Saved without cover art."),
        )
    }

    @Test
    fun completedWithoutANote_isOk_withNoSecondLine() {
        assertEquals(HistoryEntry(ok = true, detail = null, detailIsError = false), entry(DownloadStatus.COMPLETED))
    }

    @Test
    fun failed_showsTheReasonAsAnError() {
        assertEquals(
            HistoryEntry(ok = false, detail = "Not enough free space.", detailIsError = true),
            entry(DownloadStatus.FAILED, "Not enough free space."),
        )
        assertEquals(HistoryEntry(ok = false, detail = null, detailIsError = true), entry(DownloadStatus.FAILED))
    }

    @Test
    fun cancelled_showsTheStatusWord_notAReason() {
        assertEquals(
            HistoryEntry(ok = false, detail = "Cancelled", detailIsError = true),
            entry(DownloadStatus.CANCELLED, "left over"),
        )
    }
}
