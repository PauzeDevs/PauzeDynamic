package dev.pauzex.dynamicisland

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper

object IslandState {
    @Volatile var title = "PauzeX"
    @Volatile var subtitle = "Ready"
    @Volatile var charging = false
    @Volatile var batteryPercent = -1
    @Volatile var timerEndMillis = 0L
    @Volatile var downloadProgress = -1
    @Volatile var latestNotification = ""
    @Volatile var mediaPlaying = false
    @Volatile var albumArt: Bitmap? = null
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