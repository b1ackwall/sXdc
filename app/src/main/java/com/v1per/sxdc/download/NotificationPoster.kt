package com.v1per.sxdc.download

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.content.Context

/**
 * Hands a notification to the system. A seam so tests can record, drop or fail posts without the
 * real notification shade (#22). [Notifications.post] wraps every call.
 */
fun interface NotificationPoster {
    fun notify(id: Int, notification: Notification)
}

/** The real poster: [NotificationManager.notify]. */
class SystemNotificationPoster(context: Context) : NotificationPoster {

    private val manager = context.applicationContext.getSystemService(NotificationManager::class.java)

    // Callers go through Notifications.post, which handles a missing POST_NOTIFICATIONS grant.
    @SuppressLint("MissingPermission")
    override fun notify(id: Int, notification: Notification) {
        manager.notify(id, notification)
    }
}
