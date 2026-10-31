package com.v1per.sxdc.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import org.junit.rules.ExternalResource

/**
 * Builds a fresh in-memory [SxdcDatabase] before each test and closes it afterwards.
 *
 * Never touches the real "sxdc.db": the app's own SxdcDatabase.get() is not used anywhere in
 * the instrumented tests.
 */
class InMemoryDatabaseRule : ExternalResource() {

    lateinit var db: SxdcDatabase
        private set

    val dao: DownloadJobDao get() = db.downloadJobs()

    override fun before() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SxdcDatabase::class.java).build()
    }

    override fun after() {
        db.close()
    }

    /** Reads the raw TEXT stored in the status column, bypassing the type converter. */
    fun rawStatus(id: Long): String? =
        db.openHelper.readableDatabase
            .query("SELECT status FROM download_jobs WHERE id = ?", arrayOf<Any?>(id))
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }
}

/** Current rows via the UI query. observeAll caps at 50 rows; callers stay below that. */
suspend fun DownloadJobDao.snapshot(): List<DownloadJob> = observeAll().first()

suspend fun DownloadJobDao.byId(id: Long): DownloadJob = snapshot().single { it.id == id }

/** Test shorthand: a job for [url] with the given status and fields. */
fun job(
    url: String = "https://example.com/a",
    status: DownloadStatus = DownloadStatus.QUEUED,
    title: String? = null,
    stagedFilePath: String? = null,
    failureReason: String? = null,
    attempts: Int = 0,
    createdAt: Long = 1_000L,
    completedAt: Long? = null,
) = DownloadJob(
    url = url,
    status = status,
    title = title,
    stagedFilePath = stagedFilePath,
    failureReason = failureReason,
    attempts = attempts,
    createdAt = createdAt,
    completedAt = completedAt,
)
