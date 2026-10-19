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
    private var panelPage = 0
    private var horizontalGesture = false
    private var verticalDismissGesture = false
    private var touchStartedExpanded = false
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

    private fun mediaAppLabel(packageName: String?): String {
        if (packageName.isNullOrBlank()) return "Media player"
        return runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault("Media player")
    }

    private fun applyMetadata(metadata: MediaMetadata?) {
        val appLabel = mediaAppLabel(controller?.packageName)
        IslandState.title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.takeIf { it.isNotBlank() } ?: "Audio session"
        IslandState.subtitle = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() } ?: appLabel
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

    private fun pillBackground(): GradientDrawable {
        val savedTheme = prefs.getString("pill_theme", "classic") ?: "classic"
        val theme = if (prefs.getBoolean("pro_unlocked", false)) savedTheme else "classic"
        val colors = when (theme) {
            "midnight" -> intArrayOf(Color.rgb(17, 23, 43), Color.rgb(5, 7, 15))
            "emerald" -> intArrayOf(Color.rgb(12, 32, 28), Color.rgb(4, 10, 9))
            "violet" -> intArrayOf(Color.rgb(31, 17, 43), Color.rgb(8, 5, 12))
            "graphite" -> intArrayOf(Color.rgb(29, 30, 34), Color.rgb(7, 8, 10))
            else -> intArrayOf(Color.rgb(12, 13, 17), Color.rgb(3, 4, 6))
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(if (expanded) 29 else 32).toFloat()
            setStroke(dp(1), Color.rgb(43, 45, 52))
        }
    }

    private fun attachOverlay() {
        if (!Settings.canDrawOverlays(this) || pill != null) return
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(9), dp(14), dp(9))
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
                        touchStartedExpanded = expanded
                        horizontalGesture = false
                        verticalDismissGesture = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downRawX).toInt()
                        val dy = (event.rawY - downRawY).toInt()
                        if (touchStartedExpanded) {
                            if (kotlin.math.abs(dx) > dp(38) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.2f) {
                                horizontalGesture = true
                                moved = true
                            } else if (dy > dp(42) && dy > kotlin.math.abs(dx) * 1.2f) {
                                verticalDismissGesture = true
                                moved = true
                            } else if (kotlin.math.abs(dx) > dp(8) || kotlin.math.abs(dy) > dp(8)) {
                                moved = true
                            }
                        } else {
                            if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) moved = true
                            if (moved) {
                                params.gravity = Gravity.TOP or Gravity.START
                                val maxX = (resources.displayMetrics.widthPixels - (pill?.width ?: dp(prefs.getInt("pill_width", 210)))).coerceAtLeast(0)
                                params.x = (startX + dx).coerceIn(0, maxX)
                                params.y = (startY + dy).coerceAtLeast(dp(4))
                                runCatching { wm.updateViewLayout(touchedView, params) }
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = (event.rawX - downRawX).toInt()
                        if (horizontalGesture) {
                            panelPage = (panelPage + if (dx < 0) 1 else 2) % 3
                            render()
                        } else if (verticalDismissGesture) {
                            expanded = false
                            animateToggle()
                            render()
                        } else if (moved && !touchStartedExpanded) {
                            prefs.edit()
                                .putBoolean("custom_position", true)
                                .putInt("position_x", params.x)
                                .putInt("position_y", params.y)
                                .apply()
                        }
                        touchedView.performClick()
                        horizontalGesture = false
                        verticalDismissGesture = false
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
            prefs.getBoolean("show_low_battery", true) && !IslandState.charging &&
                IslandState.batteryPercent in 0..prefs.getInt("low_battery_threshold", 15).coerceIn(5, 30) -> "⚠ Low battery · ${IslandState.batteryPercent}%"
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
        view.orientation = LinearLayout.VERTICAL
        view.gravity = Gravity.CENTER_VERTICAL
        view.setPadding(
            dp(if (expanded) 18 else 14),
            dp(if (expanded) 15 else 9),
            dp(if (expanded) 18 else 14),
            dp(if (expanded) 14 else 9)
        )
        view.background = pillBackground()

        if (!expanded) {
            val compact = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            compact.addView(TextView(this).apply {
                text = "•"
                textSize = 16f
                setTextColor(if (IslandState.mediaPlaying) Color.rgb(116, 232, 194) else accent())
                gravity = Gravity.CENTER
                setPadding(0, 0, dp(7), 0)
            })
            headlineTextView = label(headline(), 11.5f).apply {
                maxLines = 1
                letterSpacing = 0.01f
                setTextColor(Color.rgb(246, 246, 249))
            }
            compact.addView(headlineTextView, LinearLayout.LayoutParams(0, -2, 1f))
            view.addView(compact)
        } else {
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(TextView(this).apply {
                text = "•"
                textSize = 13f
                setTextColor(if (IslandState.mediaPlaying) Color.rgb(116, 232, 194) else accent())
                gravity = Gravity.CENTER
                setPadding(0, 0, dp(6), 0)
            })
            header.addView(label("LIVE ACTIVITY", 9.5f, Color.rgb(174, 177, 187)).apply {
                letterSpacing = 0.12f
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val collapse = TextView(this).apply {
                text = "⌄"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(208, 210, 218))
                background = roundBackground(Color.rgb(30, 32, 38), dp(14), Color.rgb(48, 50, 58))
                setOnClickListener {
                    expanded = false
                    animateToggle()
                    render()
                }
            }
            header.addView(collapse, LinearLayout.LayoutParams(dp(30), dp(30)))
            view.addView(header)

            view.addView(View(this).apply { setBackgroundColor(Color.rgb(38, 40, 47)) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(10) })

            val tabs = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            listOf("Music", "Alerts", "Tools").forEachIndexed { index, title ->
                val selected = panelPage == index
                val tab = TextView(this).apply {
                    text = title
                    textSize = 11f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    gravity = Gravity.CENTER
                    setTextColor(if (selected) Color.WHITE else Color.rgb(147, 150, 160))
                    background = roundBackground(
                        if (selected) Color.rgb(38, 40, 47) else Color.TRANSPARENT,
                        dp(12),
                        if (selected) Color.rgb(58, 60, 69) else Color.TRANSPARENT
                    )
                    setOnClickListener {
                        if (panelPage != index) {
                            panelPage = index
                            render()
                        }
                    }
                }
                tabs.addView(tab, LinearLayout.LayoutParams(0, dp(32), 1f).apply {
                    if (index > 0) leftMargin = dp(5)
                })
            }
            view.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

            when (panelPage) {
                0 -> renderMedia(view)
                1 -> {
                    val preview = if (prefs.getBoolean("show_notifications", true)) IslandState.notificationPreview else null
                    if (preview != null) renderNotification(view, preview)
                    else {
                        view.addView(label("All quiet", 14f, Color.WHITE).apply {
                            gravity = Gravity.CENTER
                            setPadding(0, dp(20), 0, dp(4))
                        })
                        view.addView(label("New notifications will appear here.", 11f, Color.rgb(150, 153, 164)).apply {
                            gravity = Gravity.CENTER
                            setPadding(0, 0, 0, dp(16))
                        })
                    }
                }
                else -> renderTimers(view)
            }

            val footerText = when {
                IslandState.batteryPercent < 0 -> null
                IslandState.charging -> "⚡  Charging · ${IslandState.batteryPercent}%"
                prefs.getBoolean("show_low_battery", true) && IslandState.batteryPercent <= prefs.getInt("low_battery_threshold", 15).coerceIn(5, 30) -> "Battery low · ${IslandState.batteryPercent}%"
                else -> "Battery · ${IslandState.batteryPercent}%"
            }
            if (footerText != null) {
                view.addView(View(this).apply { setBackgroundColor(Color.rgb(38, 40, 47)) },
                    LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(8) })
                view.addView(label(footerText, 10f, Color.rgb(151, 154, 164)).apply {
                    gravity = Gravity.CENTER
                    letterSpacing = 0.015f
                })
            }
        }

        val params = overlayParams
        if (params != null) {
            val screenWidthDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
            params.width = dp(if (expanded) minOf(360, screenWidthDp - 32).coerceAtLeast(280) else prefs.getInt("pill_width", 210))
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            runCatching { wm.updateViewLayout(view, params) }
        }
        updateDynamicLabels()
    }

    private fun roundBackground(fill: Int, radius: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(fill)
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
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
        if (!prefs.getBoolean("show_media", true)) {
            parent.addView(label("Media activity is turned off.", 12f, Color.rgb(145, 148, 158))
                .apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(18)) })
            return
        }

        val hasMedia = controller != null && IslandState.title != "PauzeX"
        if (!hasMedia) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(18), 0, dp(16))
            }
            empty.addView(TextView(this).apply {
                text = "♫"
                textSize = 27f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(222, 224, 232))
            }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { bottomMargin = dp(7) })
            empty.addView(label("Nothing playing", 13f, Color.WHITE).apply { gravity = Gravity.CENTER })
            empty.addView(label("Start music in a supported app.", 10.5f, Color.rgb(145, 148, 158)).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, dp(1))
            })
            parent.addView(empty)
            return
        }

        val metadataRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(1))
        }
        val cover: View = if (IslandState.albumArt != null) {
            ImageView(this).apply {
                setImageBitmap(IslandState.albumArt)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundBackground(Color.rgb(28, 30, 36), dp(13), Color.rgb(54, 56, 63))
                clipToOutline = true
            }
        } else {
            TextView(this).apply {
                text = "♫"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(239, 240, 245))
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(49, 51, 60), Color.rgb(20, 22, 28))
                ).apply {
                    cornerRadius = dp(13).toFloat()
                    setStroke(dp(1), Color.rgb(58, 60, 68))
                }
            }
        }
        metadataRow.addView(cover, LinearLayout.LayoutParams(dp(54), dp(54)).apply { rightMargin = dp(12) })

        val metadata = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        metadata.addView(label(IslandState.title, 14f, Color.WHITE).apply {
            maxLines = 1
            letterSpacing = -0.015f
        })
        metadata.addView(label(IslandState.subtitle, 11f, Color.rgb(158, 161, 172)).apply {
            maxLines = 1
            setPadding(0, dp(3), 0, 0)
        })
        metadataRow.addView(metadata, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(metadataRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        if (IslandState.mediaDurationMillis > 0L && IslandState.mediaCanSeek) {
            val seek = SeekBar(this).apply {
                max = 1000
                progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(232, 233, 238))
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.rgb(245, 245, 248))
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(64, 66, 74))
                progress = ((IslandState.mediaPositionMillis * 1000L) / IslandState.mediaDurationMillis).toInt().coerceIn(0, 1000)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            isSeeking = true
                            seekTimeView?.text = "${formatTime(IslandState.mediaDurationMillis * progress / 1000L)}  /  ${formatTime(IslandState.mediaDurationMillis)}"
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
            parent.addView(seek, LinearLayout.LayoutParams(-1, dp(26)).apply { topMargin = dp(0) })
            seekTimeView = label("", 9f, Color.rgb(136, 139, 150)).apply { gravity = Gravity.CENTER }
            parent.addView(seekTimeView)
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val actions = controller?.playbackState?.actions ?: 0L
        controls.addView(actionButton("⏮", enabled = (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS.toLong()) != 0L) {
            runCatching { controller?.transportControls?.skipToPrevious() }
        }, LinearLayout.LayoutParams(dp(48), dp(44)).apply { rightMargin = dp(12) })
        controls.addView(actionButton(if (IslandState.mediaPlaying) "Ⅱ" else "▶", prominent = true) {
            runCatching {
                val current = controller
                if (current != null) {
                    if (IslandState.mediaPlaying) current.transportControls.pause() else current.transportControls.play()
                }
            }
        }, LinearLayout.LayoutParams(dp(56), dp(48)))
        controls.addView(actionButton("⏭", enabled = (actions and PlaybackState.ACTION_SKIP_TO_NEXT.toLong()) != 0L) {
            runCatching { controller?.transportControls?.skipToNext() }
        }, LinearLayout.LayoutParams(dp(48), dp(44)).apply { leftMargin = dp(12) })
        parent.addView(controls, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(5)
            bottomMargin = dp(2)
        })
    }

    private fun renderTimers(parent: LinearLayout) {
        val running = IslandState.timerEndMillis > System.currentTimeMillis()
        timerLabelView = label(
            if (running) "Timer running · ${timerRemaining()}" else "CHOOSE A DURATION",
            10f,
            Color.rgb(158, 161, 172)
        ).apply {
            letterSpacing = if (running) 0.01f else 0.09f
            setPadding(0, dp(8), 0, dp(10))
        }
        parent.addView(timerLabelView)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(1, 5, 10, 25).forEachIndexed { index, minutes ->
            row.addView(actionButton("${minutes}m", prominent = running && IslandState.timerEndMillis - System.currentTimeMillis() < TimeUnit.MINUTES.toMillis(minutes.toLong())) {
                IslandState.timerEndMillis = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes.toLong())
                IslandState.timerFinishedUntilMillis = 0L
                IslandState.changed()
                render()
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                if (index > 0) leftMargin = dp(6)
            })
        }
        parent.addView(row)

        if (running || IslandState.timerFinishedUntilMillis > System.currentTimeMillis()) {
            parent.addView(actionButton("Cancel timer", prominent = false) {
                IslandState.timerEndMillis = 0L
                IslandState.timerFinishedUntilMillis = 0L
                IslandState.changed()
                render()
            }, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(8) })
        } else {
            parent.addView(label("Your countdown runs inside PauzeX.", 10f, Color.rgb(130, 133, 144)).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(11), 0, dp(4))
            })
        }

        parent.addView(actionButton("Custom duration  ·  Pro", prominent = false) {
            if (!prefs.getBoolean("pro_unlocked", false)) {
                startActivity(Intent(this, ProActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                val input = EditText(this).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    hint = "Minutes (1–720)"
                    setText("30")
                    setSelection(text.length)
                }
                AlertDialog.Builder(this)
                    .setTitle("Custom countdown")
                    .setMessage("Choose a duration between 1 minute and 12 hours.")
                    .setView(input)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Start timer") { _, _ ->
                        val minutes = input.text.toString().toIntOrNull()
                        if (minutes == null || minutes !in 1..720) {
                            Toast.makeText(this, "Enter a number from 1 to 720.", Toast.LENGTH_SHORT).show()
                        } else {
                            IslandState.timerEndMillis = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes.toLong())
                            IslandState.timerFinishedUntilMillis = 0L
                            IslandState.changed()
                            render()
                        }
                    }
                    .show()
            }
        }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(9) })
    }

    private fun actionButton(
        text: String,
        enabled: Boolean = true,
        prominent: Boolean = false,
        click: () -> Unit
    ) = TextView(this).apply {
        this.text = text
        textSize = if (text in listOf("⏮", "⏭", "▶", "Ⅱ")) 19f else 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        val foreground = when {
            !enabled -> Color.rgb(92, 94, 103)
            prominent -> Color.rgb(12, 13, 16)
            else -> Color.rgb(240, 241, 245)
        }
        setTextColor(foreground)
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.55f
        background = if (text in listOf("⏮", "⏭", "▶", "Ⅱ")) {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (prominent && enabled) Color.rgb(245, 246, 249) else Color.rgb(32, 34, 41))
                setStroke(dp(1), if (prominent && enabled) Color.rgb(245, 246, 249) else Color.rgb(49, 51, 60))
            }
        } else {
            roundBackground(
                if (prominent && enabled) Color.rgb(239, 240, 244) else Color.rgb(32, 34, 41),
                dp(15),
                if (prominent && enabled) Color.rgb(239, 240, 244) else Color.rgb(49, 51, 60)
            )
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
