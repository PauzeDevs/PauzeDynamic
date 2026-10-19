package dev.pauzex.dynamicisland

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private fun text(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.WHITE); setPadding(0, 10, 0, 10)
    }
    private fun button(value: String, click: () -> Unit) = Button(this).apply {
        text = value; setOnClickListener { click() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 9, 14); window.navigationBarColor = Color.rgb(8, 9, 14)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); setBackgroundColor(Color.rgb(8, 9, 14))
        }
        root.addView(text("PAUZEX  /  ANDROID", 13f))
        root.addView(text("Dynamic Island", 30f))
        root.addView(text("A live-activity pill for Android. Grant the permissions you want to use.", 16f))
        root.addView(button("Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
        })
        root.addView(button("Enable notification access") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        root.addView(button("Start Dynamic Island") {
            if (Settings.canDrawOverlays(this)) {
                val i = Intent(this, IslandService::class.java).setAction(IslandService.ACTION_START)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            } else startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
        })
        root.addView(button("Stop Dynamic Island") { stopService(Intent(this, IslandService::class.java)) })
        root.addView(text("Media controls require an active media session. Notification previews require notification access. Device support varies.", 13f))
        setContentView(ScrollView(this).apply { addView(root, ViewGroup.LayoutParams(-1, -2)) })
    }
}
