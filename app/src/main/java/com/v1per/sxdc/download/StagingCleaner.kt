package com.v1per.sxdc.download

import android.content.Context

/** Clears the staging folder once a queue run is over. */
internal interface StagingCleaner {
    fun sweep()
}

/** Sweeps the real staging folder, [DownloadPreconditions.outputDir]. */
internal class DeviceStagingCleaner(context: Context) : StagingCleaner {

    private val appContext = context.applicationContext

    override fun sweep() {
        StagingSweeper.sweep(DownloadPreconditions.outputDir(appContext))
    }
}
