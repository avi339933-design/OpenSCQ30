package com.oppzippy.openscq30.lite
 
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
 
/**
 * A bar of numbered segments, filled with blue up to the selected one (like the level bar in the original app).
 * Keys: left / right change the level (the new level is sent half a second after the last key press).
 * Touch: tap or drag on a segment.
 */
class SegmentBar(context: Context, private val labels: List<String>) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
 
    private var level = 0
 
    // Called with the chosen segment index (0 based) when the user stopped changing the level.
    var onCommit: ((Int) -> Unit)? = null
 
    init {
        isFocusable = true
        layoutDirection = LAYOUT_DIRECTION_LTR
        paint.textAlign = Paint.Align.CENTER
    }
 
    // The value reported by the device. Ignored while the user is in the middle of changing it.
    fun setLevelFromDevice(index: Int) {
        if (pending != null) return
        val clamped = index.coerceIn(0, labels.size - 1)
        if (clamped != level) {
            level = clamped
            invalidate()
        }
    }
 
    private fun scheduleCommit() {
        pending?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            pending = null
            onCommit?.invoke(level)
        }
        pending = runnable
        handler.postDelayed(runnable, 500L)
    }
 
    private fun commitNow() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        onCommit?.invoke(level)
    }
 
    private fun move(delta: Int) {
        val next = (level + delta).coerceIn(0, labels.size - 1)
        if (next != level) {
            level = next
            invalidate()
            scheduleCommit()
        }
    }
 
    private fun indexAt(x: Float): Int {
        val padding = 4f * density
        val segment = (width - 2 * padding) / labels.size
        return ((x - padding) / segment).toInt().coerceIn(0, labels.size - 1)
    }
 
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (44 * density).toInt())
    }
 
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val count = labels.size
        if (count == 0) return
        val gap = 3f * density
        val padding = 4f * density
        val segmentWidth = (width - 2 * padding - gap * (count - 1)) / count
        val top = 6f * density
        val bottom = height - 6f * density
        paint.textSize = 13f * density
 
        for (i in 0 until count) {
            val left = padding + i * (segmentWidth + gap)
            rect.set(left, top, left + segmentWidth, bottom)
            paint.style = Paint.Style.FILL
            paint.color = if (i <= level) UiStyle.blue else UiStyle.track
            canvas.drawRoundRect(rect, 5f * density, 5f * density, paint)
            paint.textSize = 13f * density
            while (paint.measureText(labels[i]) > segmentWidth - 4f * density && paint.textSize > 7f * density) {
                paint.textSize -= 1f * density
            }
            paint.color = if (i <= level) Color.WHITE else UiStyle.textSecondary
            canvas.drawText(labels[i], rect.centerX(), rect.centerY() + paint.textSize * 0.35f, paint)
        }
        if (isFocused) {
            rect.set(1f * density, 1f * density, width - 1f * density, height - 1f * density)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * density
            paint.color = UiStyle.blueDark
            canvas.drawRoundRect(rect, 8f * density, 8f * density, paint)
        }
    }
 
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                move(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                move(1)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
 
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                requestFocus()
                pending?.let { handler.removeCallbacks(it) }
                pending = null
                val index = indexAt(event.x)
                if (index != level) {
                    level = index
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                commitNow()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
 
