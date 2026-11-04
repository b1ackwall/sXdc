package com.v1per.sxdc.download

import kotlinx.coroutines.sync.Mutex

/**
 * Process-wide queue-run state, shared by every DownloadService instance (#43).
 *
 * A service instance that onTimeout stopped can still be inside its blocking Python call when the
 * next instance starts, and each instance builds its own QueueProcessor. The lock therefore lives
 * here rather than in the processor: the next run waits until the stale one has returned,
 * discarded its result and swept staging before it sweeps, expands or claims anything itself.
 */
internal object QueueRunLock {
    /** Held for a whole QueueProcessor.process run, both sweeps included. */
    val mutex = Mutex()

    /** The in-flight download's abort signal, which Emergency Stop reaches through this. */
    val active = ActiveRun()
}
