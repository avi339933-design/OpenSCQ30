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
 * Pictures of the devices. The pictures of a model are looked up in assets/devices/, where the file name starts
 * with the model id in lower case (for example "soundcorea3954"):
 *   <model>_hero.png    the whole product (used on the "my devices" cards, and for headphones)
 *   <model>_left.png    the left earbud alone   (shown above the left battery)
 *   <model>_right.png   the right earbud alone  (shown above the right battery)
 *   <model>_case.png    the charging case       (shown above the case battery)
 * If there is no hero file, the built-in Liberty 4 Pro picture (EarbudsImage.kt) is used for model ids that contain
 * "a3954". Pictures that do not exist are replaced by a simple drawing.
 */
object DevicePictures {
    private val cache = HashMap<String, Bitmap?>()
 
    private fun loadAsset(context: Context, name: String): Bitmap? = try {
        context.assets.open("devices/$name").use { BitmapFactory.decodeStream(it) }
    } catch (_: Throwable) {
        null
    }
 
    fun hero(context: Context, modelId: String): Bitmap? {
        val key = modelId.lowercase(Locale.US)
        if (cache.containsKey(key)) return cache[key]
        var bitmap: Bitmap? = loadAsset(context, "${key}_hero.png")
        if (bitmap == null && key.contains("a3954")) bitmap = EarbudsImage.bitmap()
        cache[key] = bitmap
        return bitmap
    }
 
    // kind is "left", "right" or "case".
    fun part(context: Context, modelId: String, kind: String): Bitmap? {
        val model = modelId.lowercase(Locale.US)
        val key = "$model/$kind"
        if (cache.containsKey(key)) return cache[key]
        val bitmap = loadAsset(context, "${model}_$kind.png")
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
 
    // One picture, scaled to fit inside the rectangle.
    fun drawBitmap(canvas: Canvas, bitmap: Bitmap, dst: RectF) {
        drawFit(canvas, bitmap, dst)
    }
 
    // One simple earbud (used when a model has no earbud picture).
    fun drawEarbud(canvas: Canvas, dst: RectF) {
        drawEarbudShape(canvas, dst)
    }
 
    // One simple charging case (used when a model has no case picture).
    fun drawCase(canvas: Canvas, dst: RectF) {
        drawCaseShape(canvas, dst)
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
 * The top of the device screen, like the original app: a row of thin battery bars (left earbud, right earbud,
 * case) with a small icon and the percent, and above each battery the picture of that part: the left earbud above
 * the left battery, the right earbud above the right battery and the case above the case battery.
 * The order is always left to right on the screen.
 * Models that have no separate earbud pictures show one picture of the whole device on a pale shape instead.
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
    private var leftPart: Bitmap? = null
    private var rightPart: Bitmap? = null
    private var casePart: Bitmap? = null
 
    private var leftPercent: Int? = null
    private var rightPercent: Int? = null
    private var casePercent: Int? = null
    private var singlePercent: Int? = null
    private var leftCharging = false
    private var rightCharging = false
    private var caseCharging = false
 
    init {
        valuePaint.textAlign = Paint.Align.LEFT
        valuePaint.color = 0xFF333333.toInt()
        valuePaint.typeface = Typeface.DEFAULT_BOLD
        letterPaint.textAlign = Paint.Align.CENTER
        letterPaint.color = 0xFF999999.toInt()
        letterPaint.typeface = Typeface.DEFAULT_BOLD
    }
 
    // The model id is the internal name shown in the log after connecting, for example "SoundcoreA3954".
    fun setModel(id: String) {
        if (id == modelId) return
        modelId = id
        hero = if (id.isEmpty()) null else DevicePictures.hero(context, id)
        leftPart = if (id.isEmpty()) null else DevicePictures.part(context, id, "left")
        rightPart = if (id.isEmpty()) null else DevicePictures.part(context, id, "right")
        casePart = if (id.isEmpty()) null else DevicePictures.part(context, id, "case")
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
 
        // The battery items, from left to right.
        val items = ArrayList<IntArray>() // kind, percent, charging (0/1)
        leftPercent?.let { items.add(intArrayOf(KIND_LEFT, it, if (leftCharging) 1 else 0)) }
        rightPercent?.let { items.add(intArrayOf(KIND_RIGHT, it, if (rightCharging) 1 else 0)) }
        casePercent?.let { items.add(intArrayOf(KIND_CASE, it, if (caseCharging) 1 else 0)) }
        if (items.isEmpty()) {
            singlePercent?.let { items.add(intArrayOf(KIND_SINGLE, it, 0)) }
        }
 
        // The original: battery blocks 54dp wide with 20dp between them, centered.
        val slot = dp(54f)
        val gap = dp(20f)
        val startX = (w - (slot * items.size + gap * max(items.size - 1, 0))) / 2f
 
        val separateParts = items.isNotEmpty() && (leftPart != null || rightPart != null)
        if (separateParts) {
            // Each part above its own battery.
            items.forEachIndexed { index, item ->
                val cx = startX + (slot + gap) * index + slot / 2f
                drawPart(canvas, cx, slot, pictureBottom, item[0])
            }
        } else {
            // One picture of the whole device on a pale shape (on the right, like the original app).
            paint.style = Paint.Style.FILL
            paint.color = 0xFFE6F6FD.toInt()
            canvas.drawCircle(w * 0.74f, pictureBottom * 0.5f + dp(6f), pictureBottom * 0.5f, paint)
 
            val headphonesOnly = leftPercent == null && rightPercent == null && casePercent == null &&
                singlePercent != null
            val dst = RectF(w * 0.14f, dp(6f), w * 0.68f, pictureBottom)
            drawer.draw(canvas, hero, dst, headphonesOnly)
        }
 
        if (items.isEmpty()) return
        items.forEachIndexed { index, item ->
            val cx = startX + (slot + gap) * index + slot / 2f
            drawBatteryItem(canvas, cx, slot, h, item[0], item[1], item[2] == 1)
        }
    }
 
    // The picture of one part (left earbud, right earbud or case) on a small pale circle, above its battery.
    private fun drawPart(canvas: Canvas, cx: Float, slot: Float, bottom: Float, kind: Int) {
        val top = dp(6f)
        val height = bottom - top
        val width = slot + dp(16f)
        val radius = min(width, height) * 0.48f
        paint.style = Paint.Style.FILL
        paint.color = 0xFFE6F6FD.toInt()
        canvas.drawCircle(cx, top + height / 2f, radius, paint)
 
        val inset = width * 0.06f
        val dst = RectF(cx - width / 2f + inset, top + height * 0.08f, cx + width / 2f - inset, bottom - height * 0.08f)
        val left = leftPart
        val right = rightPart
        val case = casePart
        when (kind) {
            KIND_LEFT -> if (left != null) drawer.drawBitmap(canvas, left, dst) else drawer.drawEarbud(canvas, dst)
            KIND_RIGHT -> if (right != null) drawer.drawBitmap(canvas, right, dst) else drawer.drawEarbud(canvas, dst)
            KIND_CASE -> if (case != null) drawer.drawBitmap(canvas, case, dst) else drawer.drawCase(canvas, dst)
            else -> drawer.draw(canvas, hero, dst, true)
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
 
        val barWidth = slot
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
        paint.color = 0xFF999999.toInt()
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
 
