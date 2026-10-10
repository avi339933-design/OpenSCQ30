package com.oppzippy.openscq30.lite

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class IconKind { BACK, REFRESH, GEAR, SOUND, EQUALIZER, TOUCH }

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
            IconKind.REFRESH -> drawRefresh(canvas)
            IconKind.GEAR -> drawGear(canvas)
            IconKind.SOUND -> drawSound(canvas)
            IconKind.EQUALIZER -> drawEqualizer(canvas)
            IconKind.TOUCH -> drawTouch(canvas)
        }
        canvas.restore()
    }

    // The screen is right-to-left, so "back" points to the right.
    private fun drawBack(canvas: Canvas) {
        paint.strokeWidth = 2.4f
        path.reset()
        path.moveTo(9f, 5f)
        path.lineTo(16f, 12f)
        path.lineTo(9f, 19f)
        canvas.drawPath(path, paint)
    }

    private fun drawRefresh(canvas: Canvas) {
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
}

// A simple picture of two earbuds on a pale circle (our own drawing, not the official image).
class EarbudsView(context: Context, private val soft: Int, private val accent: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = min(w, h) / 2f
        val stroke = resources.displayMetrics.density * 1.5f
        paint.style = Paint.Style.FILL
        paint.color = soft
        canvas.drawCircle(w / 2f, h / 2f, r, paint)
        for (side in intArrayOf(-1, 1)) {
            canvas.save()
            canvas.translate(w / 2f + side * r * 0.36f, h / 2f)
            canvas.rotate(side * 16f)
            drawBud(canvas, r, stroke)
            canvas.restore()
        }
    }

    private fun drawBud(canvas: Canvas, r: Float, stroke: Float) {
        rect.set(-r * 0.09f, -r * 0.1f, r * 0.09f, r * 0.55f)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawRoundRect(rect, r * 0.09f, r * 0.09f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = accent
        canvas.drawRoundRect(rect, r * 0.09f, r * 0.09f, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(0f, -r * 0.2f, r * 0.3f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = accent
        canvas.drawCircle(0f, -r * 0.2f, r * 0.3f, paint)
    }
}
