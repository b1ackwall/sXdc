package com.v1per.sxdc.download

import android.content.Context
import android.content.SharedPreferences
import com.v1per.sxdc.settings.SxdcSettings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The persisted queue halt: the fatal stop that has to survive onResume, rotation and process
 * death, so that a return to the app does not quietly retry (and fail) the next queued link.
 *
 * Lives in the settings file ([SxdcSettings.prefs]), so instrumented tests get their isolated
 * copy for free. No in-memory cache: every call reads the preferences, so a test that clears the
 * file behind this object's back can never leave a stale halt in memory.
 */
object QueueHaltStore {

    private const val KEY_KIND = "queue_halt_kind"
    private const val KEY_HALTED_AT = "queue_halt_at"

    /** The halt, or null when none is set or what is stored is incomplete or unknown. */
    fun read(context: Context): QueueHalt? = read(SxdcSettings.prefs(context))

    fun set(context: Context, kind: HaltKind, haltedAt: Long) {
        SxdcSettings.prefs(context).edit()
            .putString(KEY_KIND, kind.id)
            .putLong(KEY_HALTED_AT, haltedAt)
            .apply()
    }

    /**
     * Removes the two keys one by one. Not edit().clear(): before API 30 clear() does not notify
     * change listeners, and minSdk is 28, so [observe] would miss it.
     */
    fun clear(context: Context) {
        SxdcSettings.prefs(context).edit()
            .remove(KEY_KIND)
            .remove(KEY_HALTED_AT)
            .apply()
    }

    /** The halt as it changes, starting with the current value. For the UI. */
    fun observe(context: Context): Flow<QueueHalt?> = callbackFlow {
        val prefs = SxdcSettings.prefs(context)
        // A strong reference held by this coroutine until close: SharedPreferences keeps its
        // listeners in a weak map, so a lambda passed inline could be collected and go silent.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == null || key == KEY_KIND || key == KEY_HALTED_AT) trySend(read(p))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read(prefs))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    private fun read(prefs: SharedPreferences): QueueHalt? {
        val kind = HaltKind.fromId(prefs.getString(KEY_KIND, null))
        return if (kind != null && prefs.contains(KEY_HALTED_AT)) {
            QueueHalt(kind, prefs.getLong(KEY_HALTED_AT, 0L))
        } else {
            null
        }
    }
}
