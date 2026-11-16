package app.baleoqab

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View

object Ui {

    private fun px(v: View, dp: Int): Float = dp * v.resources.displayMetrics.density

    fun appBg(v: View) {
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF0B1220.toInt(), 0xFF0F1B33.toInt())
        )
        val glow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = px(v, 360)
            setGradientCenter(0.5f, 0f)
            colors = intArrayOf(0x6625437A, 0x0025437A)
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

    fun oval(v: View, c1: Int, c2: Int, strokeDp: Int, stroke: Int) {
        v.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(c1, c2)
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(px(v, strokeDp).toInt(), stroke)
        }
    }

    fun card(v: View) = rect(v, 0xCC16233A.toInt(), 18, 1, 0x2E78A0DC.toInt())

    fun cardSel(v: View) = gradient(v, 0xFF1B3A6E.toInt(), 0xFF16305A.toInt(), 18, 2, 0xFF3B82F6.toInt())

    fun badge(v: View) = gradient(v, 0xFF2F68C2.toInt(), 0xFF1B3A66.toInt(), 13)

    fun navBar(v: View) = rect(v, 0xD90F1A2E.toInt(), 22, 1, 0x2E78A0DC.toInt())

    fun navActive(v: View) = gradient(v, 0xFF2F68C2.toInt(), 0xFF1B3A66.toInt(), 16)

    fun powerOn(v: View) = oval(v, 0xFF34D87A.toInt(), 0xFF15803D.toInt(), 3, 0xFF86EFAC.toInt())

    fun powerOff(v: View) = oval(v, 0xFF2E3F5E.toInt(), 0xFF1B2840.toInt(), 3, 0xFF3B4F73.toInt())

    fun powerWait(v: View) = oval(v, 0xFFFBBF24.toInt(), 0xFFB45309.toInt(), 3, 0xFFFDE68A.toInt())

    fun ringDash(v: View) {
        v.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0)
            setStroke(px(v, 3).toInt(), 0xB386EFAC.toInt(), px(v, 2), px(v, 8))
        }
    }
}
