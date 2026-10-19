package dev.pauzex.dynamicisland

import android.app.Activity
import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.Toast

class NotificationReplyActivity : Activity() {
    companion object {
        const val EXTRA_ACTION = "dev.pauzex.dynamicisland.NOTIFICATION_ACTION"
    }

    @Suppress("DEPRECATION")
    private fun readAction(): Notification.Action? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_ACTION, Notification.Action::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_ACTION) as? Notification.Action
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 9, 14)
        window.navigationBarColor = Color.rgb(8, 9, 14)

        val action = readAction()
        val remoteInputs = action?.remoteInputs
        if (action == null || remoteInputs.isNullOrEmpty()) {
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
            setBackgroundColor(Color.rgb(8, 9, 14))
        }
        root.addView(TextView(this).apply {
            text = "Reply to notification"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        root.addView(TextView(this).apply {
            text = action.title?.toString().orEmpty().ifBlank { "Enter your reply" }
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(14))
        })

        val edit = EditText(this).apply {
            hint = remoteInputs.firstOrNull { it.allowFreeFormInput }?.label?.toString() ?: "Message"
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.rgb(20, 22, 31))
                setStroke(dp(1), Color.rgb(65, 70, 95))
            }
            isSingleLine = false
            minLines = 2
            maxLines = 5
        }
        root.addView(edit, LinearLayout.LayoutParams(-1, -2))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(18), 0, 0)
        }
        buttons.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        })
        buttons.addView(Button(this).apply {
            text = "Send"
            setOnClickListener {
                val target = remoteInputs.firstOrNull { it.allowFreeFormInput } ?: remoteInputs.first()
                val value = edit.text?.toString().orEmpty()
                if (target.allowFreeFormInput && value.isBlank()) {
                    Toast.makeText(this@NotificationReplyActivity, "Enter a reply first.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val fillIn = Intent()
                val results = Bundle().apply {
                    if (target.allowFreeFormInput) putCharSequence(target.resultKey, value)
                }
                runCatching {
                    RemoteInput.addResultsToIntent(remoteInputs, fillIn, results)
                    action.actionIntent.send(this@NotificationReplyActivity, 0, fillIn)
                    finish()
                }.onFailure {
                    Toast.makeText(this@NotificationReplyActivity, "This notification no longer accepts replies.", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        })
        root.addView(buttons)
        setContentView(root)
        edit.requestFocus()
        edit.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}
