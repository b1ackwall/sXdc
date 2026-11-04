package com.v1per.sxdc.download

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The abort signal of the download in flight, if any, so something outside the service (Emergency
 * Stop in Settings) can stop it (#60). One per process in production ([QueueRunLock.active]);
 * tests build their own.
 */
internal class ActiveRun {
    private val current = AtomicReference<AbortSignal?>(null)

    private val stopEpoch = AtomicLong(0)

    /**
     * How many times [abortCurrent] has been called. A run that captures this before it starts
     * something slow (expansion, a claim) and sees it changed afterwards knows Emergency Stop was
     * pressed in between, whether or not a download was in flight then (#75).
     */
    fun epoch(): Long = stopEpoch.get()

    fun publish(signal: AbortSignal) {
        current.set(signal)
    }

    /** Clears the slot only if it still holds [signal], so a later call's signal is never lost. */
    fun withdraw(signal: AbortSignal) {
        current.compareAndSet(signal, null)
    }

    /** @return whether a download was in flight to be asked */
    fun abortCurrent(): Boolean {
        // First, so a run that publishes its signal after the read below still sees the change.
        stopEpoch.incrementAndGet()
        val signal = current.get() ?: return false
        signal.request()
        return true
    }
}
