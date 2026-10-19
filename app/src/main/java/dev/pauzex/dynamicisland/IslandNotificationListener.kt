package dev.pauzex.dynamicisland

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class IslandNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        // Rebuild snapshots after access is granted or the listener reconnects.
        IslandState.notificationPreviews = emptyList()
        IslandState.notificationPreview = null
        activeNotifications?.sortedByDescending { it.postTime }?.forEach { onNotificationPosted(it) }
        publishNewestPreview()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Ignore our own persistent foreground-service notification; otherwise it masks real notifications.
        if (sbn.packageName == packageName) return
        val notification = sbn.notification ?: return
        val prefs = getSharedPreferences(IslandService.PREFS, MODE_PRIVATE)
        val blocked = prefs.getStringSet("blocked_notification_apps", emptySet()) ?: emptySet()

        if (sbn.packageName in blocked) {
            removeSnapshot(sbn.key)
            return
        }

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        if (title.isBlank() && body.isBlank()) return

        val appLabel = runCatching {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(sbn.packageName)

        val observed = (prefs.getStringSet("observed_notification_packages", emptySet()) ?: emptySet()).toMutableSet()
        observed.add(sbn.packageName)
        prefs.edit()
            .putStringSet("observed_notification_packages", observed.toSet())
            .putString("notification_app_label_${sbn.packageName}", appLabel)
            .apply()

        val snapshot = NotificationSnapshot(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = title.ifBlank { appLabel },
            body = body,
            contentIntent = notification.contentIntent,
            actions = notification.actions?.toList().orEmpty(),
            receivedAtMillis = sbn.postTime
        )
        synchronized(this) {
            IslandState.notificationPreviews =
                (IslandState.notificationPreviews.filterNot { it.key == sbn.key } + snapshot)
                    .sortedByDescending { it.receivedAtMillis }
                    .take(50)
        }
        publishNewestPreview()

        IslandState.changed()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        removeSnapshot(sbn.key)
    }

    private fun removeSnapshot(key: String) {
        synchronized(this) {
            IslandState.notificationPreviews = IslandState.notificationPreviews.filterNot { it.key == key }
        }
        publishNewestPreview()
    }

    private fun publishNewestPreview() {
        val newest = IslandState.notificationPreviews.firstOrNull()
        IslandState.notificationPreview = newest
        val extras = newest?.let { snapshot ->
            activeNotifications?.firstOrNull { it.key == snapshot.key }?.notification?.extras
        }
        val max = extras?.getInt(Notification.EXTRA_PROGRESS_MAX, 0) ?: 0
        val progress = extras?.getInt(Notification.EXTRA_PROGRESS, 0) ?: 0
        IslandState.downloadProgress =
            if (max > 0 && extras?.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) != true) {
                (progress * 100f / max).toInt().coerceIn(0, 100)
            } else -1
        IslandState.changed()
    }
}
