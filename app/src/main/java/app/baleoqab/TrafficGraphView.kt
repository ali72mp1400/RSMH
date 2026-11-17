package app.baleoqab

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

class TrafficGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val n = 60
    private val down = FloatArray(n)
    private val up = FloatArray(n)
    private val dp = resources.displayMetrics.density

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33789CDC
        strokeWidth = dp
    }

    private val downLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF22C55E.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2 * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val upLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF60A5FA.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2 * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            0x6622C55E, 0x0022C55E,
            Shader.TileMode.CLAMP
        )
    }

    /** سرعت دانلود و آپلود (بایت بر ثانیه) رو اضافه میکنه. */
    fun push(d: Float, u: Float) {
        System.arraycopy(down, 1, down, 0, n - 1)
        System.arraycopy(up, 1, up, 0, n - 1)
        down[n - 1] = d
        up[n - 1] = u
        invalidate()
    }

    fun clear() {
        down.fill(0f)
        up.fill(0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawLine(0f, h - 1f, w, h - 1f, gridPaint)
        canvas.drawLine(0f, h / 2f, w, h / 2f, gridPaint)

        val maxV = maxOf(down.maxOrNull() ?: 0f, up.maxOrNull() ?: 0f, 50_000f)

        val dPath = buildPath(down, w, h, maxV)
        val fill = Path(dPath)
        fill.lineTo(w, h)
        fill.lineTo(0f, h)
        fill.close()
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(dPath, downLine)
        canvas.drawPath(buildPath(up, w, h, maxV), upLine)
    }

    private fun buildPath(data: FloatArray, w: Float, h: Float, maxV: Float): Path {
        val p = Path()
        val step = w / (n - 1)
        var px = 0f
        var py = yOf(data[0], h, maxV)
        p.moveTo(px, py)
        for (i in 1 until n) {
            val x = i * step
            val y = yOf(data[i], h, maxV)
            val mx = (px + x) / 2f
            p.cubicTo(mx, py, mx, y, x, y)
            px = x
            py = y
        }
        return p
    }

    private fun yOf(v: Float, h: Float, maxV: Float): Float =
        h - 4 * dp - (v / maxV) * (h - 8 * dp)
}
