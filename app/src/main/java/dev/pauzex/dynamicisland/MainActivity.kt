package dev.pauzex.dynamicisland

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.content.res.ColorStateList

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences(IslandService.PREFS, MODE_PRIVATE) }
    private val bg = Color.rgb(8, 9, 14)
    private val card = Color.rgb(17, 19, 28)
    private val accent = Color.rgb(139, 156, 255)
    private var lastProEntitled: Boolean? = null

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun text(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            setPadding(0, dp(5), 0, dp(5))
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun button(value: String, click: () -> Unit) = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(card)
            setStroke(dp(1), Color.rgb(48, 51, 65))
        }
        setOnClickListener { click() }
    }

    private fun section(title: String): TextView =
        text(title.uppercase(), 11f, accent, true).apply { setPadding(0, dp(20), 0, dp(8)) }

    private fun toggle(title: String, key: String, default: Boolean = true) = CheckBox(this).apply {
        text = title
        textSize = 14f
        setTextColor(Color.WHITE)
        buttonTintList = ColorStateList.valueOf(accent)
        isChecked = prefs.getBoolean(key, default)
        setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
            refreshOverlay()
        }
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun refreshOverlay() {
        if (!prefs.getBoolean("island_running", false) || !Settings.canDrawOverlays(this)) return
        stopService(Intent(this, IslandService::class.java))
        val intent = Intent(this, IslandService::class.java).setAction(IslandService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun startIsland() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        prefs.edit().putBoolean("island_running", true).apply()
        val intent = Intent(this, IslandService::class.java).setAction(IslandService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun showNotificationAppFilters() {
        val packages = (prefs.getStringSet("observed_notification_packages", emptySet()) ?: emptySet())
            .toList().sortedBy { prefs.getString("notification_app_label_$it", it)?.lowercase() }
        if (packages.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Notification app filters")
                .setMessage("No notification apps have been observed yet. Enable notification access and let notifications arrive; then return here to choose which apps the island can display.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val blocked = (prefs.getStringSet("blocked_notification_apps", emptySet()) ?: emptySet()).toMutableSet()
        val labels = packages.map { pkg ->
            "${prefs.getString("notification_app_label_$pkg", pkg)}  ·  $pkg"
        }.toTypedArray()
        val checked = packages.map { it !in blocked }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("Show notifications from")
            .setMultiChoiceItems(labels, checked) { _, index, show ->
                if (show) blocked.remove(packages[index]) else blocked.add(packages[index])
            }
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putStringSet("blocked_notification_apps", blocked.toSet()).apply()
                // Re-select the newest notification that remains allowed by the updated filter.
                // Keep the listener's snapshot cache intact so changing the filter back can restore
                // still-active notifications rather than leaving the island blank.
                IslandState.notificationPreview =
                    IslandState.notificationPreviews.firstOrNull { it.packageName !in blocked }
                IslandState.downloadProgress = -1
                IslandState.changed()
                refreshOverlay()
                Toast.makeText(this, "Notification filters saved.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= 23) window.decorView.systemUiVisibility = 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(30))
            setBackgroundColor(bg)
        }
        SystemBarInsets.apply(root)
        root.addView(text("PAUZEX  /  ANDROID", 11f, accent, true))
        root.addView(text("Dynamic Island", 32f, Color.WHITE, true))
        root.addView(text("A live-activity overlay with actionable notifications, media controls and customisation.", 14f, Color.LTGRAY))

        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(25).toFloat()
                setColor(Color.rgb(3, 4, 8))
                setStroke(dp(1), Color.rgb(70, 75, 105))
            }
        }
        preview.addView(text("●  PAUZEX  ·  READY", 12f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
        preview.addView(text("Media · Notification actions · Timers · Battery", 11f, Color.LTGRAY).apply { gravity = Gravity.CENTER })
        root.addView(preview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22); bottomMargin = dp(6) })

        root.addView(section("Permissions & controls"))
        root.addView(button("Allow display over other apps  ↗") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }, marginParams())
        root.addView(button("Enable notification access  ↗") {
            AlertDialog.Builder(this)
                .setTitle("Notification access")
                .setMessage(
                    "PauzeX Dynamic Island can read notification content only after you explicitly enable Android notification access. " +
                    "It uses notification titles, text, app names and available action buttons to show previews in the floating island. " +
                    "Notifications may contain sensitive information. Choose which apps are shown using the notification filters. " +
                    "This app does not declare the INTERNET permission; notification processing is performed on this device. " +
                    "You can revoke access at any time in Android Settings."
                )
                .setPositiveButton("Continue to Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }, marginParams())
        root.addView(button("Start Dynamic Island") { startIsland() }, marginParams())
        root.addView(button("Stop Dynamic Island") {
            prefs.edit().putBoolean("island_running", false).apply()
            stopService(Intent(this, IslandService::class.java))
        }, marginParams())
        root.addView(button("Choose notification apps  ↗") { showNotificationAppFilters() }, marginParams())
        root.addView(button("Reset island position") {
            prefs.edit()
                .putBoolean("custom_position", false)
                .putInt("position_x", 0)
                .putInt("position_y", dp(8))
                .apply()
            refreshOverlay()
            Toast.makeText(this, "Island position reset.", Toast.LENGTH_SHORT).show()
        }, marginParams())

        root.addView(section("PauzeX Pro"))
        root.addView(text("Unlock exclusive island themes, custom countdowns, live pulse and adjustable battery alerts.", 13f, Color.LTGRAY))
        root.addView(button(if (prefs.getBoolean("pro_entitled", false)) "Manage PauzeX Pro  ↗" else "Explore Pro plans  ↗") {
            startActivity(Intent(this, ProActivity::class.java))
        }, marginParams())
        root.addView(button(
            if (!prefs.getBoolean("pro_entitled", false)) "Unlock Pro for live pulse animation"
            else if (prefs.getBoolean("pro_motion", true)) "Live indicator pulse · On"
            else "Live indicator pulse · Off"
        ) {
            if (!prefs.getBoolean("pro_entitled", false)) {
                startActivity(Intent(this, ProActivity::class.java))
            } else {
                val enabled = !prefs.getBoolean("pro_motion", true)
                prefs.edit().putBoolean("pro_motion", enabled).apply()
                refreshOverlay()
                Toast.makeText(this, if (enabled) "Live pulse enabled." else "Live pulse disabled.", Toast.LENGTH_SHORT).show()
                recreate()
            }
        }, marginParams())

        root.addView(section("Appearance"))
        val widthLabel = text("Compact pill width · ${prefs.getInt("pill_width", 210)} dp", 13f, Color.LTGRAY)
        root.addView(widthLabel)
        val width = SeekBar(this).apply {
            max = 130
            progress = (prefs.getInt("pill_width", 210) - 170).coerceIn(0, 130)
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val size = 170 + progress
                    widthLabel.text = "Compact pill width · $size dp"
                    prefs.edit().putInt("pill_width", size).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    refreshOverlay()
                }
            })
        }
        root.addView(width, LinearLayout.LayoutParams(-1, dp(44)))

        root.addView(section("Live activity modules"))
        root.addView(toggle("Media activity and playback", "show_media"))
        root.addView(toggle("Notification previews and actions", "show_notifications"))
        root.addView(toggle("Battery and charging status", "show_battery"))
        root.addView(toggle("Low-battery warning (15% or less)", "show_low_battery"))
        root.addView(toggle("Notification progress bars", "show_progress"))

        if (prefs.getBoolean("pro_entitled", false)) {
            root.addView(section("Pro · custom battery alert"))
            val savedThreshold = prefs.getInt("low_battery_threshold", 15).coerceIn(5, 30)
            val thresholdLabel = text("Warn at ${savedThreshold}% battery", 13f, Color.LTGRAY)
            root.addView(thresholdLabel)
            root.addView(SeekBar(this).apply {
                max = 25
                progress = savedThreshold - 5
                progressTintList = ColorStateList.valueOf(accent)
                thumbTintList = ColorStateList.valueOf(accent)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val threshold = 5 + progress
                        thresholdLabel.text = "Warn at ${threshold}% battery"
                        if (fromUser) prefs.edit().putInt("low_battery_threshold", threshold).apply()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) { refreshOverlay() }
                })
            }, LinearLayout.LayoutParams(-1, dp(44)))
        } else {
            root.addView(button("Unlock custom battery alerts with Pro  ↗") {
                startActivity(Intent(this, ProActivity::class.java))
            }, marginParams())
        }

        root.addView(section("Accent colour"))
        val colors = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val paletteOptions = listOf(
            "#8B9CFF" to false,
            "#70E1C1" to false,
            "#FF9F70" to false,
            "#F38BCB" to false,
            "#FFFFFF" to false,
            "#B7A2FF" to true,
            "#6EC9FF" to true,
            "#FF6E91" to true,
            "#FFC46B" to true
        )
        paletteOptions.forEachIndexed { index, (hex, proOnly) ->
            if (index == 5) {
                colors.addView(colorRow)
                colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            }
            val color = Color.parseColor(hex)
            val locked = proOnly && !prefs.getBoolean("pro_entitled", false)
            val swatch = TextView(this).apply {
                text = if (locked) "⌑" else ""
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                setTextColor(Color.rgb(9, 10, 14))
                alpha = if (locked) 0.72f else 1f
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(dp(if (prefs.getString("accent", "#8B9CFF") == hex) 3 else 1),
                        if (prefs.getString("accent", "#8B9CFF") == hex) Color.WHITE else Color.rgb(50, 52, 65))
                }
                contentDescription = if (locked) "Premium accent colour $hex, unlock with PauzeX Pro" else "Set accent colour $hex"
                setOnClickListener {
                    if (proOnly && !prefs.getBoolean("pro_entitled", false)) {
                        startActivity(Intent(this@MainActivity, ProActivity::class.java))
                    } else {
                        prefs.edit().putString("accent", hex).apply()
                        refreshOverlay()
                        recreate()
                    }
                }
            }
            colorRow.addView(swatch, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                rightMargin = dp(7)
                topMargin = dp(6)
                bottomMargin = dp(6)
            })
        }
        colors.addView(colorRow)
        root.addView(colors)
        root.addView(text("Premium palette colours are locked until PauzeX Pro is active.", 10.5f, Color.GRAY))

        root.addView(section("Island themes"))
        if (prefs.getBoolean("pro_entitled", false)) {
            val themeList = listOf(
                "Midnight" to "midnight",
                "Emerald" to "emerald",
                "Violet" to "violet",
                "Graphite" to "graphite"
            )
            themeList.chunked(2).forEach { themePair ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                themePair.forEach { (label, key) ->
                    row.addView(button(
                        if (prefs.getString("pill_theme", "classic") == key) "✓  $label" else label
                    ) {
                        prefs.edit().putString("pill_theme", key).apply()
                        refreshOverlay()
                        recreate()
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply {
                        rightMargin = dp(6)
                        bottomMargin = dp(6)
                    })
                }
                root.addView(row)
            }
        } else {
            root.addView(button("Unlock four island themes with Pro  ↗") {
                startActivity(Intent(this, ProActivity::class.java))
            }, marginParams())
        }

        root.addView(text("Swipe horizontally across the expanded island to switch Music, Alerts and Tools. Swipe down to collapse it. Notification filters become available after apps have posted notifications while access is enabled.", 12f, Color.GRAY)
            .apply { setPadding(0, dp(20), 0, 0) })
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(-1, -2))
        })
    }

    override fun onResume() {
        super.onResume()
        // Resume an explicitly enabled island after the user returns from Android Settings.
        if (prefs.getBoolean("island_running", false) && Settings.canDrawOverlays(this)) {
            val intent = Intent(this, IslandService::class.java).setAction(IslandService.ACTION_START)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            }.onFailure {
                Toast.makeText(this, "Couldn't start the island. Tap Start Dynamic Island to retry.", Toast.LENGTH_LONG).show()
            }
        }
        prefs.getString("overlay_error", null)?.let {
            Toast.makeText(this, "Island overlay failed ($it). Check overlay permission and retry.", Toast.LENGTH_LONG).show()
            prefs.edit().remove("overlay_error").apply()
        }
        val nowProEntitled = prefs.getBoolean("pro_entitled", false)
        if (lastProEntitled != null && lastProEntitled != nowProEntitled) {
            refreshOverlay()
            recreate()
        }
        lastProEntitled = nowProEntitled
    }

    private fun marginParams() = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(5)
        bottomMargin = dp(5)
    }
}
