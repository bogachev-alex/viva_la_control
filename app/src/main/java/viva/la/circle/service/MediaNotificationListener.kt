package viva.la.circle.service

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Thin listener so [android.media.session.MediaSessionManager.getActiveSessions] is allowed.
 * Does not read notification contents.
 */
class MediaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    companion object {
        @Volatile
        var connected: Boolean = false
            private set

        fun componentName(context: Context): ComponentName =
            ComponentName(context, MediaNotificationListener::class.java)
    }
}
