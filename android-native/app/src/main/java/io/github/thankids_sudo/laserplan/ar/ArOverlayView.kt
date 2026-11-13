package io.github.thankids_sudo.laserplan.ar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View
import java.util.Locale

/** What the overlay draws for one frame: projected corners, wall lengths and the centre reticle. */
data class OverlayState(
    val corners: List<FloatArray?> = emptyList(),     // screen x,y per corner, null when behind the camera
    val wallLabels: List<String> = emptyList(),       // length of wall i (corner i → i+1)
    val reticleValid: Boolean = false,
    val liveLength: Double? = null,                   // last corner → reticle, metres
    val heightMode: Boolean = false,
)

/** Draws the reticle, numbered corners and the outline over the camera image. UI thread. */
class ArOverlayView(context: Context) : View(context) {
    @Volatile var state = OverlayState()
        set(v) { field = v; postInvalidateOnAnimation() }

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3DDC84"); strokeWidth = 3 * d; style = Paint.Style.STROKE }
    private val closing = Paint(line).apply { pathEffect = DashPathEffect(floatArrayOf(10 * d, 8 * d), 0f); alpha = 170 }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3DDC84"); style = Paint.Style.FILL }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15 * d; isFakeBoldText = true; setShadowLayer(4 * d, 0f, 0f, Color.BLACK); textAlign = Paint.Align.CENTER }
    private val ret = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3 * d; style = Paint.Style.STROKE }

    override fun onDraw(c: Canvas) {
        val s = state
        val pts = s.corners
        for (i in pts.indices) {
            val a = pts[i] ?: continue
            val last = i == pts.size - 1
            val b = if (last) pts[0] else pts[i + 1]
            if (b != null && (pts.size >= 3 || !last)) {
                c.drawLine(a[0], a[1], b[0], b[1], if (last) closing else line)
                s.wallLabels.getOrNull(i)?.let { if (!last || pts.size >= 3) c.drawText(it, (a[0] + b[0]) / 2, (a[1] + b[1]) / 2 - 8 * d, label) }
            }
        }
        pts.forEachIndexed { i, p -> if (p != null) { c.drawCircle(p[0], p[1], 7 * d, dot); c.drawText("C${i + 1}", p[0], p[1] - 14 * d, label) } }
        val cx = width / 2f; val cy = height / 2f
        ret.color = if (s.reticleValid) Color.parseColor("#3DDC84") else Color.parseColor("#F5B841")
        c.drawCircle(cx, cy, 18 * d, ret)
        c.drawLine(cx - 28 * d, cy, cx - 10 * d, cy, ret); c.drawLine(cx + 10 * d, cy, cx + 28 * d, cy, ret)
        c.drawLine(cx, cy - 28 * d, cx, cy - 10 * d, ret); c.drawLine(cx, cy + 10 * d, cx, cy + 28 * d, ret)
        s.liveLength?.let { c.drawText(String.format(Locale.US, "%.2f m", it), cx, cy + 50 * d, label) }
    }
}
