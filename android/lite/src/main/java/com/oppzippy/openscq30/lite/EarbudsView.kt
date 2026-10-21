package com.oppzippy.openscq30.lite
 
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import java.util.Locale
 
/**
 * Shows the connected device like the original app: the left earbud on the left, the case in the middle and the
 * right earbud on the right, each with its battery level underneath.
 *
 * Pictures come from the assets folder, named after the model id (lower case), see setModel():
 *   assets/devices/<model id>_earbud.png   one earbud (the left one is drawn mirrored)
 *   assets/devices/<model id>_case.png     the charging case
 * A model without pictures gets a simple drawing instead. Models without a case (or headphones) are laid out
 * according to the battery values that the device reports.
 */
class EarbudsView(context: Context) : View(context) {
    companion object {
        private const val KIND_LEFT = 0
        private const val KIND_RIGHT = 1
        private const val KIND_CASE = 2
    }
 
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
 
    private var modelId = ""
    private var earbudBitmap: Bitmap? = null
    private var caseBitmap: Bitmap? = null
 
    private var leftPercent: Int? = null
    private var rightPercent: Int? = null
    private var casePercent: Int? = null
    private var singlePercent: Int? = null
    private var leftCharging = false
    private var rightCharging = false
    private var caseCharging = false
 
    init {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = 0xFF3C4650.toInt()
    }
 
    // The model id is the internal name shown in the log after connecting, for example "SoundcoreA3954".
    fun setModel(id: String) {
        if (id == modelId) return
        modelId = id
        val key = id.lowercase(Locale.US)
        earbudBitmap = loadAsset("devices/${key}_earbud.png")
        caseBitmap = loadAsset("devices/${key}_case.png")
        invalidate()
    }
 
    fun setBattery(
        left: Int?,
        right: Int?,
        case: Int?,
        single: Int?,
        leftIsCharging: Boolean,
        rightIsCharging: Boolean,
        caseIsCharging: Boolean,
    ) {
        leftPercent = left
        rightPercent = right
        casePercent = case
        singlePercent = single
        leftCharging = leftIsCharging
        rightCharging = rightIsCharging
        caseCharging = caseIsCharging
        invalidate()
    }
 
    private fun loadAsset(name: String): Bitmap? = try {
        context.assets.open(name).use { BitmapFactory.decodeStream(it) }
    } catch (_: Throwable) {
        null
    }
 
    private fun dp(value: Float): Float = value * density
 
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        if (w <= 0f || height <= 0) return
 
