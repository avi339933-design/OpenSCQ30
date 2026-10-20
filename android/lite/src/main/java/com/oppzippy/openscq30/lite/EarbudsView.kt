package com.oppzippy.openscq30.lite
 
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
 
/**
 * Pictures of the devices. The picture of a model is looked up in this order:
 *   1. assets/devices/<model id in lower case>_hero.png   (add this file to show another model)
 *   2. the built-in Liberty 4 Pro picture (EarbudsImage.kt) for model ids that contain "a3954"
 *   3. nothing: a simple drawing is shown instead.
 */
object DevicePictures {
    private val cache = HashMap<String, Bitmap?>()
 
    fun hero(context: Context, modelId: String): Bitmap? {
        val key = modelId.lowercase(Locale.US)
        if (cache.containsKey(key)) return cache[key]
        var bitmap: Bitmap? = try {
            context.assets.open("devices/${key}_hero.png").use { BitmapFactory.decodeStream(it) }
        } catch (_: Throwable) {
            null
        }
        if (bitmap == null && key.contains("a3954")) bitmap = EarbudsImage.bitmap()
        cache[key] = bitmap
        return bitmap
    }
}
 
// Draws a device picture (or simple shapes when there is no picture) inside a rectangle.
class PictureDrawer(private val density: Float) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
 
    private fun dp(value: Float): Float = value * density
 
    fun draw(canvas: Canvas, bitmap: Bitmap?, dst: RectF, headphones: Boolean = false) {
        if (bitmap != null) {
            drawFit(canvas, bitmap, dst)
        } else if (headphones) {
            drawHeadphones(canvas, dst)
        } else {
            val caseDst = RectF(dst.left, dst.top, dst.left + dst.width() * 0.58f, dst.bottom)
            val budDst = RectF(dst.left + dst.width() * 0.55f, dst.top, dst.right, dst.bottom)
            drawCaseShape(canvas, caseDst)
            drawEarbudShape(canvas, budDst)
        }
    }
 
    private fun drawFit(canvas: Canvas, bitmap: Bitmap, dst: RectF) {
        val scale = min(dst.width() / bitmap.width, dst.height() / bitmap.height)
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        rect.set(
            dst.centerX() - drawWidth / 2f,
            dst.centerY() - drawHeight / 2f,
            dst.centerX() + drawWidth / 2f,
            dst.centerY() + drawHeight / 2f,
        )
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawBitmap(bitmap, null, rect, paint)
    }
 
    private fun fillAndStroke(canvas: Canvas, draw: (Paint) -> Unit) {
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFFFFF.toInt()
        draw(paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = 0xFF9EA7B3.toInt()
        draw(paint)
        paint.style = Paint.Style.FILL
    }
 
    // A simple earbud with a stem, facing right (used when there is no picture for the model).
    private fun drawEarbudShape(canvas: Canvas, dst: RectF) {
        val size = min(dst.width(), dst.height())
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
        val size = min(dst.width(), dst.height())
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
        paint.color = 0xFF9EA7B3.toInt()
        canvas.drawLine(rect.left + corner * 0.3f, lidY, rect.right - corner * 0.3f, lidY, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xFF66BB6A.toInt()
        canvas.drawCircle(dst.centerX(), lidY + caseHeight * 0.2f, dp(2.5f), paint)
    }
 
    // Headphones (no separate earbuds): a headband and two ear cups.
    private fun drawHeadphones(canvas: Canvas, dst: RectF) {
        val cx = dst.centerX()
        val size = min(dst.width(), dst.height())
        val top = dst.top + dp(4f)
        rect.set(cx - size * 0.38f, top, cx + size * 0.38f, top + size * 0.8f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(5f)
        paint.color = 0xFF9EA7B3.toInt()
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
    }
}
 
// A small picture of the device, used on the "my devices" cards.
class DevicePictureView(context: Context) : View(context) {
    private val drawer = PictureDrawer(resources.displayMetrics.density)
    private var modelId = ""
    private var bitmap: Bitmap? = null
 
    fun setModel(id: String) {
        if (id == modelId) return
        modelId = id
        bitmap = if (id.isEmpty()) null else DevicePictures.hero(context, id)
        invalidate()
    }
 
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        drawer.draw(canvas, bitmap, RectF(0f, 0f, width.toFloat(), height.toFloat()))
    }
}
 
/**
 * The top of the device screen, like the original app: a picture of the device on a pale blue shape, and a row of
 * thin battery bars underneath (left earbud, right earbud, case) with a small icon and the percent.
 * The order of the batteries is always left to right on the screen.
 */
class EarbudsView(context: Context) : View(context) {
    companion object {
        private const val KIND_LEFT = 0
        private const val KIND_RIGHT = 1
        private const val KIND_CASE = 2
        private const val KIND_SINGLE = 3
    }
 
    private val density = resources.displayMetrics.density
    private val drawer = PictureDrawer(density)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
 
    private var modelId = ""
    private var hero: Bitmap? = null
 
    private var leftPercent: Int? = null
    private var rightPercent: Int? = null
    private var casePercent: Int? = null
    private var singlePercent: Int? = null
    private var leftCharging = false
    private var rightCharging = false
    private var caseCharging = false
 
    init {
        valuePaint.textAlign = Paint.Align.LEFT
        valuePaint.color = 0xFF1B1F24.toInt()
        valuePaint.typeface = Typeface.DEFAULT_BOLD
        letterPaint.textAlign = Paint.Align.CENTER
        letterPaint.color = 0xFF6B7480.toInt()
        letterPaint.typeface = Typeface.DEFAULT_BOLD
    }
 
    // The model id is the internal name shown in the log after connecting, for example "SoundcoreA3954".
    fun setModel(id: String) {
        if (id == modelId) return
        modelId = id
        hero = if (id.isEmpty()) null else DevicePictures.hero(context, id)
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
 
    private fun dp(value: Float): Float = value * density
 
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
 
        val pictureBottom = h - dp(38f)
 
        // The pale shape behind the picture (on the right, like the original app).
        paint.style = Paint.Style.FILL
        paint.color = 0xFFE6F6FD.toInt()
        canvas.drawCircle(w * 0.74f, pictureBottom * 0.5f + dp(6f), pictureBottom * 0.5f, paint)
 
        val headphonesOnly = leftPercent == null && rightPercent == null && casePercent == null &&
            singlePercent != null
        val dst = RectF(w * 0.14f, dp(6f), w * 0.68f, pictureBottom)
        drawer.draw(canvas, hero, dst, headphonesOnly)
 
        // The battery items, from left to right.
        val items = ArrayList<IntArray>() // kind, percent, charging (0/1)
        leftPercent?.let { items.add(intArrayOf(KIND_LEFT, it, if (leftCharging) 1 else 0)) }
        rightPercent?.let { items.add(intArrayOf(KIND_RIGHT, it, if (rightCharging) 1 else 0)) }
        casePercent?.let { items.add(intArrayOf(KIND_CASE, it, if (caseCharging) 1 else 0)) }
        if (items.isEmpty()) {
            singlePercent?.let { items.add(intArrayOf(KIND_SINGLE, it, 0)) }
        }
        if (items.isEmpty()) return
 
        val span = w * 0.84f
        val slot = span / max(items.size, 3)
        val startX = (w - slot * items.size) / 2f
        items.forEachIndexed { index, item ->
            val cx = startX + slot * (index + 0.5f)
            drawBatteryItem(canvas, cx, slot, h, item[0], item[1], item[2] == 1)
        }
    }
 
    // A small icon, the percent text and a thin bar (red at 20% or less, green above).
    private fun drawBatteryItem(
        canvas: Canvas,
        cx: Float,
        slot: Float,
        h: Float,
        kind: Int,
        percent: Int,
        charging: Boolean,
    ) {
        val level = percent.coerceIn(0, 100)
        val text = "$level%"
        valuePaint.textSize = dp(11f)
        val textWidth = valuePaint.measureText(text)
        val iconWidth = if (kind == KIND_SINGLE) 0f else dp(13f)
        val gap = if (kind == KIND_SINGLE) 0f else dp(4f)
        val boltWidth = if (charging) dp(8f) else 0f
        val total = boltWidth + iconWidth + gap + textWidth
        var x = cx - total / 2f
        val baseline = h - dp(17f)
        val centerY = baseline - dp(4f)
 
        if (charging) {
            drawBolt(canvas, x + dp(3f), centerY)
            x += boltWidth
        }
        if (kind != KIND_SINGLE) drawBatteryIcon(canvas, kind, x + iconWidth / 2f, centerY)
        canvas.drawText(text, x + iconWidth + gap, baseline, valuePaint)
 
        val barWidth = slot * 0.72f
        val barHeight = dp(3f)
        val barTop = h - dp(10f)
        paint.style = Paint.Style.FILL
        paint.color = 0xFFDDE3EA.toInt()
        rect.set(cx - barWidth / 2f, barTop, cx + barWidth / 2f, barTop + barHeight)
        canvas.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, paint)
        if (level > 0) {
            paint.color = if (level <= 20) 0xFFE0453A.toInt() else 0xFF2EAE4F.toInt()
            rect.set(cx - barWidth / 2f, barTop, cx - barWidth / 2f + barWidth * level / 100f, barTop + barHeight)
            canvas.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, paint)
        }
    }
 
    // L / R in a small circle, or a small case.
    private fun drawBatteryIcon(canvas: Canvas, kind: Int, cx: Float, cy: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.2f)
        paint.color = 0xFF6B7480.toInt()
        if (kind == KIND_CASE) {
            rect.set(cx - dp(6f), cy - dp(4.5f), cx + dp(6f), cy + dp(4.5f))
            canvas.drawRoundRect(rect, dp(3f), dp(3f), paint)
            canvas.drawLine(rect.left, cy - dp(1f), rect.right, cy - dp(1f), paint)
        } else {
            canvas.drawCircle(cx, cy, dp(6.2f), paint)
            letterPaint.textSize = dp(7.5f)
            canvas.drawText(if (kind == KIND_LEFT) "L" else "R", cx, cy + dp(2.6f), letterPaint)
        }
        paint.style = Paint.Style.FILL
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
 
