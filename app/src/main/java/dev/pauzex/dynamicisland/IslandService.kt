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
    private var sessionManager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false
    private var isSeeking = false
    private var headlineTextView: TextView? = null
    private var timerLabelView: TextView? = null
    private var seekBarView: SeekBar? = null
    private var seekTimeView: TextView? = null
    private val prefs get() = getSharedPreferences(PREFS, MODE_PRIVATE)
    private val notificationPrefs get() = prefs

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra("level", -1)
                val scale = intent.getIntExtra("scale", 100).coerceAtLeast(1)
                IslandState.batteryPercent = if (level >= 0) level * 100 / scale else -1
                IslandState.charging = intent.getIntExtra("status", -1) in listOf(2, 5)
            } else {
                IslandState.charging = intent.action == Intent.ACTION_POWER_CONNECTED
            }
            IslandState.changed()
        }
    }

    private val stateListener: () -> Unit = { render() }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            applyMetadata(metadata)
            IslandState.changed()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            IslandState.mediaPlaying = state?.state == PlaybackState.STATE_PLAYING
            refreshMediaPosition()
            IslandState.changed()
        }

        override fun onSessionDestroyed() {
            handler.post { reloadMediaSessions() }
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        selectMediaController(sessions.orEmpty())
    }

    private val ticker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (IslandState.timerEndMillis > 0L && IslandState.timerEndMillis <= now) {
                IslandState.timerEndMillis = 0L
                IslandState.timerFinishedUntilMillis = now + 5_000L
            } else if (IslandState.timerFinishedUntilMillis in 1..now) {
                IslandState.timerFinishedUntilMillis = 0L
            }
            refreshMediaPosition()
            updateDynamicLabels()
            handler.postDelayed(this, 1000L)
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
        val pending = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        startForeground(
            NOTIFICATION_ID,
            builder.setContentTitle("PauzeX Dynamic Island")
                .setContentText("Floating indicator is running")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pending)
                .setOngoing(true)
                .build()
        )
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
            val manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            sessionManager = manager
            manager.addOnActiveSessionsChangedListener(
                sessionsListener,
                ComponentName(this, IslandNotificationListener::class.java),
                handler
            )
            reloadMediaSessions()
        }
    }

    private fun reloadMediaSessions() {
        runCatching {
            val listener = ComponentName(this, IslandNotificationListener::class.java)
            val sessions = sessionManager?.getActiveSessions(listener).orEmpty()
            selectMediaController(sessions)
        }
    }

    private fun selectMediaController(sessions: List<MediaController>) {
        val chosen = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
        if (controller?.sessionToken == chosen?.sessionToken) {
            refreshMediaPosition()
            return
        }
        runCatching { controller?.unregisterCallback(mediaCallback) }
        controller = chosen
        runCatching { controller?.registerCallback(mediaCallback, handler) }
        if (chosen == null) {
            IslandState.title = "PauzeX"
            IslandState.subtitle = "Ready"
            IslandState.mediaPackage = ""
            IslandState.albumArt = null
            IslandState.mediaPlaying = false
            IslandState.mediaDurationMillis = 0L
            IslandState.mediaPositionMillis = 0L
            IslandState.mediaCanSeek = false
        } else {
            applyMetadata(chosen.metadata)
            IslandState.mediaPlaying = chosen.playbackState?.state == PlaybackState.STATE_PLAYING
            refreshMediaPosition()
        }
        IslandState.changed()
    }

    private fun applyMetadata(metadata: MediaMetadata?) {
        IslandState.title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.takeIf { it.isNotBlank() } ?: "Now playing"
        IslandState.subtitle = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() } ?: controller?.packageName.orEmpty().ifBlank { "Media" }
        IslandState.mediaPackage = controller?.packageName.orEmpty()
        IslandState.albumArt = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        IslandState.mediaDurationMillis = (metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)
    }

    private fun refreshMediaPosition() {
        val state = controller?.playbackState
        val duration = (controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)
        var position = (state?.position ?: 0L).coerceAtLeast(0L)
        if (state?.state == PlaybackState.STATE_PLAYING) {
            val elapsed = (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0L)
            position += (elapsed * state.playbackSpeed).toLong().coerceAtLeast(0L)
        }
        IslandState.mediaDurationMillis = duration
        IslandState.mediaPositionMillis = if (duration > 0L) position.coerceAtMost(duration) else position
        IslandState.mediaCanSeek = state != null && (state.actions and PlaybackState.ACTION_SEEK_TO.toLong()) != 0L
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun accent(): Int = try {
        Color.parseColor(prefs.getString("accent", "#8B9CFF"))
    } catch (_: Exception) {
        Color.rgb(139, 156, 255)
    }

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
            setOnTouchListener { touchedView, event ->
                val params = overlayParams ?: return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        val location = IntArray(2)
                        touchedView.getLocationOnScreen(location)
                        startX = location[0]
                        startY = location[1]
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downRawX).toInt()
                        val dy = (event.rawY - downRawY).toInt()
                        if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) moved = true
                        if (moved) {
                            params.gravity = Gravity.TOP or Gravity.START
                            val maxX = (resources.displayMetrics.widthPixels - (pill?.width ?: dp(prefs.getInt("pill_width", 210)))).coerceAtLeast(0)
                            params.x = (startX + dx).coerceIn(0, maxX)
                            params.y = (startY + dy).coerceAtLeast(dp(4))
                            runCatching { wm.updateViewLayout(touchedView, params) }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            prefs.edit()
                                .putBoolean("custom_position", true)
                                .putInt("position_x", params.x)
                                .putInt("position_y", params.y)
                                .apply()
                        }
                        touchedView.performClick()
                        true
                    }
                    else -> false
                }
            }
        }
        pill = view
        val params = WindowManager.LayoutParams(
            dp(prefs.getInt("pill_width", 210)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        val customPosition = prefs.getBoolean("custom_position", false)
        params.gravity = if (customPosition) Gravity.TOP or Gravity.START else Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.x = if (customPosition) prefs.getInt("position_x", 0) else 0
        params.y = if (customPosition) prefs.getInt("position_y", dp(8)) else dp(8)
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
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun headline(): String {
        val preview = IslandState.notificationPreview
        return when {
            prefs.getBoolean("show_notifications", true) && preview != null ->
                "● " + (preview.title.ifBlank { preview.appLabel }).take(34)
            IslandState.timerEndMillis > System.currentTimeMillis() ->
                "Timer · " + timerRemaining()
            IslandState.timerFinishedUntilMillis > System.currentTimeMillis() -> "Timer finished"
            prefs.getBoolean("show_battery", true) && IslandState.charging ->
                "⚡ Charging · " + if (IslandState.batteryPercent >= 0) "${IslandState.batteryPercent}%" else ""
            prefs.getBoolean("show_progress", true) && IslandState.downloadProgress >= 0 ->
                "↓ Progress · ${IslandState.downloadProgress}%"
            prefs.getBoolean("show_media", true) && (IslandState.mediaPlaying || IslandState.title != "PauzeX") ->
                "♫ " + IslandState.title
            prefs.getBoolean("show_battery", true) && IslandState.batteryPercent >= 0 ->
                "Battery · ${IslandState.batteryPercent}%"
            else -> "✦  PAUZEX"
        }
    }

    private fun timerRemaining(): String {
        val left = (IslandState.timerEndMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        return "%02d:%02d".format(
            TimeUnit.MILLISECONDS.toMinutes(left),
            TimeUnit.MILLISECONDS.toSeconds(left) % 60
        )
    }

    private fun updateDynamicLabels() {
        headlineTextView?.text = headline()
        timerLabelView?.text = when {
            IslandState.timerEndMillis > 0L -> "Timer running · ${timerRemaining()}"
            IslandState.timerFinishedUntilMillis > System.currentTimeMillis() -> "Timer finished"
            else -> "Quick timers"
        }
        refreshMediaPosition()
        val duration = IslandState.mediaDurationMillis
        if (!isSeeking && duration > 0L) {
            seekBarView?.progress = ((IslandState.mediaPositionMillis * 1000L) / duration).toInt().coerceIn(0, 1000)
        }
        seekTimeView?.text = if (duration > 0L) "${formatTime(IslandState.mediaPositionMillis)} / ${formatTime(duration)}" else "Playback timeline unavailable"
    }

    private fun formatTime(millis: Long): String {
        val seconds = (millis.coerceAtLeast(0L) / 1000L).toInt()
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun render() {
        val view = pill ?: return
        view.removeAllViews()
        headlineTextView = null
        timerLabelView = null
        seekBarView = null
        seekTimeView = null
        view.background = pillBackground()

        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        top.addView(TextView(this).apply {
            text = "●"
            textSize = 8f
            setTextColor(accent())
            setPadding(0, 0, dp(7), 0)
        })
        headlineTextView = label(headline(), 12f)
        top.addView(headlineTextView)
        view.addView(top)

        if (expanded) {
            view.addView(View(this).apply { setBackgroundColor(Color.rgb(42, 44, 55)) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(9); bottomMargin = dp(9) })

            val preview = if (prefs.getBoolean("show_notifications", true)) IslandState.notificationPreview else null
            if (preview != null) renderNotification(view, preview)

            renderMedia(view)

            if (IslandState.downloadProgress >= 0 && prefs.getBoolean("show_progress", true)) {
                val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    this.progress = IslandState.downloadProgress
                    progressTintList = android.content.res.ColorStateList.valueOf(accent())
                }
                view.addView(progress, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(8); bottomMargin = dp(5) })
                view.addView(label("Progress · ${IslandState.downloadProgress}%", 10f, Color.LTGRAY))
            }

            if (prefs.getBoolean("show_battery", true) && IslandState.batteryPercent >= 0) {
                view.addView(label(
                    (if (IslandState.charging) "⚡ Charging" else "Battery") + " · ${IslandState.batteryPercent}%",
                    11f,
                    Color.LTGRAY
                ).apply { setPadding(0, dp(5), 0, dp(5)) })
            }

            renderTimers(view)
            view.addView(label("Drag to reposition · Tap outside controls to collapse", 9f, Color.GRAY)
                .apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        }

        val params = overlayParams
        if (params != null) {
            params.width = dp(if (expanded) 320 else prefs.getInt("pill_width", 210))
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            runCatching { wm.updateViewLayout(view, params) }
        }
        updateDynamicLabels()
    }

    private fun renderNotification(parent: LinearLayout, preview: NotificationSnapshot) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(15).toFloat()
                setColor(Color.rgb(18, 20, 29))
                setStroke(dp(1), Color.rgb(45, 48, 63))
            }
            isClickable = true
            setOnClickListener { openNotification(preview) }
        }
        card.addView(label(preview.appLabel, 10f, accent()))
        card.addView(label(preview.title, 12f))
        if (preview.body.isNotBlank()) card.addView(label(preview.body, 11f, Color.LTGRAY))
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })

        val actions = preview.actions.take(3)
        if (actions.isNotEmpty()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            actions.forEachIndexed { index, action ->
                val title = action.title?.toString()?.take(12)?.ifBlank { "Action" } ?: "Action"
                row.addView(actionButton(title) {
                    if (!action.remoteInputs.isNullOrEmpty()) {
                        val intent = Intent(this, NotificationReplyActivity::class.java)
                            .putExtra(NotificationReplyActivity.EXTRA_ACTION, action)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { startActivity(intent) }
                    } else {
                        runCatching { action.actionIntent.send() }
                    }
                }, LinearLayout.LayoutParams(0, dp(35), 1f).apply {
                    if (index > 0) leftMargin = dp(4)
                })
            }
            parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
        }
        if (preview.contentIntent != null) {
            parent.addView(actionButton("Open notification") { openNotification(preview) },
                LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(2); bottomMargin = dp(8) })
        }
    }

    private fun openNotification(preview: NotificationSnapshot) {
        val pending = preview.contentIntent
        if (pending == null) {
            Toast.makeText(this, "This notification has no open action.", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { pending.send() }.onFailure {
            Toast.makeText(this, "This notification is no longer available.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderMedia(parent: LinearLayout) {
        if (!prefs.getBoolean("show_media", true)) return
        val hasMedia = controller != null && IslandState.title != "PauzeX"
        if (!hasMedia) {
            parent.addView(label("No active media session", 10f, Color.GRAY)
                .apply { gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(5)) })
            return
        }

        val metadataRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        IslandState.albumArt?.let { bitmap ->
            val image = ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(Color.rgb(25, 27, 36)) }
                clipToOutline = true
            }
            metadataRow.addView(image, LinearLayout.LayoutParams(dp(50), dp(50)).apply { rightMargin = dp(9) })
        }
        val metadata = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        metadata.addView(label(IslandState.title, 13f))
        metadata.addView(label(IslandState.subtitle, 10f, Color.LTGRAY))
        metadataRow.addView(metadata, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(metadataRow)

        if (IslandState.mediaDurationMillis > 0L && IslandState.mediaCanSeek) {
            val seek = SeekBar(this).apply {
                max = 1000
                progressTintList = android.content.res.ColorStateList.valueOf(accent())
                thumbTintList = android.content.res.ColorStateList.valueOf(accent())
                progress = ((IslandState.mediaPositionMillis * 1000L) / IslandState.mediaDurationMillis).toInt().coerceIn(0, 1000)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            isSeeking = true
                            seekTimeView?.text = "${formatTime(IslandState.mediaDurationMillis * progress / 1000L)} / ${formatTime(IslandState.mediaDurationMillis)}"
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) { isSeeking = true }
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        val duration = IslandState.mediaDurationMillis
                        val target = duration * (seekBar?.progress ?: 0) / 1000L
                        runCatching { controller?.transportControls?.seekTo(target) }
                        isSeeking = false
                        refreshMediaPosition()
                        updateDynamicLabels()
                    }
                })
            }
            seekBarView = seek
            parent.addView(seek, LinearLayout.LayoutParams(-1, dp(32)).apply { topMargin = dp(3) })
            seekTimeView = label("", 9f, Color.GRAY).apply { gravity = Gravity.CENTER }
            parent.addView(seekTimeView)
        }

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val actions = controller?.playbackState?.actions ?: 0L
        controls.addView(actionButton("Previous", enabled = (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS.toLong()) != 0L) {
            runCatching { controller?.transportControls?.skipToPrevious() }
        }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(4) })
        controls.addView(actionButton(if (IslandState.mediaPlaying) "Pause" else "Play") {
            runCatching {
                val current = controller
                if (current != null) {
                    if (IslandState.mediaPlaying) current.transportControls.pause() else current.transportControls.play()
                }
            }
        }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
        controls.addView(actionButton("Next", enabled = (actions and PlaybackState.ACTION_SKIP_TO_NEXT.toLong()) != 0L) {
            runCatching { controller?.transportControls?.skipToNext() }
        }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(4) })
        parent.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7); bottomMargin = dp(7) })
    }

    private fun renderTimers(parent: LinearLayout) {
        timerLabelView = label("Quick timers", 10f, Color.LTGRAY).apply { setPadding(0, dp(4), 0, dp(3)) }
        parent.addView(timerLabelView)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf(1, 5, 10, 25).forEachIndexed { index, minutes ->
            row.addView(actionButton("${minutes}m") {
                IslandState.timerEndMillis = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes.toLong())
                IslandState.timerFinishedUntilMillis = 0L
                updateDynamicLabels()
            }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { if (index > 0) leftMargin = dp(4) })
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        if (IslandState.timerEndMillis > 0L || IslandState.timerFinishedUntilMillis > System.currentTimeMillis()) {
            parent.addView(actionButton("Cancel timer") {
                IslandState.timerEndMillis = 0L
                IslandState.timerFinishedUntilMillis = 0L
                updateDynamicLabels()
            }, LinearLayout.LayoutParams(-1, dp(34)).apply { topMargin = dp(5) })
        }
    }

    private fun actionButton(text: String, enabled: Boolean = true, click: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 10.5f
        gravity = Gravity.CENTER
        setTextColor(if (enabled) Color.WHITE else Color.GRAY)
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.5f
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.rgb(28, 30, 40))
            setStroke(dp(1), Color.rgb(55, 58, 72))
        }
        setOnClickListener { if (isEnabled) click() }
    }

    override fun onDestroy() {
        IslandState.removeListener(stateListener)
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        runCatching { controller?.unregisterCallback(mediaCallback) }
        handler.removeCallbacks(ticker)
        animator?.cancel()
        pill?.let { runCatching { wm.removeView(it) } }
        pill = null
        overlayParams = null
        headlineTextView = null
        timerLabelView = null
        seekBarView = null
        seekTimeView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
