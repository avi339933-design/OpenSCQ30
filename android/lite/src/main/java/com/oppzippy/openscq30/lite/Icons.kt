package com.oppzippy.openscq30.lite
 
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
 
enum class IconKind {
    BACK, CHEVRON, CHECK, CHECK_SMALL, GEAR, SOUND, EQUALIZER, TOUCH, GAUGE, BELL, HEADSET,
    PLUS, PLANET, PERSON, DEVICES, RESET, DOT1, DOT2, DOT3, LINE, SLIDE
}
 
// Simple icons drawn in code (the lite app has no image resources). Everything is drawn in a 24x24 space.
class IconView(context: Context, private val kind: IconKind, tint: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private var color = tint
 
    fun setTint(value: Int) {
        color = value
        invalidate()
    }
 
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (24 * resources.displayMetrics.density + 0.5f).toInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }
 
    override fun onDraw(canvas: Canvas) {
        val unit = min(width, height) / 24f
        if (unit <= 0f) return
        canvas.save()
        canvas.translate((width - 24f * unit) / 2f, (height - 24f * unit) / 2f)
        canvas.scale(unit, unit)
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        when (kind) {
            IconKind.BACK -> drawBack(canvas)
            IconKind.CHEVRON -> drawChevron(canvas)
            IconKind.CHECK -> drawCheck(canvas)
            IconKind.CHECK_SMALL -> drawCheckSmall(canvas)
            IconKind.GEAR -> drawGear(canvas)
            IconKind.SOUND -> drawSound(canvas)
            IconKind.EQUALIZER -> drawEqualizer(canvas)
            IconKind.TOUCH -> drawTouch(canvas)
            IconKind.GAUGE -> drawGauge(canvas)
            IconKind.BELL -> drawBell(canvas)
            IconKind.HEADSET -> drawHeadset(canvas)
            IconKind.PLUS -> drawPlus(canvas)
            IconKind.PLANET -> drawPlanet(canvas)
            IconKind.PERSON -> drawPerson(canvas)
            IconKind.DEVICES -> drawDevices(canvas)
            IconKind.RESET -> drawReset(canvas)
            IconKind.DOT1 -> drawDots(canvas, floatArrayOf(12f), 2.6f)
            IconKind.DOT2 -> drawDots(canvas, floatArrayOf(8f, 16f), 2.4f)
            IconKind.DOT3 -> drawDots(canvas, floatArrayOf(5f, 12f, 19f), 2.2f)
            IconKind.LINE -> drawBar(canvas, 3f, 10.5f, 21f, 13.5f)
            IconKind.SLIDE -> drawBar(canvas, 10.5f, 3f, 13.5f, 21f)
        }
        canvas.restore()
    }
 
    // Points to the left (the top bar of the device screen is left-to-right, like the original app).
    private fun drawBack(canvas: Canvas) {
        paint.strokeWidth = 2.4f
        path.reset()
        path.moveTo(15f, 5f)
        path.lineTo(8f, 12f)
        path.lineTo(15f, 19f)
        canvas.drawPath(path, paint)
    }
 
    // A small arrow for rows. Points to the left, because the text of the rows is right-to-left.
    private fun drawChevron(canvas: Canvas) {
        paint.strokeWidth = 2.4f
        path.reset()
        path.moveTo(14f, 6f)
        path.lineTo(8f, 12f)
        path.lineTo(14f, 18f)
        canvas.drawPath(path, paint)
    }
 
    private fun drawCheck(canvas: Canvas) {
        paint.strokeWidth = 2.6f
        path.reset()
        path.moveTo(5f, 12.5f)
        path.lineTo(10f, 17.5f)
        path.lineTo(19f, 7f)
        canvas.drawPath(path, paint)
    }
 
    // A check mark that fits inside a small circle.
    private fun drawCheckSmall(canvas: Canvas) {
        paint.strokeWidth = 3f
        path.reset()
        path.moveTo(7f, 12.5f)
        path.lineTo(10.5f, 16f)
        path.lineTo(17f, 8.5f)
        canvas.drawPath(path, paint)
    }
 
    private fun drawGear(canvas: Canvas) {
        paint.strokeWidth = 3.2f
        paint.strokeCap = Paint.Cap.BUTT
        for (i in 0 until 8) {
            val angle = (i * 45.0) * Math.PI / 180.0
            val c = cos(angle).toFloat()
            val s = sin(angle).toFloat()
            canvas.drawLine(12f + 7f * c, 12f + 7f * s, 12f + 10f * c, 12f + 10f * s, paint)
        }
        paint.strokeWidth = 2.2f
        canvas.drawCircle(12f, 12f, 6.5f, paint)
        canvas.drawCircle(12f, 12f, 2.6f, paint)
    }
 
    private fun drawSound(canvas: Canvas) {
        paint.strokeWidth = 2.4f
        val xs = floatArrayOf(4f, 8f, 12f, 16f, 20f)
        val hs = floatArrayOf(3f, 7f, 10f, 6f, 3f)
        for (i in xs.indices) {
            canvas.drawLine(xs[i], 12f - hs[i], xs[i], 12f + hs[i], paint)
        }
    }
 
    private fun drawEqualizer(canvas: Canvas) {
        val xs = floatArrayOf(6f, 12f, 18f)
        val ys = floatArrayOf(14f, 8f, 15f)
        for (i in xs.indices) {
            canvas.drawLine(xs[i], 3f, xs[i], 21f, paint)
        }
        paint.style = Paint.Style.FILL
        for (i in xs.indices) {
            canvas.drawCircle(xs[i], ys[i], 2.8f, paint)
        }
    }
 
    private fun drawTouch(canvas: Canvas) {
        rect.set(9f, 3f, 14f, 14f)
        canvas.drawRoundRect(rect, 2.5f, 2.5f, paint)
        rect.set(5.5f, 11f, 19f, 21.5f)
        canvas.drawRoundRect(rect, 4f, 4f, paint)
    }
 
    // A pressure gauge: an open arc with a needle.
    private fun drawGauge(canvas: Canvas) {
        rect.set(3f, 3f, 21f, 21f)
        canvas.drawArc(rect, 150f, 240f, false, paint)
        canvas.drawLine(12f, 13f, 16f, 8f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(12f, 13f, 1.6f, paint)
    }
 
    private fun drawBell(canvas: Canvas) {
        paint.strokeWidth = 1.8f
        rect.set(6f, 3.5f, 18f, 15.5f)
        canvas.drawArc(rect, 180f, 180f, false, paint)
        canvas.drawLine(6f, 9.5f, 4.5f, 17f, paint)
        canvas.drawLine(18f, 9.5f, 19.5f, 17f, paint)
        canvas.drawLine(4.5f, 17f, 19.5f, 17f, paint)
        rect.set(10f, 18f, 14f, 22f)
        canvas.drawArc(rect, 0f, 180f, false, paint)
    }
 
    private fun drawHeadset(canvas: Canvas) {
        paint.strokeWidth = 1.8f
        rect.set(4f, 4f, 20f, 20f)
        canvas.drawArc(rect, 180f, 180f, false, paint)
        rect.set(3f, 12f, 7f, 18f)
        canvas.drawRoundRect(rect, 2f, 2f, paint)
        rect.set(17f, 12f, 21f, 18f)
        canvas.drawRoundRect(rect, 2f, 2f, paint)
        canvas.drawLine(19f, 18f, 19f, 20f, paint)
        canvas.drawLine(19f, 20f, 14f, 20f, paint)
    }
 
    private fun drawPlus(canvas: Canvas) {
        paint.strokeWidth = 2f
        canvas.drawLine(12f, 5f, 12f, 19f, paint)
        canvas.drawLine(5f, 12f, 19f, 12f, paint)
    }
 
    private fun drawPlanet(canvas: Canvas) {
        paint.strokeWidth = 2f
        canvas.drawCircle(12f, 12f, 5.5f, paint)
        canvas.save()
        canvas.rotate(-20f, 12f, 12f)
        rect.set(2f, 8.5f, 22f, 15.5f)
        canvas.drawOval(rect, paint)
        canvas.restore()
    }
 
    private fun drawPerson(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        canvas.drawCircle(12f, 8f, 4f, paint)
        rect.set(4f, 14f, 20f, 30f)
        canvas.drawArc(rect, 180f, 180f, true, paint)
    }
 
    // A stylized "d" like the Soundcore logo.
    private fun drawDevices(canvas: Canvas) {
        paint.strokeWidth = 2.8f
        canvas.drawCircle(10f, 14f, 5f, paint)
        canvas.drawLine(15f, 4f, 15f, 19f, paint)
    }
 
    private fun drawReset(canvas: Canvas) {
        rect.set(5f, 5f, 19f, 19f)
        canvas.drawArc(rect, 30f, 290f, false, paint)
        paint.style = Paint.Style.FILL
        path.reset()
        path.moveTo(15.06f, 9.43f)
        path.lineTo(19.93f, 10.56f)
        path.lineTo(19.66f, 5.57f)
        path.close()
        canvas.drawPath(path, paint)
    }
 
    private fun drawDots(canvas: Canvas, xs: FloatArray, radius: Float) {
        paint.style = Paint.Style.FILL
        for (x in xs) {
            canvas.drawCircle(x, 12f, radius, paint)
        }
    }
 
    private fun drawBar(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        paint.style = Paint.Style.FILL
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, 1.5f, 1.5f, paint)
    }
}
 




