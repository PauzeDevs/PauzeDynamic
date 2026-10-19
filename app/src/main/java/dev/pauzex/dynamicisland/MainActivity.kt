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
    private val bg = Color.rgb(5, 6, 8)
    private val card = Color.rgb(17, 18, 22)
    private val border = Color.rgb(43, 45, 51)
    private val accent = Color.rgb(151, 166, 255)
    private var lastProEntitled: Boolean? = null

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun text(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            setPadding(0, dp(4), 0, dp(4))
            letterSpacing = if (size >= 28f) -0.025f else 0f
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun button(value: String, click: () -> Unit) = TextView(this).apply {
        val primary = value.startsWith("Start Dynamic Island") || value.startsWith("Explore Pro plans") || value.startsWith("Unlock Pro for live pulse")
        val subtle = value.startsWith("Stop Dynamic Island")
        text = value
        textSize = 14.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = -0.005f
        setTextColor(if (primary) Color.rgb(10, 11, 14) else Color.rgb(239, 240, 244))
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        minHeight = dp(56)
        setPadding(dp(18), dp(14), dp(18), dp(14))
        val fill = when {
            primary -> Color.rgb(242, 243, 247)
            subtle -> Color.rgb(13, 14, 17)
            else -> card
        }
        val shape = GradientDrawable().apply {
            cornerRadius = dp(17).toFloat()
            setColor(fill)
            setStroke(dp(1), if (primary) fill else border)
        }
        background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(Color.rgb(75, 78, 90)), shape, null
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }
    }

    private fun section(title: String): TextView =
        text(title, 16f, Color.rgb(241, 242, 246), true).apply {
            setPadding(0, dp(25), 0, dp(9))
            letterSpacing = -0.015f
        }

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
            // Remember the user's explicit start request so returning from Settings can complete it.
            prefs.edit().putBoolean("pending_island_start", true).apply()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        prefs.edit()
            .putBoolean("pending_island_start", false)
            .putBoolean("island_running", true)
            .apply()
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
        root.addView(text("PAUZEX  /  ANDROID", 10.5f, accent, true).apply {
            letterSpacing = 0.08f
            setPadding(0, dp(1), 0, dp(9))
        })
        root.addView(text("Dynamic Island", 36f, Color.WHITE, true).apply {
            letterSpacing = -0.035f
            setPadding(0, 0, 0, dp(5))
        })
        root.addView(text(
            "Your essentials, one quiet glance away.",
            15f, Color.rgb(169, 171, 179)
        ).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, 0, 0, dp(5))
        })

        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(21), dp(20), dp(21))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.BLACK)
                setStroke(dp(1), border)
            }
        }
        preview.addView(text("●  READY WHEN YOU ARE", 11f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.035f
        })
        preview.addView(text("Music  ·  Alerts  ·  Timers  ·  Battery", 12f, Color.rgb(157, 160, 169)).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
        root.addView(preview, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(22)
            bottomMargin = dp(4)
        })

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
            prefs.edit()
                .putBoolean("island_running", false)
                .putBoolean("pending_island_start", false)
                .apply()
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

        val expandedWidthValue = prefs.getInt("expanded_width", 360).coerceIn(260, 420)
        val expandedWidthLabel = text("Expanded panel width · $expandedWidthValue dp", 13f, Color.LTGRAY)
        root.addView(expandedWidthLabel)
        root.addView(SeekBar(this).apply {
            max = 160
            progress = expandedWidthValue - 260
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val size = 260 + progress
                    expandedWidthLabel.text = "Expanded panel width · $size dp"
                    if (fromUser) prefs.edit().putInt("expanded_width", size).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) { refreshOverlay() }
            })
        }, LinearLayout.LayoutParams(-1, dp(44)))

        root.addView(section("Apple-style motion"))
        val durationValue = prefs.getInt("animation_duration_ms", 280).coerceIn(180, 420)
        val durationLabel = text("Expand/collapse duration · ${durationValue} ms", 13f, Color.LTGRAY)
        root.addView(durationLabel)
        root.addView(SeekBar(this).apply {
            max = 240
            progress = durationValue - 180
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val duration = 180 + progress
                    durationLabel.text = "Expand/collapse duration · ${duration} ms"
                    if (fromUser) prefs.edit().putInt("animation_duration_ms", duration).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) { refreshOverlay() }
            })
        }, LinearLayout.LayoutParams(-1, dp(44)))
        root.addView(text("Default surface is pure black. Morphing follows Android's display-vsync clock; sustained 60 fps depends on device performance.", 10.5f, Color.GRAY))

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
        // Complete a pending start request after the user grants overlay access in Settings.
        val shouldStartIsland = prefs.getBoolean("island_running", false) ||
            prefs.getBoolean("pending_island_start", false)
        if (shouldStartIsland && Settings.canDrawOverlays(this)) {
            prefs.edit()
                .putBoolean("island_running", true)
                .putBoolean("pending_island_start", false)
                .apply()
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
        topMargin = dp(4)
        bottomMargin = dp(4)
    }
}
