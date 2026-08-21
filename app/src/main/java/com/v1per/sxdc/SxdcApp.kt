package com.v1per.sxdc

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.v1per.sxdc.download.Notifications

/**
 * Process-wide startup.
 *
 * Python init moved here from MainActivity in Task 3: the share flow and DownloadService can both
 * be reached without MainActivity ever running, so the interpreter has to be started from the
 * Application rather than from one particular entry point.
 */
class SxdcApp : Application() {

    override fun onCreate() {
        super.onCreate()

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        // Channels are idempotent and must exist before the first notification is posted; the
        // service can be the first thing that runs after install, so create them here.
        Notifications.ensureChannels(this)
    }
}
