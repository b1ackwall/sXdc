package com.v1per.sxdc.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The per-process System check cache (#21): one load per process, shared by every caller. */
class OnceCacheTest {

    /** Like production's appScope: a SupervisorJob, so a failed load does not fail the scope. */
    private fun TestScope.supervised() =
        CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext.job))

    @Test
    fun secondGet_returnsCachedResult_withoutRerunningLoader() = runTest {
        val cache = OnceCache<String>(supervised())
        var calls = 0
        val loader: suspend () -> String = { calls++; "result-$calls" }

        assertEquals("result-1", cache.get(loader))
        assertEquals("result-1", cache.get(loader))
        assertEquals(1, calls)
        assertEquals("result-1", cache.peek())
    }

    @Test
    fun peek_isNullUntilTheLoadFinishes() = runTest {
        val cache = OnceCache<String>(supervised())
        val gate = CompletableDeferred<Unit>()
        val waiter = async { cache.get { gate.await(); "done" } }
        runCurrent()

        assertNull(cache.peek())
        gate.complete(Unit)
        assertEquals("done", waiter.await())
        assertEquals("done", cache.peek())
    }

    @Test
    fun concurrentGets_shareOneLoad() = runTest {
        val cache = OnceCache<String>(supervised())
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val loader: suspend () -> String = { calls++; gate.await(); "shared" }

        val first = async { cache.get(loader) }
        val second = async { cache.get(loader) }
        runCurrent()
        gate.complete(Unit)

        assertEquals("shared", first.await())
        assertEquals("shared", second.await())
        assertEquals(1, calls)
    }

    @Test
    fun cancelledCaller_doesNotCancelTheLoad_soTheNextCallerReusesIt() = runTest {
        // The rotation case: the first activity's wait is cancelled mid-load.
        val cache = OnceCache<String>(supervised())
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val loader: suspend () -> String = { calls++; gate.await(); "survived" }

        val first = launch { cache.get(loader) }
        runCurrent()
        first.cancel()
        runCurrent()

        val second = async { cache.get(loader) }
        runCurrent()
        gate.complete(Unit)

        assertEquals("survived", second.await())
        assertEquals(1, calls)
    }

    @Test
    fun failedLoad_isNotCached_andTheNextGetRetries() = runTest {
        val cache = OnceCache<String>(supervised())
        var calls = 0

        try {
            cache.get { calls++; throw IllegalStateException("boom") }
            fail("expected the loader's exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("boom"))
        }
        assertNull(cache.peek())

        assertEquals("ok", cache.get { calls++; "ok" })
        assertEquals(2, calls)
    }
}
