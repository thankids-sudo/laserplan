package io.github.thankids_sudo.laserplan.survey

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * OpenGL point-cloud viewer: round points in photo colour (or height colour), perspective camera orbiting the cloud's
 * centre, depth test, XYZ axis lines. One-finger drag rotates, pinch zooms, double tap resets.
 * It shows measured points only – it is not a mesh or a model.
 */
class PointCloudView(context: Context) : GLSurfaceView(context) {
    enum class ColourMode { PHOTO, HEIGHT }

    private val d = resources.displayMetrics.density
    private val renderer = CloudRenderer()
    private var lastX = 0f; private var lastY = 0f; private var lastTap = 0L
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            renderer.zoom = (renderer.zoom * det.scaleFactor).coerceIn(0.2f, 25f); requestRender(); return true
        }
    })

    /** Called on the UI thread with a summary line (point count, size, colour coverage) once data is loaded. */
    var onSummary: ((String) -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** [xyzc] packed x,y,z,confidence; [rgb] 0xRRGGBB per point or −1 (no photo colour), or null for older scans. */
    fun setPoints(xyzc: FloatArray, rgb: IntArray?) {
        val n = xyzc.size / 4
        var sx = 0.0; var sy = 0.0; var sz = 0.0; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until n) { sx += xyzc[i * 4]; sy += xyzc[i * 4 + 1]; sz += xyzc[i * 4 + 2]; minY = min(minY, xyzc[i * 4 + 1]); maxY = max(maxY, xyzc[i * 4 + 1]) }
        val cx = if (n > 0) (sx / n).toFloat() else 0f; val cy = if (n > 0) (sy / n).toFloat() else 0f; val cz = if (n > 0) (sz / n).toFloat() else 0f
        var r = 0.1f
        for (i in 0 until n) { val dx = xyzc[i * 4] - cx; val dy = xyzc[i * 4 + 1] - cy; val dz = xyzc[i * 4 + 2] - cz; r = max(r, sqrt(dx * dx + dy * dy + dz * dz)) }
        val photo = FloatArray(n * 6); val height = FloatArray(n * 6)
        val span = max(maxY - minY, 1e-3f)
        var coloured = 0
        val hsv = FloatArray(3)
        for (i in 0 until n) {
            val x = xyzc[i * 4] - cx; val y = xyzc[i * 4 + 1] - cy; val z = xyzc[i * 4 + 2] - cz
            hsv[0] = 240f - 240f * ((xyzc[i * 4 + 1] - minY) / span); hsv[1] = 0.75f; hsv[2] = 1f
            val hc = Color.HSVToColor(hsv)
            val c = rgb?.get(i)?.takeIf { it != VoxelGrid.NO_COLOUR }?.also { coloured++ } ?: 0x9A9A9A
            for ((buf, col) in listOf(photo to c, height to hc)) {
                buf[i * 6] = x; buf[i * 6 + 1] = y; buf[i * 6 + 2] = z
                buf[i * 6 + 3] = ((col shr 16) and 0xFF) / 255f; buf[i * 6 + 4] = ((col shr 8) and 0xFF) / 255f; buf[i * 6 + 5] = (col and 0xFF) / 255f
            }
        }
        // Walls-only subset: drop the floor and ceiling layers (2nd–98th height percentile minus a margin).
        val band = WallBand.of(FloatArray(n) { xyzc[it * 4 + 1] })
        val sel = if (band == null) IntArray(0) else (0 until n).filter { val y = xyzc[it * 4 + 1]; y >= band.first && y <= band.second }.toIntArray()
        val photoW = FloatArray(sel.size * 6); val heightW = FloatArray(sel.size * 6)
        sel.forEachIndexed { j, i -> System.arraycopy(photo, i * 6, photoW, j * 6, 6); System.arraycopy(height, i * 6, heightW, j * 6, 6) }
        hasWallBand = sel.isNotEmpty()
        queueEvent { renderer.load(photo, height, n, r); renderer.loadWalls(photoW, heightW, sel.size) }
        if (rgb == null || coloured == 0) setMode(ColourMode.HEIGHT)
        val pct = if (n > 0) coloured * 100 / n else 0
        post {
            onSummary?.invoke(if (n == 0) "No 3D points in this scan yet."
            else "$n points · ${"%.1f".format(r * 2)} m across · ${if (rgb == null) "older scan, no photo colour" else "$pct% photo-coloured"}")
        }
        requestRender()
    }

    fun setMode(m: ColourMode) { renderer.mode = m; requestRender() }
    /** True when the cloud has a recognisable floor-to-ceiling range, so "walls only" can be shown. */
    @Volatile var hasWallBand = false
        private set
    var wallsOnly: Boolean
        get() = renderer.wallsOnly
        set(v) { renderer.wallsOnly = v && hasWallBand; requestRender() }
    /** Looks straight down (plan view); with walls only this reads like a floor plan of the scan. */
    fun topView() { renderer.yaw = 0f; renderer.pitch = 1.5f; requestRender() }
    val mode get() = renderer.mode
    fun pointSize(delta: Float) { renderer.pointSize = (renderer.pointSize + delta).coerceIn(1f, 14f); requestRender() }
    fun resetView() { renderer.yaw = 0.6f; renderer.pitch = 0.45f; renderer.zoom = 1f; requestRender() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; if (e.eventTime - lastTap < 300) resetView(); lastTap = e.eventTime }
            MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && !scaler.isInProgress) {
                renderer.yaw += (e.x - lastX) / (150 * d)
                renderer.pitch = (renderer.pitch + (e.y - lastY) / (150 * d)).coerceIn(-1.5f, 1.5f)
                lastX = e.x; lastY = e.y; requestRender()
            }
            MotionEvent.ACTION_POINTER_UP -> { lastX = e.x; lastY = e.y }
        }
        return true
    }

    private inner class CloudRenderer : Renderer {
        @Volatile var yaw = 0.6f
        @Volatile var pitch = 0.45f
        @Volatile var zoom = 1f
        @Volatile var mode = ColourMode.PHOTO
        @Volatile var wallsOnly = false
        private var photoW: FloatBuffer? = null
        private var heightW: FloatBuffer? = null
        private var countW = 0
        fun loadWalls(p: FloatArray, h: FloatArray, n: Int) { photoW = buf(p); heightW = buf(h); countW = n }
        @Volatile var pointSize = 5f * d / 2.6f
        private var photo: FloatBuffer? = null
        private var height: FloatBuffer? = null
        private var count = 0
        private var radius = 1f
        private var prog = 0; private var aPos = 0; private var aCol = 0; private var uMvp = 0; private var uSize = 0; private var uRound = 0
        private val axes = buf(floatArrayOf(
            0f, 0f, 0f, 1f, 0.32f, 0.32f, 1f, 0f, 0f, 1f, 0.32f, 0.32f,
            0f, 0f, 0f, 0.24f, 0.86f, 0.52f, 0f, 1f, 0f, 0.24f, 0.86f, 0.52f,
            0f, 0f, 0f, 0.27f, 0.54f, 1f, 0f, 0f, 1f, 0.27f, 0.54f, 1f,
        ))
        private val proj = FloatArray(16); private val view = FloatArray(16); private val mvp = FloatArray(16); private val model = FloatArray(16)
        private var aspect = 1f

        fun load(p: FloatArray, h: FloatArray, n: Int, r: Float) { photo = buf(p); height = buf(h); count = n; radius = r }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.043f, 0.071f, 0.125f, 1f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            prog = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, VS)); GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, FS))
                GLES20.glLinkProgram(it)
            }
            aPos = GLES20.glGetAttribLocation(prog, "aPos"); aCol = GLES20.glGetAttribLocation(prog, "aCol")
            uMvp = GLES20.glGetUniformLocation(prog, "uMvp"); uSize = GLES20.glGetUniformLocation(prog, "uSize"); uRound = GLES20.glGetUniformLocation(prog, "uRound")
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { GLES20.glViewport(0, 0, w, h); aspect = w.toFloat() / max(h, 1) }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val dist = radius * 2.8f / zoom
            Matrix.perspectiveM(proj, 0, 50f, aspect, max(0.01f, dist - radius * 3), dist + radius * 3)
            Matrix.setLookAtM(view, 0, 0f, 0f, dist, 0f, 0f, 0f, 0f, 1f, 0f)
            Matrix.setIdentityM(model, 0)
            Matrix.rotateM(model, 0, Math.toDegrees(pitch.toDouble()).toFloat(), 1f, 0f, 0f)
            Matrix.rotateM(model, 0, Math.toDegrees(yaw.toDouble()).toFloat(), 0f, 1f, 0f)
            Matrix.multiplyMM(mvp, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, proj, 0, mvp, 0)
            GLES20.glUseProgram(prog)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            val walls = wallsOnly && countW > 0
            val b = if (walls) (if (mode == ColourMode.PHOTO) photoW else heightW) else (if (mode == ColourMode.PHOTO) photo else height)
            val nDraw = if (walls) countW else count
            if (b != null && nDraw > 0) {
                GLES20.glUniform1f(uSize, pointSize * dist); GLES20.glUniform1f(uRound, 1f)
                draw(b, GLES20.GL_POINTS, nDraw)
            }
            // Axis lines through the cloud centre, a fifth of the cloud's size: X red, Y (up) green, Z blue.
            val s = radius * 0.4f
            val a = FloatArray(36); axes.position(0); axes.get(a); axes.position(0)
            for (i in 0 until 6) { a[i * 6] *= s; a[i * 6 + 1] *= s; a[i * 6 + 2] *= s }
            GLES20.glUniform1f(uSize, 1f); GLES20.glUniform1f(uRound, 0f)
            GLES20.glLineWidth(3f)
            draw(buf(a), GLES20.GL_LINES, 6)
        }

        private fun draw(b: FloatBuffer, primitive: Int, n: Int) {
            b.position(0); GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, b)
            b.position(3); GLES20.glVertexAttribPointer(aCol, 3, GLES20.GL_FLOAT, false, 24, b)
            GLES20.glEnableVertexAttribArray(aPos); GLES20.glEnableVertexAttribArray(aCol)
            GLES20.glDrawArrays(primitive, 0, n)
            GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aCol)
            b.position(0)
        }

        private fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
    }

    private companion object {
        fun buf(a: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }
        // Point size shrinks with distance; points are drawn as soft-edged discs.
        const val VS = "uniform mat4 uMvp; uniform float uSize; attribute vec3 aPos; attribute vec3 aCol; varying vec3 vCol;\n" +
            "void main(){ gl_Position = uMvp * vec4(aPos, 1.0); vCol = aCol; gl_PointSize = clamp(uSize / max(gl_Position.w, 0.05), 1.0, 24.0); }"
        const val FS = "precision mediump float; varying vec3 vCol; uniform float uRound;\n" +
            "void main(){ if (uRound > 0.5) { vec2 p = gl_PointCoord - vec2(0.5); float r = dot(p, p); if (r > 0.25) discard; gl_FragColor = vec4(vCol * (1.0 - r * 1.2), 1.0); } else { gl_FragColor = vec4(vCol, 1.0); } }"
    }
}
