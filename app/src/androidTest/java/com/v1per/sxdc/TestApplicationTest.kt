package com.v1per.sxdc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.sxdc.data.DownloadJob
import com.v1per.sxdc.data.SxdcDatabase
import com.v1per.sxdc.download.DownloadQueue
import com.v1per.sxdc.download.StartupRecovery
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The instrumented tests run in TestSxdcApp, away from the install's real data. */
@RunWith(AndroidJUnit4::class)
class TestApplicationTest {

    @get:Rule
    val db = TestDatabaseRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as TestSxdcApp

    @Test
    fun startupRecovery_wasReplaced_andStillOpenedTheGate() = runBlocking {
        // The gate opens only after the recovery body has returned, so this also orders the flag.
        withTimeout(5_000) { StartupRecovery.awaitDone() }
        assertTrue(app.recoveryOverrideRan)
    }

    @Test
    fun notificationPrompt_isOff() {
        assertFalse(app.requestsNotificationPermission)
    }

    @Test
    fun appDatabase_isTheTestFile_andTheRealFileIsUntouched() = runBlocking {
        val real = realFiles()
        val before = real.map(::snapshot)

        val opened = SxdcDatabase.get(context).openHelper
        assertEquals(TestSxdcApp.TEST_DATABASE_NAME, opened.databaseName)

        // Exercise the app's own database path: every production caller goes through this dao.
        val dao = DownloadQueue.dao(context)
        val id = dao.insert(DownloadJob(url = "https://example.com/test-app-db"))
        assertEquals(1, dao.countPending())
        assertEquals(id, dao.claimNext()?.id)
        opened.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }

        assertEquals(before, real.map(::snapshot))
    }

    /** The real database's main file and WAL, whether or not they exist on this install. */
    private fun realFiles(): List<File> {
        val main = context.getDatabasePath(SxdcDatabase.NAME)
        return listOf(main, File(main.path + "-wal"))
    }

    private fun snapshot(file: File): String =
        if (file.exists()) "${file.name} size=${file.length()} mtime=${file.lastModified()}" else "${file.name} absent"
}
