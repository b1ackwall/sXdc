package com.v1per.sxdc

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.v1per.sxdc.data.SxdcDatabase
import com.v1per.sxdc.download.DownloadQueue
import com.v1per.sxdc.download.Notifications
import com.v1per.sxdc.download.StartupRecovery
import com.v1per.sxdc.settings.SxdcSettings
import kotlinx.coroutines.launch

/**
 * Process-wide startup.
 *
 * Python init moved here from MainActivity in Task 3: the share flow and DownloadService can both
 * be reached without MainActivity ever running, so the interpreter has to be started from the
 * Application rather than from one particular entry point.
 */
open class SxdcApp : Application() {

    override fun onCreate() {
        super.onCreate()

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        // Channels are idempotent and must exist before the first notification is posted; the
        // service can be the first thing that runs after install, so create them here.
        Notifications.ensureChannels(this)

        // Resolve any job left claimed by a process that did not survive. Bookkeeping only --
        // this must not start DownloadService. The system reaches Application.onCreate for plenty
        // of background reasons, and a dataSync foreground service started from one of those
        // throws ForegroundServiceStartNotAllowedException. MainActivity restarts the queue from
        // a visible Activity instead. StartupRecovery opens the queue's claim gate when it is
        // over, and a failure is logged rather than crashing the process.
        appScope.launch { StartupRecovery.run(recover = { runStartupRecovery() }) }
    }

    /** The Room file name, read by SxdcDatabase.get. Open so instrumented tests use their own. */
    open val databaseName: String get() = SxdcDatabase.NAME

    /** The SharedPreferences file SxdcSettings uses. Open so instrumented tests use their own. */
    open val settingsName: String get() = SxdcSettings.PREFS

    /**
     * A folder inside the staging folder to stage in instead, read by DownloadPreconditions.outputDir.
     * Null, the production value, stages in the staging folder itself. Open so instrumented tests
     * use their own.
     */
    open val stagingDirName: String? get() = null

    /** Whether MainActivity asks for POST_NOTIFICATIONS. Off in instrumented tests (#25). */
    open val requestsNotificationPermission: Boolean get() = true

    /** The recovery pass itself. Open so the instrumented-test application can skip it. */
    protected open suspend fun runStartupRecovery() {
        DownloadQueue.recoverOrphans(this)
    }
}
