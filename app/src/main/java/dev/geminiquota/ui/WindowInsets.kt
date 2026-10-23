package dev.geminiquota.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Pads this view by the system-bar and display-cutout insets.
 *
 * Android 15 (targetSdk 35) draws every app edge-to-edge whether it asks for it
 * or not, so the toolbar ended up underneath the status bar. Handling the insets
 * here — combined with `setDecorFitsSystemWindows(window, false)` in the activity
 * — gives the same layout on every version from 26 to 35+, instead of relying on
 * `android:statusBarColor`, which 15+ ignores.
 */
fun View.applySystemBarInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        WindowInsetsCompat.CONSUMED
    }
    ViewCompat.requestApplyInsets(this)
}
