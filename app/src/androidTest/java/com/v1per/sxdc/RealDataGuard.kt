package com.v1per.sxdc

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.v1per.sxdc.download.DownloadQueue
import com.v1per.sxdc.download.DownloadService
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Protects the real data on the device. Any test that launches an activity or starts the service
 * is skipped while the real queue holds pending jobs, because MainActivity.onResume and the
 * service would otherwise act on them.
 *
 * The single read-only countPending() here is the only access to the real database in tests.
 */
object RealDataGuard {

    fun assumeNoPendingJobs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pending = runBlocking { DownloadQueue.dao(context).countPending() }
        assumeTrue("Real queue has $pending pending job(s); skipping to protect user data", pending == 0)
    }

    /** Outermost rule, so the assumption is checked before an activity rule launches anything. */
    fun rule(): TestRule = TestRule { base: Statement, _: Description ->
        object : Statement() {
            override fun evaluate() {
                assumeNoPendingJobs()
                base.evaluate()
            }
        }
    }
}

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
