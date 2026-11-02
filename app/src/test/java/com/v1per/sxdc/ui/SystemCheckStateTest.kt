package com.v1per.sxdc.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/** A System check load that throws becomes a Failed state instead of a crash (#68). */
class SystemCheckStateTest {

    /** Like production's appScope: a SupervisorJob, so a failed load does not fail the scope. */
    private fun TestScope.supervised() =
        CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext.job))

    private val diagnostics = Diagnostics("hi", 4, "2026.01.01", NativeDiagnostics.Failed("n/a"))

    @Test
    fun success_isLoaded_andCached() = runTest {
        val cache = OnceCache<Diagnostics>(supervised())

        assertEquals(SystemCheckState.Pending, cache.initialState())
        assertEquals(SystemCheckState.Loaded(diagnostics), loadSystemCheck(cache) { diagnostics })
        assertEquals(SystemCheckState.Loaded(diagnostics), cache.initialState())
    }

    @Test
    fun failure_returnsFailedWithClassAndMessage_andIsNotCached() = runTest {
        val cache = OnceCache<Diagnostics>(supervised())

        val state = loadSystemCheck(cache) { throw IllegalStateException("No module named 'yt_dlp'") }

        assertEquals(SystemCheckState.Failed("IllegalStateException: No module named 'yt_dlp'"), state)
        assertNull(cache.peek())
        assertEquals(SystemCheckState.Pending, cache.initialState())
    }

    @Test
    fun failureWithoutMessage_showsTheClassOnly() = runTest {
        val cache = OnceCache<Diagnostics>(supervised())

        val state = loadSystemCheck(cache) { throw IllegalStateException() }

        assertEquals(SystemCheckState.Failed("IllegalStateException"), state)
    }

    @Test
    fun retryAfterFailure_succeeds() = runTest {
        val cache = OnceCache<Diagnostics>(supervised())
        var calls = 0

        loadSystemCheck(cache) { calls++; throw IllegalStateException("boom") }
        val second = loadSystemCheck(cache) { calls++; diagnostics }

        assertEquals(SystemCheckState.Loaded(diagnostics), second)
        assertEquals(2, calls)
    }

    @Test
    fun cancelledCaller_isPassedOn_notTurnedIntoFailed() = runTest {
        val cache = OnceCache<Diagnostics>(supervised())
        val gate = CompletableDeferred<Diagnostics>()

        val caller = async { loadSystemCheck(cache) { gate.await() } }
        runCurrent()
        caller.cancel()

        val thrown = runCatching { caller.await() }.exceptionOrNull()
        assertTrue("expected cancellation, got $thrown", thrown is CancellationException)
        // The load itself keeps running for the next caller, which then gets the real result.
        gate.complete(diagnostics)
        assertEquals(SystemCheckState.Loaded(diagnostics), loadSystemCheck(cache) { error("not rerun") })
    }

    @Test
    fun cancellationThrownByTheLoader_isPassedOn() = runTest {
        val cache = OnceCache<Diagnostics>(supervised())

        val thrown = runCatching { loadSystemCheck(cache) { throw CancellationException("stop") } }.exceptionOrNull()

        assertTrue("expected cancellation, got $thrown", thrown is CancellationException)
        assertNull(cache.peek())
    }
}
