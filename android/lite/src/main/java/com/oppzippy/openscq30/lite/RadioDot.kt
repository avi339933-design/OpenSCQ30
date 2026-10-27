package com.oppzippy.openscq30.lite
 
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
 
/** A round selection mark: a blue circle with a check when selected, a gray ring otherwise. */
class RadioDot(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
 
    var checked: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
 
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((28 * density).toInt(), (28 * density).toInt())
    }
 
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = 10f * density
        if (checked) {
            paint.style = Paint.Style.FILL
            paint.color = UiStyle.blue
            canvas.drawCircle(cx, cy, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * density
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawLine(cx - 4f * density, cy, cx - 1f * density, cy + 3f * density, paint)
            canvas.drawLine(cx - 1f * density, cy + 3f * density, cx + 4f * density, cy - 3f * density, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * density
            paint.color = 0xFFB0B7BF.toInt()
            canvas.drawCircle(cx, cy, radius, paint)
        }
    }
}
  
