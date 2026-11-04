package com.example.timescapedemo

import android.content.SharedPreferences
import android.util.Log
import android.view.View

internal class BookmarkButtonPosition(private val prefs: SharedPreferences) {
    private val buttons = mutableSetOf<View>()
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        restoreAll()
    }

    fun attach(button: View) {
        require(button.parent is View)
        if (!buttons.add(button)) return
        button.addOnLayoutChangeListener(layoutListener)
        (button.parent as View).addOnLayoutChangeListener(layoutListener)
        restore(button)
    }

    fun detach(button: View) {
        if (!buttons.remove(button)) return
        button.removeOnLayoutChangeListener(layoutListener)
        (button.parent as? View)?.removeOnLayoutChangeListener(layoutListener)
    }

    fun save(button: View) {
        val parent = button.parent as View
        if (parent.width == 0 || parent.height == 0 || button.width == 0 || button.height == 0) return
        val rangeX = (parent.width - button.width).coerceAtLeast(0)
        val rangeY = (parent.height - button.height).coerceAtLeast(0)
        val x = if (rangeX > 0) (button.x / rangeX).coerceIn(0f, 1f) else 0f
        val y = if (rangeY > 0) (button.y / rangeY).coerceIn(0f, 1f) else 0f
        prefs.edit().putFloat(KEY_X, x).putFloat(KEY_Y, y).apply()
        restoreAll()
    }

    private fun restoreAll() {
        buttons.forEach(::restore)
    }

    private fun restore(button: View) {
        if (!prefs.contains(KEY_X) || !prefs.contains(KEY_Y)) return
        val parent = button.parent as View
        if (parent.width == 0 || parent.height == 0 || button.width == 0 || button.height == 0) return
        val x = prefs.getFloat(KEY_X, 0f)
        val y = prefs.getFloat(KEY_Y, 0f)
        if (!x.isFinite() || !y.isFinite()) {
            Log.w("BookmarkButtonPosition", "Cannot restore non-finite bookmark button coordinates")
            return
        }
        button.x = x.coerceIn(0f, 1f) * (parent.width - button.width).coerceAtLeast(0)
        button.y = y.coerceIn(0f, 1f) * (parent.height - button.height).coerceAtLeast(0)
    }

    private companion object {
        const val KEY_X = "bookmark_button_position_x"
        const val KEY_Y = "bookmark_button_position_y"
    }
}
