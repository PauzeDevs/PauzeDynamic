package dev.pauzex.dynamicisland

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class CustomTimerActivity : Activity() {
    private val prefs by lazy { getSharedPreferences(IslandService.PREFS, MODE_PRIVATE) }
    private val bg = Color.rgb(7, 8, 12)
    private val surface = Color.rgb(20, 22, 29)
    private val accent = Color.rgb(180, 190, 255)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun cardBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16).toFloat()
        setColor(surface)
        setStroke(dp(1), Color.rgb(49, 52, 63))
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!prefs.getBoolean("pro_entitled", false)) {
            startActivity(Intent(this, ProActivity::class.java))
            finish()
            return
        }

        window.statusBarColor = bg
        window.navigationBarColor = bg
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(bg)
        }
        root.addView(label("PAUZEX PRO  /  TOOLS", 10f, accent, true))
        root.addView(label("Custom countdown", 29f, Color.WHITE, true).apply {
            setPadding(0, dp(9), 0, dp(8))
        })
        root.addView(label("Set any duration from 1 minute to 12 hours.", 13f, Color.rgb(157, 160, 171)))
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Minutes (1–720)"
            setText("30")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(120, 124, 136))
            textSize = 20f
            gravity = Gravity.CENTER
            selectAll()
            background = cardBackground()
            setPadding(dp(14), dp(16), dp(14), dp(16))
        }
        root.addView(input, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(22)
            bottomMargin = dp(12)
        })

        val start = TextView(this).apply {
            text = "Start countdown"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(8, 9, 13))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(15).toFloat()
                setColor(Color.rgb(242, 243, 248))
            }
            setPadding(dp(14), dp(15), dp(14), dp(15))
            setOnClickListener {
                val minutes = input.text.toString().toIntOrNull()
                if (minutes == null || minutes !in 1..720) {
                    input.error = "Enter 1–720 minutes"
                    return@setOnClickListener
                }
                IslandState.timerEndMillis = System.currentTimeMillis() + minutes * 60_000L
                IslandState.timerFinishedUntilMillis = 0L
                IslandState.changed()
                Toast.makeText(this@CustomTimerActivity, "Timer set for $minutes minutes.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        root.addView(start, LinearLayout.LayoutParams(-1, -2))
        root.addView(label("The countdown appears in the island's Tools view.", 11f, Color.rgb(128, 132, 145)).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        })
        setContentView(root, ViewGroup.LayoutParams(-1, -1))
    }
}
