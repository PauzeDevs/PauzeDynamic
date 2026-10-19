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
import android.view.animation.DecelerateInterpolator
import android.widget.*
import android.animation.ValueAnimator
import java.util.concurrent.TimeUnit

class IslandService : Service() {
    companion object {
        const val ACTION_START = "dev.pauzex.dynamicisland.START"
        private const val CHANNEL = "pauzex_island_service"
        private const val NOTIFICATION_ID = 4401
        const val PREFS = "island_settings"
    }
    private lateinit var wm: WindowManager
    private var pill: LinearLayout? = null
    private var expanded = false
    private var animator: ValueAnimator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var mediaCallback: MediaController.Callback? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false
    private val prefs get() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra("level", -1)
                val scale = intent.getIntExtra("scale", 100).coerceAtLeast(1)
                IslandState.batteryPercent = if (level >= 0) level * 100 / scale else -1
                IslandState.charging = intent.getIntExtra("status", -1) in listOf(2, 5)
            } else IslandState.charging = intent.action == Intent.ACTION_POWER_CONNECTED
            IslandState.changed()
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
            val sessions = mgr.getActiveSessions(listener)
            controller = sessions.firstOrNull()
            mediaCallback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    IslandState.title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Now playing"
                    IslandState.subtitle = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Media"
                    IslandState.albumArt = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    IslandState.changed()
                }
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    IslandState.mediaPlaying = state?.state == PlaybackState.STATE_PLAYING
                    IslandState.changed()
                }
            }
            controller?.registerCallback(mediaCallback!!)
            controller?.metadata?.let {
                IslandState.title = it.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Now playing"
                IslandState.subtitle = it.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Media"
                IslandState.albumArt = it.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            }
            IslandState.mediaPlaying = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun accent(): Int = try { Color.parseColor(prefs.getString("accent", "#8B9CFF")) } catch (_: Exception) { Color.rgb(139, 156, 255) }
    private fun pillBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(if (expanded) 30 else 40).toFloat()
        setColor(Color.rgb(9, 10, 15))
        setStroke(dp(1), Color.argb(170, Color.red(accent()), Color.green(accent()), Color.blue(accent())))
    }

    private fun attachOverlay() {
        if (!Settings.canDrawOverlays(this) || pill != null) return
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = pillBackground()
            elevation = dp(14).toFloat()
            clipToOutline = true
            setOnClickListener {
                if (!moved) {
                    expanded = !expanded
                    animateToggle()
                    render()
                }
                moved = false
            }
            setOnTouchListener { _, event ->
                val params = overlayParams ?: return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX; downRawY = event.rawY
                        startX = params.x; startY = params.y; moved = false; true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downRawX).toInt()
                        val dy = (event.rawY - downRawY).toInt()
                        if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) moved = true
                        if (moved) {
                            params.gravity = Gravity.TOP or Gravity.START
                            params.x = (startX + dx).coerceAtLeast(0)
                            params.y = (startY + dy).coerceAtLeast(dp(4))
                            runCatching { wm.updateViewLayout(this, params) }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) prefs.edit().putInt("position_x", params.x).putInt("position_y", params.y).apply()
                        performClick()
                        true
                    }
                    else -> false
                }
            }
        }
        pill = view
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(dp(prefs.getInt("pill_width", 210)), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
        params.gravity = if (prefs.getBoolean("custom_position", false)) Gravity.TOP or Gravity.START else Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.x = prefs.getInt("position_x", 0)
        params.y = prefs.getInt("position_y", dp(8))
        overlayParams = params
        runCatching { wm.addView(view, params) }.onFailure { pill = null; overlayParams = null }
        render()
    }

    private fun animateToggle() {
        val view = pill ?: return
        animator?.cancel()
        view.animate().cancel()
        view.scaleX = 0.94f
        view.scaleY = 0.94f
        view.alpha = 0.86f
        view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(230)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun label(value: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun render() {
        val view = pill ?: return
        view.removeAllViews()
        view.background = pillBackground()
        val headline = when {
            prefs.getBoolean("show_battery", true) && IslandState.charging ->
                "⚡ Charging · " + if (IslandState.batteryPercent >= 0) "${IslandState.batteryPercent}%" else ""
            prefs.getBoolean("show_progress", true) && IslandState.downloadProgress >= 0 -> "↓ Progress · ${IslandState.downloadProgress}%"
            prefs.getBoolean("show_notifications", true) && IslandState.latestNotification.isNotBlank() -> "● " + IslandState.latestNotification.take(34)
            prefs.getBoolean("show_media", true) && (IslandState.mediaPlaying || IslandState.title != "PauzeX") -> "♫ " + IslandState.title
            prefs.getBoolean("show_battery", true) && IslandState.batteryPercent >= 0 -> "Battery · ${IslandState.batteryPercent}%"
            else -> "✦  PAUZEX"
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        val dot = TextView(this).apply { text = "●"; textSize = 8f; setTextColor(accent()); setPadding(0, 0, dp(7), 0) }
        top.addView(dot)
        top.addView(label(headline, 12f))
        view.addView(top)
        if (expanded) {
            val divider = View(this).apply { setBackgroundColor(Color.rgb(42, 44, 55)) }
            view.addView(divider, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(9); bottomMargin = dp(9) })
            val title = if (IslandState.title.isNotBlank()) IslandState.title else "PauzeX Dynamic"
            view.addView(label(title, 14f))
            view.addView(label(IslandState.subtitle.ifBlank { "Your live activities" }, 11f, Color.LTGRAY))
            if (IslandState.downloadProgress >= 0 && prefs.getBoolean("show_progress", true)) {
                val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100; progress = IslandState.downloadProgress; progressTintList = android.content.res.ColorStateList.valueOf(accent())
                }
                view.addView(bar, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(9); bottomMargin = dp(4) })
            }
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
            row.addView(actionButton(if (IslandState.timerEndMillis > 0L) "Timer on" else "1 min timer") {
                IslandState.timerEndMillis = if (IslandState.timerEndMillis > 0L) 0L else System.currentTimeMillis() + 60_000
                IslandState.subtitle = if (IslandState.timerEndMillis > 0L) "Timer · 01:00" else "Ready"
                IslandState.changed()
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(5) })
            row.addView(actionButton(if (IslandState.mediaPlaying) "Pause" else "Play") {
                runCatching {
                    val c = controller
                    if (c != null) { if (IslandState.mediaPlaying) c.transportControls.pause() else c.transportControls.play() }
                    else Toast.makeText(this@IslandService, "No active media session. Start playback first.", Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(5) })
            view.addView(row)
            view.addView(label("Drag to reposition · Tap to collapse", 9f, Color.GRAY).apply { setPadding(0, dp(6), 0, 0) })
        }
        val params = overlayParams
        if (params != null) {
            params.width = dp(if (expanded) 310 else prefs.getInt("pill_width", 210))
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            runCatching { wm.updateViewLayout(view, params) }
        }
    }

    private fun actionButton(text: String, click: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 11f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.rgb(28, 30, 40)); setStroke(dp(1), Color.rgb(55, 58, 72)) }
        setOnClickListener { click() }
    }

    override fun onDestroy() {
        IslandState.removeListener(stateListener)
        runCatching { unregisterReceiver(batteryReceiver) }
        controller?.let { c -> mediaCallback?.let { cb -> runCatching { c.unregisterCallback(cb) } } }
        handler.removeCallbacks(ticker)
        animator?.cancel()
        pill?.let { runCatching { wm.removeView(it) } }
        pill = null; overlayParams = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}