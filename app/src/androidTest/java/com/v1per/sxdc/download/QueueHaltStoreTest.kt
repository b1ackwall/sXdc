package com.v1per.sxdc.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.sxdc.TestSettingsRule
import com.v1per.sxdc.TestSxdcApp
import com.v1per.sxdc.settings.SxdcSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueHaltStoreTest {

    @get:Rule
    val settingsRule = TestSettingsRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun usesTheIsolatedTestSettingsFile() {
        QueueHaltStore.set(context, HaltKind.STORAGE, 42L)

        assertTrue(settingsRule.prefs.all.keys.any { it.startsWith("queue_halt") })
        assertEquals(TestSxdcApp.TEST_SETTINGS_NAME, (context.applicationContext as TestSxdcApp).settingsName)
        val real = context.getSharedPreferences(SxdcSettings.PREFS, android.content.Context.MODE_PRIVATE)
        assertFalse(real.all.keys.any { it.startsWith("queue_halt") })
    }

    @Test
    fun nothingSet_readsNull() {
        assertNull(QueueHaltStore.read(context))
    }

    @Test
    fun set_thenRead_returnsKindAndTime_forEveryKind() {
        HaltKind.entries.forEachIndexed { i, kind ->
            QueueHaltStore.set(context, kind, 1_000L + i)
            assertEquals(QueueHalt(kind, 1_000L + i), QueueHaltStore.read(context))
        }
    }

    @Test
    fun clear_removesTheHalt_andLeavesOtherSettingsAlone() {
        settingsRule.prefs.edit().putString("ytdlp_extra_args", "--keep-me").commit()
        QueueHaltStore.set(context, HaltKind.SETUP, 7L)

        QueueHaltStore.clear(context)

        assertNull(QueueHaltStore.read(context))
        assertEquals("--keep-me", SxdcSettings.extraArgs(context))
    }

    @Test
    fun survivesReReading_fromTheFileOnDisk() {
        QueueHaltStore.set(context, HaltKind.RATE_LIMITED, 99L)
        // Re-read many times and through a fresh lookup: there is no cache to go stale.
        repeat(3) { assertEquals(QueueHalt(HaltKind.RATE_LIMITED, 99L), QueueHaltStore.read(context)) }
        assertEquals(HaltKind.RATE_LIMITED.id, settingsRule.prefs.getString("queue_halt_kind", null))
        assertEquals(99L, settingsRule.prefs.getLong("queue_halt_at", 0L))
    }

    @Test
    fun unknownOrIncompleteStoredValue_readsNull() {
        settingsRule.prefs.edit().putString("queue_halt_kind", "network").putLong("queue_halt_at", 1L).commit()
        assertNull(QueueHaltStore.read(context))

        settingsRule.prefs.edit().clear().putString("queue_halt_kind", "storage").commit()
        assertNull(QueueHaltStore.read(context))
    }

    @Test
    fun observe_emitsCurrentValue_thenOnSet_thenOnRemoval() = runBlocking {
        val seen = Channel<QueueHalt?>(Channel.UNLIMITED)
        scope.launch { QueueHaltStore.observe(context).collect { seen.send(it) } }

        withTimeout(TIMEOUT_MS) {
            assertNull(seen.receive())

            QueueHaltStore.set(context, HaltKind.STORAGE, 5L)
            assertEquals(QueueHalt(HaltKind.STORAGE, 5L), seen.receive())

            QueueHaltStore.clear(context)
            assertNull(seen.receive())
        }
    }

    @Test
    fun observe_startsWithAHaltSetBeforeCollection() = runBlocking {
        QueueHaltStore.set(context, HaltKind.SETUP, 3L)

        val first = withTimeout(TIMEOUT_MS) { QueueHaltStore.observe(context).first() }

        assertEquals(QueueHalt(HaltKind.SETUP, 3L), first)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
