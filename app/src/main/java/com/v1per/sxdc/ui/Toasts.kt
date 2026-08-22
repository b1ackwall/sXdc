package com.v1per.sxdc.ui

import android.content.Context
import android.widget.Toast
import androidx.annotation.MainThread

/**
 * One toast at a time, newest wins.
 *
 * The share flow says two things in quick succession -- "checking" from the activity, then the
 * queue result from the service -- and Android's default behaviour is to *queue* the second
 * behind the first for its full duration. That would leave the user reading a stale line for
 * seconds after the real answer was known, so the previous toast is cancelled rather than
 * followed.
 *
 * Holds the application context, never the caller's, so a retained reference cannot outlive and
 * leak an Activity.
 */
object Toasts {

    private var current: Toast? = null

    @MainThread
    fun showReplacing(context: Context, message: String) {
        current?.cancel()
        current = Toast
            .makeText(context.applicationContext, message, Toast.LENGTH_SHORT)
            .also { it.show() }
    }
}
