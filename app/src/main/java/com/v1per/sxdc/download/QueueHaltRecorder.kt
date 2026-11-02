package com.v1per.sxdc.download

import android.content.Context

/** The slice of the persisted halt that [QueueProcessor] uses. */
internal interface QueueHaltRecorder {
    /** Records a fatal stop. Overwrites any halt already set. */
    fun halt(kind: HaltKind, now: Long)

    fun current(): QueueHalt?

    fun clear()
}

/** One-to-one delegation to [QueueHaltStore]. */
internal class PrefsQueueHaltRecorder(context: Context) : QueueHaltRecorder {
    private val context = context.applicationContext

    override fun halt(kind: HaltKind, now: Long) = QueueHaltStore.set(context, kind, now)

    override fun current(): QueueHalt? = QueueHaltStore.read(context)

    override fun clear() = QueueHaltStore.clear(context)
}
