package com.oppzippy.openscq30.lite

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.KeyEvent
import android.view.View

/**
 * A switch drawn by hand (track + thumb with a soft shadow), matching the original app's look. The stock
 * android.widget.Switch cannot be restyled on Android 4.4 (Switch.setThumbDrawable needs API 23), so this
 * is a small custom view instead: a 51x31dp track (the original's size) and a white thumb.
 */
class AnkerSwitch(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    var isChecked = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var onCheckedChange: ((Boolean) -> Unit)? = null

    init {
        isFocusable = true
        isClickable = true
        layoutDirection = LAYOUT_DIRECTION_LTR
        setOnClickListener { toggle() }
    }

    private fun toggle() {
        isChecked = !isChecked
        onCheckedChange?.invoke(isChecked)
    }

    private fun dp(v: Float) = v * density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(51f).toInt(), dp(31f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val w = width.toFloat()
        val trackH = h - dp(2f)
        rect.set(dp(1f), (h - trackH) / 2f, w - dp(1f), (h + trackH) / 2f)
        paint.style = Paint.Style.FILL
        paint.color = if (isChecked) UiStyle.blue else 0xFFE5E5EA.toInt()
        canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, paint)

        val radius = h / 2f - dp(1.5f)
        val cx = if (isChecked) w - radius - dp(1.5f) else radius + dp(1.5f)
        val cy = h / 2f

        // A soft shadow under the thumb, like the original's layered drop-shadow.
        paint.color = 0x22000000
        canvas.drawCircle(cx, cy + dp(0.8f), radius + dp(0.5f), paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, cy, radius, paint)

        if (isFocused) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.color = UiStyle.blueDark
            canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, paint)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            toggle()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
