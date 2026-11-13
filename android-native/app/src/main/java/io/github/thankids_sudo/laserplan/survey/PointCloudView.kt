package io.github.thankids_sudo.laserplan.survey

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sparse point-cloud viewer: one-finger drag rotates, pinch zooms, double tap resets.
 * Perspective projection about the cloud's centre, points coloured by height, XYZ axis gizmo.
 * It shows measured points only – it is not a mesh or a model.
 */
class PointCloudView(context: Context) : View(context) {
    private var pts = FloatArray(0)          // x,y,z,c
    private var cx = 0f; private var cy = 0f; private var cz = 0f
    private var radius = 1f
    private var minY = 0f; private var maxY = 1f
    private var yaw = 0.6f
    private var pitch = 0.45f
    private var zoom = 1f
    private var lastX = 0f; private var lastY = 0f
    private val d = resources.displayMetrics.density

    private val bands = 8
    private val paints = Array(bands) { i ->
        Paint().apply {
            val t = i / (bands - 1f)
            color = Color.HSVToColor(floatArrayOf(240f - 240f * t, 0.75f, 1f))
            strokeWidth = 2.2f * d; strokeCap = Paint.Cap.ROUND; isAntiAlias = false
        }
    }
    private val buffers = Array(bands) { FloatArray(0) }
    private val counts = IntArray(bands)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14 * d }
    private val axis = arrayOf(
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF5252"); strokeWidth = 3 * d },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3DDC84"); strokeWidth = 3 * d },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#448AFF"); strokeWidth = 3 * d },
    )

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            zoom = (zoom * det.scaleFactor).coerceIn(0.2f, 20f); invalidate(); return true
        }
    })
    private var lastTap = 0L

    init { setBackgroundColor(Color.parseColor("#0B1220")) }

    fun setPoints(xyzc: FloatArray) {
        pts = xyzc
        val n = pts.size / 4
        if (n > 0) {
            var sx = 0.0; var sy = 0.0; var sz = 0.0
            minY = Float.MAX_VALUE; maxY = -Float.MAX_VALUE
            for (i in 0 until n) { sx += pts[i * 4]; sy += pts[i * 4 + 1]; sz += pts[i * 4 + 2]; minY = min(minY, pts[i * 4 + 1]); maxY = max(maxY, pts[i * 4 + 1]) }
            cx = (sx / n).toFloat(); cy = (sy / n).toFloat(); cz = (sz / n).toFloat()
            var r = 0f
            for (i in 0 until n) { val dx = pts[i * 4] - cx; val dy = pts[i * 4 + 1] - cy; val dz = pts[i * 4 + 2] - cz; r = max(r, sqrt(dx * dx + dy * dy + dz * dz)) }
            radius = max(r, 0.1f)
        }
        for (b in 0 until bands) buffers[b] = FloatArray(n * 2)
        resetView()
    }

    fun resetView() { yaw = 0.6f; pitch = 0.45f; zoom = 1f; invalidate() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y
                if (e.eventTime - lastTap < 300) resetView()
                lastTap = e.eventTime
            }
            MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && !scaler.isInProgress) {
                yaw += (e.x - lastX) / (150 * d)
                pitch = (pitch + (e.y - lastY) / (150 * d)).coerceIn(-1.5f, 1.5f)
                lastX = e.x; lastY = e.y; invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> { lastX = e.x; lastY = e.y }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val n = pts.size / 4
        if (n == 0) {
            text.textAlign = Paint.Align.CENTER
            c.drawText("No 3D points yet.", width / 2f, height / 2f - 10 * d, text)
            c.drawText("Record a survey with depth, then stop it.", width / 2f, height / 2f + 14 * d, text)
            return
        }
        val cyw = cos(yaw); val syw = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        val f = min(width, height) * 0.9f * zoom
        val camDist = radius * 2.6f
        val w2 = width / 2f; val h2 = height / 2f
        counts.fill(0)
        val span = max(maxY - minY, 1e-3f)
        for (i in 0 until n) {
            val x = pts[i * 4] - cx; val y = pts[i * 4 + 1] - cy; val z = pts[i * 4 + 2] - cz
            val x1 = cyw * x + syw * z; val z1 = -syw * x + cyw * z          // yaw about Y
            val y2 = cp * y - sp * z1; val z2 = sp * y + cp * z1              // pitch about X
            val depth = camDist - z2
            if (depth <= 0.05f) continue
            val sx = w2 + f * x1 / depth / radius * 0.5f
            val sy = h2 - f * y2 / depth / radius * 0.5f
            val b = (((pts[i * 4 + 1] - minY) / span) * (bands - 1)).toInt().coerceIn(0, bands - 1)
            val buf = buffers[b]; val k = counts[b]
            buf[k] = sx; buf[k + 1] = sy; counts[b] = k + 2
        }
        for (b in 0 until bands) if (counts[b] > 0) c.drawPoints(buffers[b], 0, counts[b], paints[b])

        // Axis gizmo (world X red, Y up green, Z blue), bottom-left.
        val ox = 56 * d; val oy = height - 56 * d; val L = 36 * d
        val axes = arrayOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f))
        val names = arrayOf("X", "Y", "Z")
        text.textAlign = Paint.Align.CENTER
        for (a in 0..2) {
            val (x, y, z) = Triple(axes[a][0], axes[a][1], axes[a][2])
            val x1 = cyw * x + syw * z; val z1 = -syw * x + cyw * z
            val y2 = cp * y - sp * z1
            c.drawLine(ox, oy, ox + x1 * L, oy - y2 * L, axis[a])
            c.drawText(names[a], ox + x1 * (L + 10 * d), oy - y2 * (L + 10 * d) + 5 * d, text)
        }
        text.textAlign = Paint.Align.LEFT
        c.drawText("$n points · ${"%.1f".format(radius * 2)} m across · drag to rotate, pinch to zoom, double-tap to reset", 12 * d, 22 * d, text)
    }
}
