package com.oppzippy.openscq30.lite
 
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.min
 
// One drawing step of an icon: a path filled and/or stroked in the tint color.
class IconLayer(
    val data: String,
    val fill: Boolean,
    val strokeWidth: Float,
    val round: Boolean,
    val evenOdd: Boolean,
)
 
/**
 * Icons taken from the original Soundcore app (its vector drawables), drawn with plain Canvas paths so they work
 * on Android 4.4. The numbers are the size of the original drawing area.
 */
enum class OfficialIcon(val viewport: Float, val layers: List<IconLayer>) {
    AMBIENT_TRANSPARENCY(20.0f, listOf(
            IconLayer("M12.838,14.268C14.174,14.268 15.257,15.351 15.257,16.688L15.257,16.895C15.257,17.229 14.986,17.5 14.652,17.5H5.578C5.244,17.5 4.973,17.229 4.973,16.895L4.974,16.688C4.974,15.351 6.057,14.268 7.393,14.268H12.838ZM15.947,11.793L16.867,12.084L16.932,12.11C17.112,12.2 17.211,12.399 17.175,12.596L17.158,12.662L17.051,12.953C16.96,13.184 16.858,13.41 16.744,13.63L16.626,13.849L16.547,13.984C16.42,14.18 16.159,14.236 15.964,14.109L15.124,13.564C14.922,13.433 14.858,13.167 14.978,12.958L15.101,12.731C15.172,12.591 15.237,12.448 15.296,12.303L15.383,12.074C15.464,11.844 15.714,11.719 15.947,11.793ZM4.617,11.873L4.695,12.081C4.803,12.354 4.931,12.618 5.086,12.884L5.11,12.938C5.166,13.101 5.102,13.282 4.955,13.373L4.023,13.953L3.966,13.982C3.794,14.052 3.592,13.987 3.494,13.82L3.345,13.557C3.19,13.27 3.055,12.974 2.938,12.67L2.847,12.418C2.78,12.187 2.914,11.946 3.145,11.88L4.097,11.607C4.314,11.545 4.543,11.662 4.617,11.873ZM10.047,6.35C12.01,6.35 13.575,8.058 13.354,10.08C13.185,11.616 11.951,12.86 10.426,13.029C8.418,13.252 6.72,11.678 6.72,9.699C6.72,7.848 8.21,6.35 10.047,6.35ZM17.002,8.546C17.201,8.541 17.381,8.667 17.444,8.856L17.46,8.921L17.509,9.245C17.555,9.609 17.575,9.977 17.568,10.347L17.567,10.369C17.548,10.613 17.335,10.795 17.092,10.776L16.129,10.7L16.056,10.689C15.852,10.641 15.705,10.456 15.708,10.241L15.708,10.202C15.709,9.979 15.697,9.755 15.672,9.533L15.643,9.311L15.632,9.245C15.592,9.01 15.747,8.785 15.982,8.739L16.927,8.555L17.002,8.546ZM3.016,8.337L4.061,8.572C4.266,8.618 4.399,8.816 4.365,9.02L4.333,9.243C4.296,9.537 4.282,9.835 4.291,10.149L4.284,10.216C4.251,10.382 4.11,10.505 3.939,10.514L2.863,10.566C2.631,10.573 2.438,10.392 2.431,10.157L2.428,9.859C2.431,9.537 2.454,9.216 2.498,8.897L2.538,8.64C2.586,8.424 2.8,8.288 3.016,8.337ZM15.882,5.421L15.924,5.466L15.999,5.563C16.201,5.828 16.385,6.105 16.55,6.392L16.67,6.61L16.706,6.678L16.732,6.746C16.796,6.96 16.694,7.194 16.485,7.289L15.639,7.672L15.565,7.701C15.381,7.755 15.182,7.686 15.07,7.53L15.036,7.475L14.895,7.223C14.818,7.093 14.735,6.966 14.648,6.842L14.495,6.636C14.371,6.477 14.369,6.262 14.476,6.103L14.512,6.057L14.559,6.01L15.276,5.403L15.337,5.359C15.512,5.255 15.735,5.28 15.882,5.421ZM4.675,5.215L5.422,5.869C5.604,6.028 5.629,6.303 5.478,6.492L5.32,6.704C5.184,6.894 5.061,7.092 4.951,7.297L4.916,7.355C4.789,7.532 4.551,7.596 4.349,7.499L3.473,7.075L3.41,7.037C3.23,6.909 3.168,6.665 3.274,6.462L3.41,6.215C3.552,5.967 3.709,5.727 3.88,5.496L4.066,5.256C4.223,5.077 4.496,5.059 4.675,5.215ZM12.791,3.104L12.847,3.114L12.903,3.132L13.007,3.178C13.347,3.331 13.675,3.509 13.99,3.71L14.254,3.887C14.454,4.037 14.494,4.322 14.344,4.522L13.765,5.291L13.722,5.339C13.57,5.481 13.339,5.499 13.166,5.38L12.955,5.241C12.75,5.113 12.538,4.998 12.319,4.896L12.289,4.883C12.07,4.784 11.966,4.531 12.052,4.306L12.431,3.321C12.493,3.175 12.641,3.092 12.791,3.104ZM7.527,3.264L7.918,4.276C7.997,4.482 7.899,4.714 7.698,4.798L7.476,4.897C7.331,4.966 7.189,5.041 7.05,5.122L6.845,5.247L6.754,5.308C6.582,5.425 6.349,5.386 6.225,5.219L5.593,4.366C5.479,4.195 5.498,3.974 5.628,3.825L5.663,3.789L5.705,3.755L5.718,3.745L5.958,3.592C6.202,3.443 6.454,3.308 6.714,3.188L6.976,3.072L7.035,3.049C7.232,2.973 7.451,3.069 7.527,3.264ZM10.392,2.519L10.653,2.541L10.726,2.55L10.8,2.565C11.012,2.627 11.152,2.836 11.121,3.062L10.992,4.002C10.961,4.224 10.776,4.387 10.558,4.395L10.492,4.392L10.222,4.37C10.073,4.361 9.924,4.358 9.775,4.361L9.551,4.37L9.507,4.373C9.267,4.39 9.056,4.219 9.024,3.981L8.889,2.969C8.873,2.787 8.978,2.623 9.137,2.555L9.199,2.535L9.265,2.524L9.609,2.504C9.87,2.496 10.132,2.5 10.392,2.519Z", true, 0f, false, false),
    )),
    AMBIENT_ANC(20.0f, listOf(
            IconLayer("M12.863,14.251C14.208,14.251 15.297,15.34 15.297,16.684L15.297,16.893C15.296,17.229 15.024,17.501 14.688,17.501H5.562C5.226,17.502 4.954,17.229 4.954,16.893C4.954,16.893 4.954,16.893 4.954,16.893L4.954,16.684C4.954,15.34 6.044,14.251 7.388,14.251H12.863ZM4.697,4.697C7.626,1.768 12.374,1.768 15.303,4.697C17.656,7.05 18.147,10.629 16.639,13.491C16.607,13.552 16.566,13.625 16.517,13.71L16.399,13.905C16.306,14.045 16.117,14.082 15.977,13.989L15.218,13.481C15.083,13.39 15.043,13.21 15.127,13.071C15.179,12.984 15.224,12.907 15.261,12.84L15.326,12.719C16.487,10.45 16.086,7.631 14.227,5.772C11.893,3.437 8.107,3.437 5.773,5.772C3.897,7.648 3.506,10.501 4.705,12.78C4.726,12.82 4.752,12.866 4.782,12.918L4.885,13.091C4.97,13.23 4.93,13.411 4.795,13.502L4.038,14.013L3.979,14.044C3.845,14.096 3.688,14.047 3.609,13.92C3.511,13.76 3.435,13.631 3.383,13.533C1.848,10.665 2.332,7.062 4.697,4.697ZM9.991,6.287C11.966,6.287 13.541,8.006 13.318,10.039C13.149,11.584 11.907,12.834 10.372,13.005C8.352,13.229 6.643,11.646 6.643,9.656C6.643,7.794 8.142,6.287 9.991,6.287Z", true, 0f, false, false),
    )),
    TIPS(48.0f, listOf(
            IconLayer("M24,2C36.1503,2 46,11.8497 46,24C46,36.1503 36.1503,46 24,46C11.8497,46 2,36.1503 2,24C2,11.8497 11.8497,2 24,2ZM24,5C13.5066,5 5,13.5066 5,24C5,34.4934 13.5066,43 24,43C34.4934,43 43,34.4934 43,24C43,13.5066 34.4934,5 24,5ZM24.8563,17.6309C25.16,17.6309 25.4063,17.8771 25.4063,18.1809L25.4063,35.45C25.4063,35.7538 25.16,36 24.8563,36L23.1437,36C22.84,36 22.5938,35.7538 22.5938,35.45L22.5938,18.1809C22.5938,17.8771 22.84,17.6309 23.1437,17.6309L24.8563,17.6309ZM24.95,12C25.2234,12 25.4502,12.1995 25.4928,12.4608L25.5,12.55L25.5,14.505C25.5,14.7784 25.3005,15.0052 25.0392,15.0478L24.95,15.055L23.05,15.055C22.7766,15.055 22.5498,14.8556 22.5072,14.5942L22.5,14.505L22.5,12.55C22.5,12.2766 22.6995,12.0498 22.9608,12.0072L23.05,12L24.95,12Z", true, 0f, false, false),
    )),
    BAROMETRIC(28.0f, listOf(
            IconLayer("M17.673,12.121C17.673,12.121 15.79,17.431 14.894,18.358C13.999,19.285 12.522,19.31 11.595,18.415C10.668,17.52 10.642,16.043 11.538,15.116C12.433,14.189 17.673,12.121 17.673,12.121Z", false, 1.75f, true, false),
            IconLayer("M22.662,23.829C24.879,21.612 26.25,18.549 26.25,15.167C26.25,8.401 20.765,2.917 14,2.917C7.234,2.917 1.75,8.401 1.75,15.167C1.75,18.549 3.121,21.612 5.338,23.829", false, 1.75f, true, false),
            IconLayer("M14,3.5V5.833", false, 1.75f, true, false),
            IconLayer("M22.66,7.666L20.846,9.135", false, 1.75f, true, false),
            IconLayer("M24.805,17.052L22.531,16.528", false, 1.75f, true, false),
            IconLayer("M3.195,17.052L5.469,16.528", false, 1.75f, true, false),
            IconLayer("M5.34,7.666L7.154,9.135", false, 1.75f, true, false),
    )),
    TAB_AMBIENT(24.0f, listOf(
            IconLayer("M12,3V21", false, 2.0f, true, false),
            IconLayer("M16.5,7V17", false, 2.0f, true, false),
            IconLayer("M3,10V14", false, 2.0f, true, false),
            IconLayer("M21,10V14", false, 2.0f, true, false),
            IconLayer("M7.5,7V17", false, 2.0f, true, false),
    )),
    TAB_EQ(24.0f, listOf(
            IconLayer("M5,4V8", false, 2.0f, true, false),
            IconLayer("M5,11V20", false, 2.0f, true, false),
            IconLayer("M3,8H7", false, 2.0f, true, false),
            IconLayer("M19,4V8", false, 2.0f, true, false),
            IconLayer("M19,11V20", false, 2.0f, true, false),
            IconLayer("M17,8H21", false, 2.0f, true, false),
            IconLayer("M12,20V16", false, 2.0f, true, false),
            IconLayer("M12,13V4", false, 2.0f, true, false),
            IconLayer("M14,16H10", false, 2.0f, true, false),
    )),
    TAB_CONTROLS(24.0f, listOf(
            IconLayer("M18.922,14.277L13.022,13.477V7.577C13.022,6.677 12.322,5.977 11.422,5.977C10.522,5.977 9.822,6.777 9.822,7.577V12.977V16.677L6.922,13.877C6.222,13.177 5.122,13.177 4.422,13.877C3.722,14.577 3.722,15.677 4.422,16.377L9.722,22.077H12.922H17.222L19.922,16.277C20.422,15.377 19.822,14.377 18.922,14.277Z", false, 2.0f, true, false),
            IconLayer("M6.947,8.549C6.847,8.149 6.847,7.749 6.847,7.349C6.847,4.849 8.847,2.849 11.347,2.849C13.847,2.849 15.947,4.849 15.947,7.349C15.947,7.749 15.947,8.149 15.847,8.449", false, 2.0f, true, false),
    )),
    RESET(20.0f, listOf(
            IconLayer("M4.697,15.303C6.054,16.66 7.929,17.5 10,17.5C14.142,17.5 17.5,14.142 17.5,10C17.5,5.858 14.142,2.5 10,2.5C7.929,2.5 6.054,3.339 4.697,4.697C4.006,5.388 2.5,7.083 2.5,7.083", false, 1.25f, true, false),
            IconLayer("M2.5,3.75V7.083H5.833", false, 1.25f, true, false),
    ));
}
 
