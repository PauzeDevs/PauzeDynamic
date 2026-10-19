package dev.pauzex.dynamicisland

import android.app.Notification
import android.app.PendingIntent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper

data class NotificationSnapshot(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val body: String,
    val contentIntent: PendingIntent?,
    val actions: List<Notification.Action>,
    val receivedAtMillis: Long
)

object IslandState {
    @Volatile var title = "PauzeX"
    @Volatile var subtitle = "Ready"
    @Volatile var mediaPackage = ""
    @Volatile var charging = false
    @Volatile var batteryPercent = -1
    @Volatile var timerEndMillis = 0L
    @Volatile var timerFinishedUntilMillis = 0L
    @Volatile var downloadProgress = -1
    @Volatile var notificationPreview: NotificationSnapshot? = null
    @Volatile var mediaPlaying = false
    @Volatile var albumArt: Bitmap? = null
    @Volatile var mediaDurationMillis = 0L
    @Volatile var mediaPositionMillis = 0L
    @Volatile var mediaCanSeek = false

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    @Synchronized fun addListener(listener: () -> Unit) { listeners.add(listener) }
    @Synchronized fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    fun changed() {
        handler.post {
            val copy = synchronized(this) { listeners.toList() }
            copy.forEach { runCatching { it() } }
        }
    }
}
