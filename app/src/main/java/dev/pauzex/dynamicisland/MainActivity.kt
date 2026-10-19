package dev.pauzex.dynamicisland

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.content.res.ColorStateList

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences(IslandService.PREFS, MODE_PRIVATE) }
    private val bg = Color.rgb(8, 9, 14)
    private val card = Color.rgb(17, 19, 28)
    private val accent = Color.rgb(139, 156, 255)

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(5), 0, dp(5))
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun button(value: String, click: () -> Unit) = TextView(this).apply {
        text = value; textSize = 14f; setTextColor(Color.WHITE); gravity = android.view.Gravity.CENTER
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(card); setStroke(dp(1), Color.rgb(48, 51, 65)) }
        setOnClickListener { click() }
    }
    private fun section(title: String): TextView = text(title.uppercase(), 11f, accent, true).apply { setPadding(0, dp(20), 0, dp(8)) }
    private fun toggle(title: String, key: String, default: Boolean = true) = CheckBox(this).apply {
        text = title; textSize = 14f; setTextColor(Color.WHITE); buttonTintList = ColorStateList.valueOf(accent)
        isChecked = prefs.getBoolean(key, default)
        setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply(); refreshOverlay() }
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun refreshOverlay() {
        if (Settings.canDrawOverlays(this)) {
            stopService(Intent(this, IslandService::class.java))
            val intent = Intent(this, IslandService::class.java).setAction(IslandService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg; window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= 23) window.decorView.systemUiVisibility = 0
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(22), dp(22), dp(30)); setBackgroundColor(bg)
        }
        root.addView(text("PAUZEX  /  ANDROID", 11f, accent, true))
        root.addView(text("Dynamic Island", 32f, Color.WHITE, true))
        root.addView(text("A live-activity overlay with a compact, customisable interface.", 14f, Color.LTGRAY))
        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER; setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply { cornerRadius = dp(25).toFloat(); setColor(Color.rgb(3, 4, 8)); setStroke(dp(1), Color.rgb(70, 75, 105)) }
        }
        preview.addView(text("●  PAUZEX  ·  READY", 12f, Color.WHITE, true).apply { gravity = android.view.Gravity.CENTER })
        preview.addView(text("Live activities · Media · Battery", 11f, Color.LTGRAY).apply { gravity = android.view.Gravity.CENTER })
        root.addView(preview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22); bottomMargin = dp(6) })
        root.addView(section("Permissions & controls"))
        root.addView(button("Allow display over other apps  ↗") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }, marginParams())
        root.addView(button("Enable notification access  ↗") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, marginParams())
        root.addView(button("Start Dynamic Island") {
            if (Settings.canDrawOverlays(this)) {
                val i = Intent(this, IslandService::class.java).setAction(IslandService.ACTION_START)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            } else startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }, marginParams())
        root.addView(button("Stop Dynamic Island") { stopService(Intent(this, IslandService::class.java)) }, marginParams())
        root.addView(section("Appearance"))
        root.addView(text("Compact pill width · ${prefs.getInt("pill_width", 210)} dp", 13f, Color.LTGRAY))
        val width = SeekBar(this).apply {
            max = 110; progress = (prefs.getInt("pill_width", 210) - 170).coerceIn(0, 110)
            progressTintList = ColorStateList.valueOf(accent); thumbTintList = ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val size = 170 + progress
                    prefs.edit().putInt("pill_width", size).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) { refreshOverlay(); recreate() }
            })
        }
        root.addView(width, LinearLayout.LayoutParams(-1, dp(44)))
        root.addView(section("Live activity modules"))
        root.addView(toggle("Media activity and playback", "show_media"))
        root.addView(toggle("Notification previews", "show_notifications"))
        root.addView(toggle("Battery and charging status", "show_battery"))
        root.addView(toggle("Notification progress bars", "show_progress"))
        root.addView(section("Accent colour"))
        val colors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("#8B9CFF", "#70E1C1", "#FF9F70", "#F38BCB", "#FFFFFF").forEach { hex ->
            val color = Color.parseColor(hex)
            val swatch = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(2), if (prefs.getString("accent", "#8B9CFF") == hex) Color.WHITE else Color.rgb(50, 52, 65)) }
                setOnClickListener { prefs.edit().putString("accent", hex).apply(); refreshOverlay(); recreate() }
            }
            colors.addView(swatch, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(14); topMargin = dp(6); bottomMargin = dp(6) })
        }
        root.addView(colors)
        root.addView(text("Tip: enable notification access for notification previews and media-session discovery. The overlay can be dragged to reposition it.", 12f, Color.GRAY).apply { setPadding(0, dp(20), 0, 0) })
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root, ViewGroup.LayoutParams(-1, -2)) })
    }
    private fun marginParams() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5); bottomMargin = dp(5) }
}