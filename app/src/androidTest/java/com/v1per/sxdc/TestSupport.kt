package com.v1per.sxdc

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.v1per.sxdc.download.DownloadService
import java.io.File

/** Whether DownloadService is running in this app's process right now. */
@Suppress("DEPRECATION") // getRunningServices still returns the caller's own services.
fun isDownloadServiceRunning(context: Context): Boolean {
    val am = context.getSystemService(ActivityManager::class.java)
    return am.getRunningServices(Int.MAX_VALUE).any {
        it.service.packageName == context.packageName &&
            it.service.className == DownloadService::class.java.name
    }
}

/** Polls [condition] every [stepMs] until it is true or [timeoutMs] passes. Returns the last value. */
fun pollUntil(timeoutMs: Long, stepMs: Long = 100, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        if (condition()) return true
        if (System.currentTimeMillis() >= deadline) return false
        Thread.sleep(stepMs.coerceAtMost(250))
    }
}

/** The currently resumed activity, read on the main thread. */
fun resumedActivity(): Activity? {
    var result: Activity? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        result = ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .firstOrNull()
    }
    return result
}

/**
 * The install's own staging folder, worked out the way DownloadPreconditions.outputDir does in
 * production. The test application stages in a subfolder of it instead.
 */
fun realStagingDir(context: Context): File =
    context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "downloads")