        val noData = leftPercent == null && rightPercent == null && casePercent == null && singlePercent == null
        val hasEarbuds = leftPercent != null || rightPercent != null || noData
        if (!hasEarbuds && singlePercent != null) {
            drawHeadphones(canvas, w / 2f, w * 0.6f)
            return
        }
        val hasCase = casePercent != null || noData
        if (hasCase) {
            val slot = w / 3f
            drawSlot(canvas, slot * 0.5f, slot, KIND_LEFT, leftPercent, leftCharging, "שמאל")
            drawSlot(canvas, slot * 1.5f, slot, KIND_CASE, casePercent, caseCharging, "מארז")
            drawSlot(canvas, slot * 2.5f, slot, KIND_RIGHT, rightPercent, rightCharging, "ימין")
        } else {
            val slot = w / 2f
            drawSlot(canvas, slot * 0.5f, slot, KIND_LEFT, leftPercent, leftCharging, "שמאל")
            drawSlot(canvas, slot * 1.5f, slot, KIND_RIGHT, rightPercent, rightCharging, "ימין")
        }
    }
 
    private fun drawSlot(
        canvas: Canvas,
        cx: Float,
        slotWidth: Float,
        kind: Int,
        percent: Int?,
        charging: Boolean,
        label: String,
    ) {
        val imageWidth = slotWidth - dp(12f)
        val dst = RectF(cx - imageWidth / 2f, dp(6f), cx + imageWidth / 2f, height - dp(46f))
        if (kind == KIND_CASE) {
            val bitmap = caseBitmap
            if (bitmap != null) drawFit(canvas, bitmap, dst) else drawCaseShape(canvas, dst)
        } else {
            canvas.save()
            // Only a right earbud picture is needed: the left earbud is its mirror image.
            if (kind == KIND_LEFT) canvas.scale(-1f, 1f, cx, 0f)
            val bitmap = earbudBitmap
            if (bitmap != null) drawFit(canvas, bitmap, dst) else drawEarbudShape(canvas, dst)
            canvas.restore()
        }
        drawBattery(canvas, cx, slotWidth, percent, charging, label)
    }
 
    private fun drawFit(canvas: Canvas, bitmap: Bitmap, dst: RectF) {
        val scale = minOf(dst.width() / bitmap.width, dst.height() / bitmap.height)
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        rect.set(
            dst.centerX() - drawWidth / 2f,
            dst.centerY() - drawHeight / 2f,
            dst.centerX() + drawWidth / 2f,
            dst.centerY() + drawHeight / 2f,
        )
        canvas.drawBitmap(bitmap, null, rect, paint)
    }
 
    private fun fillAndStroke(canvas: Canvas, draw: (Paint) -> Unit) {
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFFFFF.toInt()
        draw(paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = 0xFFB0B7BF.toInt()
        draw(paint)
        paint.style = Paint.Style.FILL
    }
 
    // A simple earbud with a stem, facing right (used when there is no picture for the model).
    private fun drawEarbudShape(canvas: Canvas, dst: RectF) {
        val size = minOf(dst.width(), dst.height())
        val radius = size * 0.28f
        val bodyX = dst.centerX() - size * 0.05f
        val bodyY = dst.top + size * 0.34f
        val stemLeft = bodyX + radius * 0.35f
        val stemRight = bodyX + radius * 0.95f
        rect.set(stemLeft, bodyY, stemRight, bodyY + size * 0.62f)
        val stemRadius = (stemRight - stemLeft) / 2f
        fillAndStroke(canvas) { canvas.drawRoundRect(rect, stemRadius, stemRadius, it) }
        fillAndStroke(canvas) { canvas.drawCircle(bodyX, bodyY, radius, it) }
    }
 
    // A simple charging case (used when there is no picture for the model).
    private fun drawCaseShape(canvas: Canvas, dst: RectF) {
        val size = minOf(dst.width(), dst.height())
        val caseWidth = size * 0.95f
        val caseHeight = size * 0.72f
        rect.set(
            dst.centerX() - caseWidth / 2f,
            dst.centerY() - caseHeight / 2f,
            dst.centerX() + caseWidth / 2f,
            dst.centerY() + caseHeight / 2f,
        )
        val corner = size * 0.18f
        fillAndStroke(canvas) { canvas.drawRoundRect(rect, corner, corner, it) }
        val lidY = rect.top + caseHeight * 0.38f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = 0xFFB0B7BF.toInt()
        canvas.drawLine(rect.left + corner * 0.3f, lidY, rect.right - corner * 0.3f, lidY, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xFF66BB6A.toInt()
        canvas.drawCircle(dst.centerX(), lidY + caseHeight * 0.2f, dp(2.5f), paint)
    }
 
    // Headphones (no separate earbuds): a headband and two ear cups.
    private fun drawHeadphones(canvas: Canvas, cx: Float, slotWidth: Float) {
        val size = minOf(slotWidth, height - dp(52f))
        val top = dp(8f)
        rect.set(cx - size * 0.38f, top, cx + size * 0.38f, top + size * 0.8f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(5f)
        paint.color = 0xFFB0B7BF.toInt()
        canvas.drawArc(rect, 180f, 180f, false, paint)
        paint.style = Paint.Style.FILL
        val cupWidth = size * 0.2f
        val cupHeight = size * 0.36f
        val cupTop = top + size * 0.4f
        paint.color = 0xFFFFFFFF.toInt()
        rect.set(cx - size * 0.38f - cupWidth / 2f, cupTop, cx - size * 0.38f + cupWidth / 2f, cupTop + cupHeight)
        canvas.drawRoundRect(rect, dp(6f), dp(6f), paint)
        rect.set(cx + size * 0.38f - cupWidth / 2f, cupTop, cx + size * 0.38f + cupWidth / 2f, cupTop + cupHeight)
        canvas.drawRoundRect(rect, dp(6f), dp(6f), paint)
        drawBattery(canvas, cx, slotWidth, singlePercent, false, "אוזניות")
    }
 
    // Percent text, a battery bar (red at 20% or less, green above) and the name underneath.
    private fun drawBattery(
        canvas: Canvas,
        cx: Float,
        slotWidth: Float,
        percent: Int?,
        charging: Boolean,
        label: String,
    ) {
        textPaint.textSize = dp(10f)
        canvas.drawText(label, cx, height - dp(4f), textPaint)
        if (percent == null) return
 
        val barWidth = slotWidth * 0.6f
        val barHeight = dp(6f)
        val barTop = height - dp(20f)
        val level = percent.coerceIn(0, 100)
 
        paint.style = Paint.Style.FILL
        paint.color = 0xFFDADDE1.toInt()
        rect.set(cx - barWidth / 2f, barTop, cx + barWidth / 2f, barTop + barHeight)
        canvas.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, paint)
        if (level > 0) {
            paint.color = if (level <= 20) 0xFFE53935.toInt() else 0xFF43A047.toInt()
            rect.set(cx - barWidth / 2f, barTop, cx - barWidth / 2f + barWidth * level / 100f, barTop + barHeight)
            canvas.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, paint)
        }
 
        textPaint.textSize = dp(12f)
        val text = "$level%"
        val baseline = barTop - dp(4f)
        canvas.drawText(text, cx, baseline, textPaint)
        if (charging) {
            val boltX = cx - textPaint.measureText(text) / 2f - dp(7f)
            drawBolt(canvas, boltX, baseline - dp(5f))
        }
    }
 
    // A small lightning bolt, shown next to the percent while charging.
    private fun drawBolt(canvas: Canvas, x: Float, y: Float) {
        path.reset()
        path.moveTo(x + dp(2f), y - dp(6f))
        path.lineTo(x - dp(2.5f), y + dp(0.5f))
        path.lineTo(x, y + dp(0.5f))
        path.lineTo(x - dp(2f), y + dp(6f))
        path.lineTo(x + dp(3f), y - dp(1f))
        path.lineTo(x + dp(0.5f), y - dp(1f))
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFC107.toInt()
        canvas.drawPath(path, paint)
    }
}
 
