package app.baleoqab

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin

/** دایره‌ی اتصال: حلقه، نقطه‌های گنبدی و موج‌هایی که با سرعت ترافیک بالا و پایین میرن. */
class OrbView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val OFF = 0
        const val WAIT = 1
        const val ON = 2
        const val ERR = 3
    }

    private var state = OFF
    private var target = 0f
    private var level = 0f
    private val d = resources.displayMetrics.density

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * d
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 16f * d
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF030803.toInt()
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.4f * d
    }
    private val path = Path()
    private val clip = Path()
    private var dots: Array<FloatArray> = emptyArray()
    private var dotsFor = 0f

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            level += (target - level) * 0.06f
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    fun setState(s: Int) {
        state = s
        if (s != ON) target = 0f
        invalidate()
    }

    /** سرعت لحظه‌ای (بایت بر ثانیه) رو به ارتفاع موج تبدیل میکنه. */
    fun setActivity(bytesPerSec: Long) {
        target = if (state == ON) {
            (log10(1.0 + bytesPerSec / 1024.0) / 3.0).toFloat().coerceIn(0f, 1f)
        } else 0f
    }

    private fun accent(): Int = when (state) {
        ON -> 0xFF3DFF1A.toInt()
        WAIT -> 0xFFFBBF24.toInt()
        ERR -> 0xFFEF4444.toInt()
        else -> 0xFF5A605A.toInt()
    }

    private fun withAlpha(color: Int, a: Int): Int =
        (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    private fun buildDots(r: Float) {
        dotsFor = r
        val rings = 6
        dots = Array(rings) { k ->
            val rad = r * (1.16f + k * 0.055f)
            val gap = (9f + k) * d
            val n = (2.0 * PI * rad / gap).toInt().coerceAtLeast(12)
            val arr = FloatArray(n * 2)
            for (i in 0 until n) {
                val a = 2.0 * PI * (i + 0.5 * (k % 2)) / n
                arr[i * 2] = (rad * cos(a)).toFloat()
                arr[i * 2 + 1] = (rad * sin(a)).toFloat()
            }
            arr
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) * 0.32f
        val col = accent()
        val moving = state == ON || state == WAIT
        val t = if (moving) (SystemClock.uptimeMillis() % 60000L) / 1000f else 0f

        if (dotsFor != r) buildDots(r)

        // نقطه‌های گنبدی دور دایره
        c.save()
        c.translate(cx, cy)
        c.rotate(t * 6f)
        for (k in dots.indices) {
            val a = if (state == OFF) 45 else (160 - k * 22)
            dotPaint.color = withAlpha(col, a.coerceAtLeast(30))
            c.drawPoints(dots[k], dotPaint)
        }
        c.restore()

        // داخل دایره
        c.drawCircle(cx, cy, r, fillPaint)
        clip.reset()
        clip.addCircle(cx, cy, r - 3f * d, Path.Direction.CW)
        c.save()
        c.clipPath(clip)

        val amp = (4f + 26f * level) * d
        val base = cy + r * 0.38f
        val step = 6f * d
        for (l in 0 until 3) {
            val freq = 1.1f + 0.45f * l
            val speed = 0.5f + 0.25f * l
            val a = amp * (1f - 0.28f * l)
            path.reset()
            var x = cx - r
            path.moveTo(x, cy + r)
            while (x <= cx + r + step) {
                val u = (x - (cx - r)) / (2f * r)
                val y = base - l * 5f * d + a * sin(2.0 * PI * (u * freq + t * speed)).toFloat()
                path.lineTo(x, y)
                x += step
            }
            path.lineTo(cx + r + step, cy + r)
            path.close()
            wavePaint.color = withAlpha(col, if (state == OFF) 28 else 70 + 35 * l)
            c.drawPath(path, wavePaint)
        }
        c.restore()

        // درخشش و حلقه
        glowPaint.color = withAlpha(col, if (state == OFF) 0 else 55)
        c.drawCircle(cx, cy, r - 6f * d, glowPaint)
        ringPaint.color = col
        c.drawCircle(cx, cy, r, ringPaint)
    }
}
