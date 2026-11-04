package com.v1per.sxdc.download

import java.util.concurrent.atomic.AtomicReference

/**
 * The abort signal of the download in flight, if any, so something outside the service (Emergency
 * Stop in Settings) can stop it (#60). One per process in production ([QueueRunLock.active]);
 * tests build their own.
 */
internal class ActiveRun {
    private val current = AtomicReference<AbortSignal?>(null)

    fun publish(signal: AbortSignal) {
        current.set(signal)
    }

    /** Clears the slot only if it still holds [signal], so a later call's signal is never lost. */
    fun withdraw(signal: AbortSignal) {
        current.compareAndSet(signal, null)
    }

    /** @return whether a download was in flight to be asked */
    fun abortCurrent(): Boolean {
        val signal = current.get() ?: return false
        signal.request()
        return true
    }
}