// Turns SVG path data ("M12.8,14.2C14.2,14.2 ...") into an android.graphics.Path.
object SvgPath {
    private val token = Regex("[MmLlHhVvCcSsQqTtZzAa]|[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?")
 
    fun parse(data: String): Path {
        val path = Path()
        val t = token.findAll(data).map { it.value }.toList()
        var i = 0
        var cmd = ' '
        var last = ' '
        var cx = 0f
        var cy = 0f
        var sx = 0f
        var sy = 0f
        var px = 0f // the last control point, for S and T
        var py = 0f
        fun num(): Float = t[i++].toFloat()
        while (i < t.size) {
            val tk = t[i]
            if (tk[0].isLetter()) {
                cmd = tk[0]
                i++
                if (cmd == 'Z' || cmd == 'z') {
                    path.close()
                    cx = sx
                    cy = sy
                    last = cmd
                    continue
                }
            }
            val rel = cmd.isLowerCase()
            when (cmd.uppercaseChar()) {
                'M' -> {
                    val x = num() + (if (rel) cx else 0f)
                    val y = num() + (if (rel) cy else 0f)
                    path.moveTo(x, y)
                    cx = x
                    cy = y
                    sx = x
                    sy = y
                    cmd = if (rel) 'l' else 'L'
                }
                'L' -> {
                    val x = num() + (if (rel) cx else 0f)
                    val y = num() + (if (rel) cy else 0f)
                    path.lineTo(x, y)
                    cx = x
                    cy = y
                }
                'H' -> {
                    val x = num() + (if (rel) cx else 0f)
                    path.lineTo(x, cy)
                    cx = x
                }
                'V' -> {
                    val y = num() + (if (rel) cy else 0f)
                    path.lineTo(cx, y)
                    cy = y
                }
                'C' -> {
                    val ox = if (rel) cx else 0f
                    val oy = if (rel) cy else 0f
                    val x1 = num() + ox
                    val y1 = num() + oy
                    val x2 = num() + ox
                    val y2 = num() + oy
                    val x = num() + ox
                    val y = num() + oy
                    path.cubicTo(x1, y1, x2, y2, x, y)
                    px = x2
                    py = y2
                    cx = x
                    cy = y
                }
                'S' -> {
                    val ox = if (rel) cx else 0f
                    val oy = if (rel) cy else 0f
                    val smooth = last == 'C' || last == 'c' || last == 'S' || last == 's'
                    val x1 = if (smooth) 2 * cx - px else cx
                    val y1 = if (smooth) 2 * cy - py else cy
                    val x2 = num() + ox
                    val y2 = num() + oy
                    val x = num() + ox
                    val y = num() + oy
                    path.cubicTo(x1, y1, x2, y2, x, y)
                    px = x2
                    py = y2
                    cx = x
                    cy = y
                }
                'Q' -> {
                    val ox = if (rel) cx else 0f
                    val oy = if (rel) cy else 0f
                    val x1 = num() + ox
                    val y1 = num() + oy
                    val x = num() + ox
                    val y = num() + oy
                    path.quadTo(x1, y1, x, y)
                    px = x1
                    py = y1
                    cx = x
                    cy = y
                }
                'T' -> {
                    val smooth = last == 'Q' || last == 'q' || last == 'T' || last == 't'
                    val x1 = if (smooth) 2 * cx - px else cx
                    val y1 = if (smooth) 2 * cy - py else cy
                    val x = num() + (if (rel) cx else 0f)
                    val y = num() + (if (rel) cy else 0f)
                    path.quadTo(x1, y1, x, y)
                    px = x1
                    py = y1
                    cx = x
                    cy = y
                }
                'A' -> {
                    // Arcs are not used by the icons above; draw a straight line to the end point instead.
                    i += 5
                    val x = num() + (if (rel) cx else 0f)
                    val y = num() + (if (rel) cy else 0f)
                    path.lineTo(x, y)
                    cx = x
                    cy = y
                }
                else -> i++
            }
            last = cmd
        }
        return path
    }
}
 
// Draws one of the original icons in a given color. Works with setTint() like the other icon views.
class OfficialIconView(context: Context, private val icon: OfficialIcon, private var tint: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paths = icon.layers.map { SvgPath.parse(it.data) }
 
    fun setTint(color: Int) {
        tint = color
        invalidate()
    }
 
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val scale = min(width, height) / icon.viewport
        canvas.save()
        canvas.translate((width - icon.viewport * scale) / 2f, (height - icon.viewport * scale) / 2f)
        canvas.scale(scale, scale)
        icon.layers.forEachIndexed { index, layer ->
            val path = paths[index]
            path.fillType = if (layer.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
            paint.color = tint
            if (layer.fill) {
                paint.style = Paint.Style.FILL
                canvas.drawPath(path, paint)
            }
            if (layer.strokeWidth > 0f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = layer.strokeWidth
                paint.strokeCap = if (layer.round) Paint.Cap.ROUND else Paint.Cap.BUTT
                paint.strokeJoin = if (layer.round) Paint.Join.ROUND else Paint.Join.MITER
                canvas.drawPath(path, paint)
            }
        }
        canvas.restore()
    }
}
 
