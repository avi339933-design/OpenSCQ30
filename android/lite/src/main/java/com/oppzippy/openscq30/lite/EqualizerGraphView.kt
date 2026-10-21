package com.oppzippy.openscq30.lite
 
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import java.util.Locale
 
/**
 * The equalizer as a curve through one point per band, like the original app.
 *
 * Keypad: focus the graph and press OK to start editing. Then left / right choose the band, up / down change its
 * value (holding the key goes faster) and OK (or Back) finishes. The new values are sent half a second after
 * the last change. Touch: tap or drag on the graph.
 *
 * Values are in the device's own units (for example tenths of a dB); [scale] converts them to dB.
 */
class EqualizerGraphView(
    context: Context,
    private val bandLabels: List<String>,
    private val min: Int,
    private val max: Int,
    private val scale: Double,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
 
    private val bandCount = bandLabels.size
    private val values = IntArray(bandCount)
    private var editing = false
    private var touching = false
    private var selectedBand = 0
 
    var readOnly = false
 
    // Called with all band values when the user stopped changing them.
    var onCommit: ((IntArray) -> Unit)? = null
 
    init {
        isFocusable = true
        layoutDirection = LAYOUT_DIRECTION_LTR
    }
 
    // The values reported by the device. Ignored while the user is changing them.
    fun setValues(newValues: IntArray) {
        if (editing || touching || pending != null) return
        for (i in 0 until bandCount) values[i] = newValues.getOrElse(i) { 0 }.coerceIn(min, max)
        invalidate()
    }
 
    private fun scheduleCommit() {
        pending?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            pending = null
            onCommit?.invoke(values.copyOf())
        }
        pending = runnable
        handler.postDelayed(runnable, 500L)
    }
 
    private fun commitNow() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        onCommit?.invoke(values.copyOf())
    }
 
    // ---- geometry ----
 
    private fun plotLeft() = 30f * density
    private fun plotRight() = width - 8f * density
    private fun plotTop() = 22f * density
    private fun plotBottom() = height - 24f * density
 
    private fun xOf(band: Int): Float = plotLeft() + (band + 0.5f) * (plotRight() - plotLeft()) / bandCount
 
    private fun yOf(value: Int): Float =
        plotBottom() - (value - min).toFloat() * (plotBottom() - plotTop()) / (max - min)
 
    private fun valueAt(y: Float): Int {
        val raw = min + (plotBottom() - y) / (plotBottom() - plotTop()) * (max - min)
        return Math.round(raw).coerceIn(min, max)
    }
 
    private fun bandAt(x: Float): Int {
        val slot = (plotRight() - plotLeft()) / bandCount
        return Math.round((x - plotLeft()) / slot - 0.5f).coerceIn(0, bandCount - 1)
    }
 
    private fun db(value: Int): String =
        if (Math.abs(value / scale) < 0.05) "0" else String.format(Locale.US, "%+.1f", value / scale)
 
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (220 * density).toInt())
    }
 
    // ---- drawing ----
 
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bandCount == 0 || max <= min) return
 
        // Grid lines with the dB labels.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        textPaint.textSize = 10f * density
        textPaint.color = UiStyle.textSecondary
        textPaint.textAlign = Paint.Align.RIGHT
        for (k in 0..4) {
            val value = min + (max - min) * k / 4
            val y = yOf(value)
            paint.color = UiStyle.divider
            canvas.drawLine(plotLeft(), y, plotRight(), y, paint)
            val label = if (Math.abs(value / scale) < 0.05) "0" else String.format(Locale.US, "%+.0f", value / scale)
            canvas.drawText(label, plotLeft() - 4f * density, y + 4f * density, textPaint)
        }
        if (min < 0 && max > 0) {
            paint.color = UiStyle.track
            canvas.drawLine(plotLeft(), yOf(0), plotRight(), yOf(0), paint)
        }
        paint.color = UiStyle.divider
        for (i in 0 until bandCount) {
            canvas.drawLine(xOf(i), plotTop(), xOf(i), plotBottom(), paint)
        }
 
        // Band names under the graph.
        textPaint.textAlign = Paint.Align.CENTER
        for (i in 0 until bandCount) {
            canvas.drawText(bandLabels[i], xOf(i), height - 8f * density, textPaint)
        }
 
        // The curve.
        path.reset()
        for (i in 0 until bandCount) {
            if (i == 0) path.moveTo(xOf(i), yOf(values[i])) else path.lineTo(xOf(i), yOf(values[i]))
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = UiStyle.blue
        canvas.drawPath(path, paint)
 
        // The points.
        for (i in 0 until bandCount) {
            val x = xOf(i)
            val y = yOf(values[i])
            val active = (editing || touching) && i == selectedBand
            paint.style = Paint.Style.FILL
            paint.color = if (active) UiStyle.blue else UiStyle.card
            canvas.drawCircle(x, y, if (active) 8f * density else 6f * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * density
            paint.color = UiStyle.blue
            canvas.drawCircle(x, y, if (active) 8f * density else 6f * density, paint)
        }
 
        // The value of the band being changed.
        if (editing || touching) {
            textPaint.textSize = 12f * density
            textPaint.color = UiStyle.textPrimary
            val x = xOf(selectedBand)
            val y = yOf(values[selectedBand]) - 12f * density
            canvas.drawText(db(values[selectedBand]) + " dB", x.coerceIn(30f * density, width - 30f * density), y.coerceAtLeast(14f * density), textPaint)
            textPaint.textSize = 10f * density
            textPaint.color = UiStyle.textSecondary
        }
 
        // A short hint while the graph has focus.
        if (isFocused && !readOnly) {
            textPaint.textSize = 10f * density
            textPaint.color = UiStyle.blueDark
            val hint = if (editing) "עריכה: חצים לשינוי, OK לסיום" else "לחץ OK כדי לערוך"
            canvas.drawText(hint, width / 2f, 12f * density, textPaint)
        }
        if (isFocused) {
            rect.set(1f * density, 1f * density, width - 1f * density, height - 1f * density)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * density
            paint.color = UiStyle.blueDark
            canvas.drawRoundRect(rect, 8f * density, 8f * density, paint)
        }
    }
 
    // ---- keypad ----
 
    private fun change(delta: Int) {
        values[selectedBand] = (values[selectedBand] + delta).coerceIn(min, max)
        scheduleCommit()
    }
 
    private fun stepFor(event: KeyEvent): Int = when {
        event.repeatCount > 8 -> 5
        event.repeatCount > 3 -> 2
        else -> 1
    }
 
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (readOnly) return super.onKeyDown(keyCode, event)
        if (!editing) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                editing = true
                invalidate()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> selectedBand = (selectedBand - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> selectedBand = (selectedBand + 1).coerceAtMost(bandCount - 1)
            KeyEvent.KEYCODE_DPAD_UP -> change(stepFor(event))
            KeyEvent.KEYCODE_DPAD_DOWN -> change(-stepFor(event))
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> {
                editing = false
                if (pending != null) commitNow()
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        invalidate()
        return true
    }
 
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus && editing) {
            editing = false
            if (pending != null) commitNow()
        }
        invalidate()
    }
 
    // ---- touch ----
 
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (readOnly) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touching = true
                requestFocus()
                pending?.let { handler.removeCallbacks(it) }
                pending = null
                selectedBand = bandAt(event.x)
                values[selectedBand] = valueAt(event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                commitNow()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
 
