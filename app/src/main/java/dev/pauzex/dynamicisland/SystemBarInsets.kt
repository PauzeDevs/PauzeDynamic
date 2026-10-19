package dev.pauzex.dynamicisland

import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Android 15 enforces edge-to-edge for target-SDK 35 apps. Preserve each screen's
 * intended spacing by adding system-bar insets to its existing root padding.
 * Older Android versions already fit the content to system bars by default.
 */
internal object SystemBarInsets {
    fun apply(root: View) {
        if (Build.VERSION.SDK_INT < 35) return

        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom

        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(
                initialLeft + bars.left,
                initialTop + bars.top,
                initialRight + bars.right,
                initialBottom + bars.bottom
            )
            insets
        }
        root.requestApplyInsets()
    }
}
