package app.baleoqab

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View

object Ui {
    val BG = 0xFF050805.toInt()
    val GREEN = 0xFF3DFF1A.toInt()
    val GREEN_DARK = 0xFF1B5E12.toInt()
    val AMBER = 0xFFFBBF24.toInt()
    val RED = 0xFFEF4444.toInt()
    val MUTED = 0xFF8A938A.toInt()
    val WHITE = 0xFFFFFFFF.toInt()

    private fun px(v: View, dp: Int): Float = dp * v.resources.displayMetrics.density

    fun appBg(v: View) {
        val base = GradientDrawable().apply { setColor(BG) }
        val glow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = px(v, 420)
            setGradientCenter(0.5f, 0.38f)
            colors = intArrayOf(0x2A1FB80F, 0x00000000)
        }
        v.background = LayerDrawable(arrayOf(base, glow))
    }

    fun rect(v: View, fill: Int, radiusDp: Int, strokeDp: Int = 0, stroke: Int = 0) {
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = px(v, radiusDp)
            if (strokeDp > 0) setStroke(px(v, strokeDp).toInt(), stroke)
        }
    }

    fun gradient(v: View, c1: Int, c2: Int, radiusDp: Int, strokeDp: Int = 0, stroke: Int = 0) {
        v.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(c1, c2)
        ).apply {
            cornerRadius = px(v, radiusDp)
            if (strokeDp > 0) setStroke(px(v, strokeDp).toInt(), stroke)
        }
    }

    fun card(v: View) = rect(v, 0xFF0E120E.toInt(), 18, 1, 0xFF232A23.toInt())

    fun cardSel(v: View) = gradient(v, 0xFF14301A.toInt(), 0xFF0E2212.toInt(), 18, 2, GREEN)

    fun badge(v: View) = gradient(v, 0xFF1F7A12.toInt(), 0xFF0F3D0A.toInt(), 13)

    fun iconBtn(v: View) = gradient(v, 0xFF1E5A14.toInt(), 0xFF0B260A.toInt(), 12, 1, 0xFF2D7A1F.toInt())

    fun pill(v: View, color: Int) = gradient(v, withA(color, 0x55), withA(color, 0x22), 28, 1, withA(color, 0x99))

    fun tile(v: View, selected: Boolean) {
        if (selected) gradient(v, 0xFF14301A.toInt(), 0xFF0B1A0E.toInt(), 22, 2, GREEN)
        else rect(v, 0xFF0E120E.toInt(), 22, 1, 0xFF2A312A.toInt())
    }

    fun input(v: View) = rect(v, 0xFF121712.toInt(), 12, 1, 0xFF283028.toInt())

    fun wide(v: View) = gradient(v, 0xFF1E2A1E.toInt(), 0xFF141B14.toInt(), 28, 1, 0xFF2D3A2D.toInt())

    private fun withA(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a shl 24)
}
