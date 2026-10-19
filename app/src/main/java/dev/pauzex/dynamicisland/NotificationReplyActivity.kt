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
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
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

    private fun choiceBackground(selected: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(if (selected) Color.rgb(43, 48, 70) else Color.rgb(20, 22, 31))
        setStroke(dp(1), if (selected) Color.rgb(160, 171, 255) else Color.rgb(65, 70, 95))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 9, 14)
        window.navigationBarColor = Color.rgb(8, 9, 14)

        val action = readAction()
        val remoteInputs = action?.remoteInputs
        val target = remoteInputs?.firstOrNull { it.allowFreeFormInput }
            ?: remoteInputs?.firstOrNull { !it.choices.isNullOrEmpty() }
        if (action == null || remoteInputs.isNullOrEmpty() || target == null) {
            Toast.makeText(this, "This action doesn't support a typed or preset reply.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
            setBackgroundColor(Color.rgb(8, 9, 14))
        }
        SystemBarInsets.apply(root)

        root.addView(TextView(this).apply {
            text = if (target.allowFreeFormInput) "Reply to notification" else "Choose a quick reply"
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

        var edit: EditText? = null
        var selectedChoice: CharSequence? = null
        if (target.allowFreeFormInput) {
            edit = EditText(this).apply {
                hint = target.label?.toString()?.ifBlank { null } ?: "Message"
                textSize = 16f
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = choiceBackground(false)
                isSingleLine = false
                minLines = 2
                maxLines = 5
            }
            root.addView(edit, LinearLayout.LayoutParams(-1, -2))
        } else {
            val choices = target.choices?.toList().orEmpty()
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val choiceViews = mutableListOf<TextView>()
            choices.forEach { choice ->
                val choiceView = TextView(this).apply {
                    text = choice
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = choiceBackground(false)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        selectedChoice = choice
                        choiceViews.forEach { view -> view.background = choiceBackground(view === this) }
                    }
                }
                choiceViews.add(choiceView)
                container.addView(choiceView, LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(7)
                })
            }
            root.addView(container, LinearLayout.LayoutParams(-1, -2))
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(18), 0, 0)
        }
        buttons.addView(TextView(this).apply {
            text = "Cancel"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(dp(15), dp(12), dp(15), dp(12))
            setOnClickListener { finish() }
        })
        buttons.addView(TextView(this).apply {
            text = "Send"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.rgb(8, 9, 14))
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.rgb(232, 234, 245))
            }
            setOnClickListener {
                val value = if (target.allowFreeFormInput) edit?.text?.toString().orEmpty()
                    else selectedChoice?.toString().orEmpty()
                if (value.isBlank()) {
                    Toast.makeText(this@NotificationReplyActivity, "Choose or enter a reply first.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val fillIn = Intent()
                val results = Bundle().apply { putCharSequence(target.resultKey, value) }
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
        edit?.let { input ->
            input.requestFocus()
            input.post {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
}
