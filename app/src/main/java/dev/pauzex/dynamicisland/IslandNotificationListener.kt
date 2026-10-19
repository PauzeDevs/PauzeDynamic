package dev.pauzex.dynamicisland

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class IslandNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && body.isBlank()) return
        IslandState.latestNotification = title.ifBlank { body }
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val progress = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        IslandState.downloadProgress = if (max > 0 && !extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
            (progress * 100f / max).toInt().coerceIn(0, 100) else -1
        IslandState.changed()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Don't clear a newer notification when an older notification disappears.
        val extras = sbn.notification.extras ?: return
        val removedTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        if (removedTitle == IslandState.latestNotification) {
            IslandState.latestNotification = ""
            IslandState.downloadProgress = -1
            IslandState.changed()
        }
    }
}