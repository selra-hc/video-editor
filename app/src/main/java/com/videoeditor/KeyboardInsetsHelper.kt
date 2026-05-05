package com.videoeditor

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView

/**
 * Wires IME (soft keyboard) inset handling to a [NestedScrollView].
 *
 * On modern Android (Material3 + edge-to-edge) `adjustResize` alone is not guaranteed to shrink
 * the window when the keyboard opens, so we listen for IME insets on the root view and manually
 * apply the keyboard height as bottom padding on [scrollView].  [NestedScrollView] then detects
 * the focused child is outside its visible bounds and scrolls to reveal it.
 *
 * [scrollView] must have `android:clipToPadding="false"` in XML so that content can over-scroll
 * into the padding area and the last item scrolls fully above the keyboard.
 */
fun setupKeyboardInsets(root: View, scrollView: NestedScrollView) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
        val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        scrollView.updatePadding(bottom = imeHeight)
        if (imeHeight > 0) {
            root.findFocus()?.let { focused ->
                scrollView.post { scrollView.requestChildFocus(focused, focused) }
            }
        }
        insets
    }
}
