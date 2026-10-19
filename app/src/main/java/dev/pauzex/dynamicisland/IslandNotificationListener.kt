package dev.pauzex.dynamicisland

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class IslandNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        // Seed the island from existing notifications after access is granted or the device reconnects.
        activeNotifications?.sortedBy { it.postTime }?.forEach { onNotificationPosted(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Ignore our own persistent foreground-service notification; otherwise it masks real notifications.
        if (sbn.packageName == packageName) return
        val notification = sbn.notification ?: return
        val prefs = getSharedPreferences(IslandService.PREFS, MODE_PRIVATE)
        val blocked = prefs.getStringSet("blocked_notification_apps", emptySet()) ?: emptySet()

        if (sbn.packageName in blocked) {
            if (IslandState.notificationPreview?.key == sbn.key) {
                IslandState.notificationPreview = null
                IslandState.downloadProgress = -1
                IslandState.changed()
            }
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

        IslandState.notificationPreview = NotificationSnapshot(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = title.ifBlank { appLabel },
            body = body,
            contentIntent = notification.contentIntent,
            actions = notification.actions?.toList().orEmpty(),
            receivedAtMillis = System.currentTimeMillis()
        )

        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val progress = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        IslandState.downloadProgress =
            if (max > 0 && !extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)) {
                (progress * 100f / max).toInt().coerceIn(0, 100)
            } else {
                -1
            }
        IslandState.changed()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Match by notification key so removal of an older notification cannot clear a newer one.
        if (IslandState.notificationPreview?.key == sbn.key) {
            IslandState.notificationPreview = null
            IslandState.downloadProgress = -1
            IslandState.changed()
        }
    }
}
