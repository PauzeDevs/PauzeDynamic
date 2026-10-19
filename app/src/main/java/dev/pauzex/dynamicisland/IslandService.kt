package dev.pauzex.dynamicisland

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import java.util.concurrent.TimeUnit

class IslandService : Service() {
    companion object {
        const val ACTION_START = "dev.pauzex.dynamicisland.START"
        private const val CHANNEL = "pauzex_island_service"
        private const val NOTIFICATION_ID = 4401
    }
    private lateinit var wm: WindowManager
    private var pill: LinearLayout? = null
    private var expanded = false
    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra("level", -1)
                val scale = intent.getIntExtra("scale", 100).coerceAtLeast(1)
                IslandState.batteryPercent = if (level >= 0) level * 100 / scale else -1
                IslandState.charging = intent.getIntExtra("status", -1) in listOf(2, 5)
                IslandState.changed()
            } else {
                IslandState.charging = intent.action == Intent.ACTION_POWER_CONNECTED
                IslandState.changed()
            }
        }
    }
    private val stateListener: () -> Unit = { render() }
    private val ticker = object : Runnable {
        override fun run() {
            val end = IslandState.timerEndMillis
            if (end > 0L) {
                val left = (end - System.currentTimeMillis()).coerceAtLeast(0)
                IslandState.subtitle = if (left == 0L) "Timer finished" else
                    "Timer · " + "%02d:%02d".format(TimeUnit.MILLISECONDS.toMinutes(left), TimeUnit.MILLISECONDS.toSeconds(left) % 60)
                if (left == 0L) IslandState.timerEndMillis = 0L
                render()
            }
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "Dynamic Island service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(NOTIFICATION_ID, builder.setContentTitle("PauzeX Dynamic Island").setContentText("Floating indicator is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(pending).setOngoing(true).build())
        IslandState.addListener(stateListener)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_POWER_CONNECTED))
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_POWER_DISCONNECTED))
        handler.post(ticker)
        attachMedia()
        attachOverlay()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START && pill == null && Settings.canDrawOverlays(this)) attachOverlay()
        return START_STICKY
    }
    private fun attachMedia() {
        runCatching {
            val mgr = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val listener = ComponentName(this, IslandNotificationListener::class.java)
            controller = mgr.getActiveSessions(listener).firstOrNull()
            controller?.registerCallback(object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    IslandState.title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Now playing"
                    IslandState.subtitle = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Media"
                    IslandState.changed()
                }
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    IslandState.mediaPlaying = state?.state == PlaybackState.STATE_PLAYING
                    IslandState.changed()
                }
            })
        }
    }
    private fun pillBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = 1000f
        setColor(Color.rgb(5, 6, 10)); setStroke(1, Color.rgb(57, 60, 74))
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun attachOverlay() {
        if (!Settings.canDrawOverlays(this) || pill != null) return
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8)); background = pillBackground()
            elevation = dp(10).toFloat()
            setOnClickListener { expanded = !expanded; render() }
        }
        pill = view
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(dp(190), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; params.y = dp(8)
        runCatching { wm.addView(view, params) }.onFailure { pill = null }
        render()
    }
    private fun render() {
        val view = pill ?: return
        view.removeAllViews()
        view.background = pillBackground()
        val headline = when {
            IslandState.charging -> "⚡ Charging · " + if (IslandState.batteryPercent >= 0) IslandState.batteryPercent.toString() + "%" else ""
            IslandState.downloadProgress >= 0 -> "↓ Download · " + IslandState.downloadProgress + "%"
            IslandState.latestNotification.isNotBlank() -> "● " + IslandState.latestNotification.take(30)
            IslandState.mediaPlaying -> "♫ " + IslandState.title
            else -> "● PauzeX"
        }
        view.addView(TextView(this).apply {
            text = headline; setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (expanded) {
            view.addView(TextView(this).apply {
                text = IslandState.subtitle; setTextColor(Color.LTGRAY); textSize = 12f; gravity = Gravity.CENTER
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
            row.addView(Button(this).apply {
                text = "Start 1 min"
                setOnClickListener { IslandState.timerEndMillis = System.currentTimeMillis() + 60_000; IslandState.changed() }
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
            row.addView(Button(this).apply {
                text = if (IslandState.mediaPlaying) "Pause" else "Play"
                setOnClickListener { runCatching {
                    val c = controller
                    if (c != null) { if (IslandState.mediaPlaying) c.transportControls.pause() else c.transportControls.play() }
                    else Toast.makeText(this@IslandService, "No active media session", Toast.LENGTH_SHORT).show()
                } }
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
            view.addView(row)
        }
        (view.layoutParams as? WindowManager.LayoutParams)?.let {
            it.width = dp(if (expanded) 300 else 190); runCatching { wm.updateViewLayout(view, it) }
        }
    }
    override fun onDestroy() {
        IslandState.removeListener(stateListener); runCatching { unregisterReceiver(batteryReceiver) }
        handler.removeCallbacks(ticker); pill?.let { runCatching { wm.removeView(it) } }; pill = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
