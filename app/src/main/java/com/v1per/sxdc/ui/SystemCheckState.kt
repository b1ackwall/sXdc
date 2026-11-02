package com.v1per.sxdc.ui

import android.util.Log
import kotlin.coroutines.cancellation.CancellationException

/** What the System check panel shows: still running, finished, or the load itself threw (#68). */
internal sealed interface SystemCheckState {
    data object Pending : SystemCheckState

    data class Loaded(val diagnostics: Diagnostics) : SystemCheckState

    /** @param message "<ExceptionClass>: <message>", shown on the panel's first row */
    data class Failed(val message: String) : SystemCheckState
}

/** The state to start from: the process's cached result if there is one, else [SystemCheckState.Pending]. */
internal fun OnceCache<Diagnostics>.initialState(): SystemCheckState =
    peek()?.let { SystemCheckState.Loaded(it) } ?: SystemCheckState.Pending

/**
 * Runs [loader] through [cache] and turns a thrown exception into [SystemCheckState.Failed]
 * instead of letting it crash the activity. Nothing is cached on failure, so the next activity
 * creation tries again. Cancellation is passed on: a rotation mid-load is not a failure.
 */
internal suspend fun loadSystemCheck(
    cache: OnceCache<Diagnostics>,
    loader: suspend () -> Diagnostics,
): SystemCheckState = try {
    SystemCheckState.Loaded(cache.get(loader))
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.e(TAG, "System check failed", e)
    SystemCheckState.Failed(listOfNotNull(e::class.simpleName, e.message).joinToString(": "))
}

private const val TAG = "SystemCheck"
