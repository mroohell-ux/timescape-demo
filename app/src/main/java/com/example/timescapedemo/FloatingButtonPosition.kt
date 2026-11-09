package com.example.timescapedemo

import android.content.SharedPreferences
import android.util.Log
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.view.GestureDetectorCompat
import kotlin.math.abs

internal class FloatingButtonPosition(private val prefs: SharedPreferences, keyPrefix: String) {
    private val keyX = "${keyPrefix}_button_position_x"
    private val keyY = "${keyPrefix}_button_position_y"
    private val buttons = mutableMapOf<View, GestureDetectorCompat>()
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        restoreAll()
    }

    fun attach(button: View) {
        require(button.parent is View)
        if (buttons.containsKey(button)) return
        buttons[button] = makeMovable(button)
        button.addOnLayoutChangeListener(layoutListener)
        (button.parent as View).addOnLayoutChangeListener(layoutListener)
        restore(button)
    }

    fun detach(button: View) {
        val detector = buttons.remove(button) ?: return
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        detector.onTouchEvent(cancel)
        cancel.recycle()
        button.setOnTouchListener(null)
        button.parent?.requestDisallowInterceptTouchEvent(false)
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
        prefs.edit().putFloat(keyX, x).putFloat(keyY, y).apply()
        restoreAll()
    }

    private fun restoreAll() {
        buttons.keys.forEach(::restore)
    }

    private fun restore(button: View) {
        if (!prefs.contains(keyX) || !prefs.contains(keyY)) return
        val parent = button.parent as View
        if (parent.width == 0 || parent.height == 0 || button.width == 0 || button.height == 0) return
        val x = prefs.getFloat(keyX, 0f)
        val y = prefs.getFloat(keyY, 0f)
        if (!x.isFinite() || !y.isFinite()) {
            Log.w("FloatingButtonPosition", "Cannot restore non-finite coordinates for $keyX / $keyY")
            return
        }
        button.x = x.coerceIn(0f, 1f) * (parent.width - button.width).coerceAtLeast(0)
        button.y = y.coerceIn(0f, 1f) * (parent.height - button.height).coerceAtLeast(0)
    }

    private fun makeMovable(button: View): GestureDetectorCompat {
        val touchSlop = ViewConfiguration.get(button.context).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        var moved = false
        var longPressed = false
        val detector = GestureDetectorCompat(button.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true

                override fun onLongPress(event: MotionEvent) {
                    if (!moved && button.isLongClickable) {
                        longPressed = button.performLongClick()
                    }
                }
            })
        button.setOnTouchListener { view, event ->
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = view.x
                    startY = view.y
                    moved = false
                    longPressed = false
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - downRawX
                    val deltaY = event.rawY - downRawY
                    if (!moved && (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop)) moved = true
                    if (moved) {
                        val parent = view.parent as View
                        view.x = (startX + deltaX).coerceIn(0f,
                            (parent.width - view.width).coerceAtLeast(0).toFloat())
                        view.y = (startY + deltaY).coerceIn(0f,
                            (parent.height - view.height).coerceAtLeast(0).toFloat())
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (moved) save(view) else if (!longPressed) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (moved) save(view)
                    true
                }
                else -> true
            }
        }
        return detector
    }
}
