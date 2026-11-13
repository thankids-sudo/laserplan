package io.github.thankids_sudo.laserplan.ar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import io.github.thankids_sudo.laserplan.geometry.ArRoomPayload
import io.github.thankids_sudo.laserplan.geometry.Corner3
import io.github.thankids_sudo.laserplan.geometry.P2
import io.github.thankids_sudo.laserplan.geometry.RoomGeometry
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Native ARCore room capture: find the floor, aim the centre reticle at each floor corner and tap Add corner,
 * optionally aim at the ceiling for the height, then Finish. Returns a "laserplan.ar-room" v1 JSON payload.
 * AR lengths are estimates; the drawing marks them for laser confirmation.
 */
@SuppressLint("SetTextI18n")
class ArRoomActivity : AppCompatActivity(), GLSurfaceView.Renderer {
    private class CapturedCorner(val anchor: Anchor, val source: String, val timeMs: Long)

    private lateinit var surface: GLSurfaceView
    private lateinit var overlay: ArOverlayView
    private lateinit var status: TextView
    private lateinit var floorBar: LinearLayout
    private lateinit var heightBar: LinearLayout

    private var session: Session? = null
    private var installRequested = false
    private val bg = BackgroundRenderer()
    private var textureSet = false
    @Volatile private var geometryDirty = true
    @Volatile private var viewW = 1
    @Volatile private var viewH = 1

    // Owned by the GL thread.
    private val corners = mutableListOf<CapturedCorner>()
    private var floorY: Float? = null
    private var ceilingY: Float? = null
    private var heightSource: String? = null
    private var manualHeight: Double? = null
    @Volatile private var heightMode = false
    private var depthUsed = false
    private var trackingLosses = 0
    private var wasTracking = false
    private var lastStatus = ""
    private val startedAt = System.currentTimeMillis()
    private val actions = ConcurrentLinkedQueue<(Frame) -> Unit>()
    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val vp = FloatArray(16)

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) fail("Camera permission is needed for AR capture. You can still draw the room by hand or use the laser.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val d = resources.displayMetrics.density
        surface = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@ArRoomActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        overlay = ArOverlayView(this)
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f; setBackgroundColor(Color.parseColor("#AA0B1220"))
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            text = "Starting AR…"
        }
        floorBar = bar(
            "Add corner" to { addCorner() }, "Undo" to { undo() }, "Delete point" to { deleteNearCentre() },
            "Height" to { setHeightMode(true) }, "Finish" to { finishRoom() }, "Cancel" to { cancel() },
        )
        heightBar = bar(
            "Set height" to { setHeightFromCeiling() }, "Type height" to { typeHeight() }, "Back" to { setHeightMode(false) },
        ).apply { visibility = View.GONE }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(floorBar); addView(heightBar) }
        setContentView(FrameLayout(this).apply {
            addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        })
        onBackPressedDispatcher.addCallback(this) { cancel() }
    }

    private fun bar(vararg items: Pair<String, () -> Any?>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(Color.parseColor("#CC0B1220"))
        items.forEach { (label, fn) ->
            addView(Button(this@ArRoomActivity).apply { text = label; isAllCaps = false; textSize = 12f; setOnClickListener { fn() } },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA); return
        }
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return }
                    else -> {}
                }
                val s = Session(this)
                val cfg = Config(s).apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                    focusMode = Config.FocusMode.AUTO
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) { depthMode = Config.DepthMode.AUTOMATIC; depthUsed = true }
                }
                s.configure(cfg)
                session = s
            } catch (e: UnavailableArcoreNotInstalledException) { fail("Google Play Services for AR is not installed."); return }
            catch (e: UnavailableUserDeclinedInstallationException) { fail("Google Play Services for AR is needed for AR capture."); return }
            catch (e: UnavailableApkTooOldException) { fail("Please update Google Play Services for AR."); return }
            catch (e: UnavailableSdkTooOldException) { fail("Please update LaserPlan."); return }
            catch (e: UnavailableDeviceNotCompatibleException) { fail("This phone does not support ARCore. Draw the room by hand or use the laser."); return }
            catch (e: Exception) { fail("AR could not start: ${e.message}"); return }
        }
        try { session?.resume() } catch (e: CameraNotAvailableException) { fail("The camera is in use by another app."); return }
        surface.onResume()
        geometryDirty = true
    }

    override fun onPause() {
        super.onPause()
        surface.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    // ---------------- GL thread ----------------
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        bg.create()
        textureSet = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewW = width; viewH = height; geometryDirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (!textureSet) { s.setCameraTextureName(bg.textureId); textureSet = true }
        if (geometryDirty) {
            @Suppress("DEPRECATION") val rotation = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
            s.setDisplayGeometry(rotation, viewW, viewH)
            geometryDirty = false
        }
        val frame = try { s.update() } catch (_: Exception) { return }
        bg.draw(frame)
        val cam = frame.camera
        while (true) { val a = actions.poll() ?: break; a(frame) }

        if (cam.trackingState != TrackingState.TRACKING) {
            if (wasTracking) trackingLosses++
            wasTracking = false
            say(trackingMessage(cam.trackingFailureReason))
            overlay.state = OverlayState(heightMode = heightMode)
            return
        }
        wasTracking = true
        val hit = pickHit(frame)
        val floorSeen = s.getAllTrackables(Plane::class.java).any { it.trackingState == TrackingState.TRACKING && it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
        say(when {
            heightMode && hit != null -> "Ceiling found – tap Set height."
            heightMode -> "Aim the centre at the ceiling. If it is plain and not found, tap Type height."
            !floorSeen -> "Move the phone slowly over the floor until the floor is found."
            hit == null -> "Aim the centre dot at the floor${if (corners.isEmpty()) "" else " at the next corner"}."
            corners.isEmpty() -> "Aim at the first floor corner and tap Add corner."
            else -> "${corners.size} corner${if (corners.size == 1) "" else "s"}. Aim at the next corner (clockwise), or Finish."
        })

        cam.getViewMatrix(view, 0)
        cam.getProjectionMatrix(proj, 0, 0.05f, 100f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        val poses = corners.map { it.anchor.pose }
        val plan = poses.map { P2(it.tx().toDouble(), it.tz().toDouble()) }
        val lengths = RoomGeometry.wallLengths(plan).map { String.format(Locale.US, "%.2f m", it) }
        val live = if (!heightMode && hit != null && poses.isNotEmpty()) {
            val p = hit.first.hitPose; val l = poses.last()
            hypot((p.tx() - l.tx()).toDouble(), (p.tz() - l.tz()).toDouble())
        } else if (heightMode && hit != null && floorY != null) (hit.first.hitPose.ty() - floorY!!).toDouble() else null
        overlay.state = OverlayState(poses.map { project(it.tx(), it.ty(), it.tz()) }, lengths, hit != null, live, heightMode)
    }

    private fun project(x: Float, y: Float, z: Float): FloatArray? {
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, vp, 0, floatArrayOf(x, y, z, 1f), 0)
        if (out[3] <= 0f) return null
        return floatArrayOf((out[0] / out[3] + 1f) / 2f * viewW, (1f - out[1] / out[3]) / 2f * viewH)
    }

    /** Evidence-based hit at the screen centre: floor plane (or depth on the same floor height), or ceiling in height mode. */
    private fun pickHit(frame: Frame): Pair<HitResult, String>? {
        for (h in frame.hitTest(viewW / 2f, viewH / 2f)) {
            val t = h.trackable
            val p = h.hitPose
            val fy = floorY
            if (heightMode) {
                if (fy == null) return null
                val dy = p.ty() - fy
                if (dy < 1.8f || dy > 8f) continue
                when (t) {
                    is Plane -> if (t.type == Plane.Type.HORIZONTAL_DOWNWARD_FACING && t.isPoseInPolygon(p)) return h to "ceiling-plane"
                    is DepthPoint -> return h to "ceiling-depth"
                    is Point -> return h to "ceiling-point"
                }
            } else {
                when (t) {
                    is Plane -> if (t.type == Plane.Type.HORIZONTAL_UPWARD_FACING && t.isPoseInPolygon(p) &&
                        (fy == null || abs(p.ty() - fy) < 0.12f)) return h to "plane"
                    is DepthPoint -> if (fy != null && abs(p.ty() - fy) < 0.08f) return h to "depth"
                }
            }
        }
        return null
    }

    private fun trackingMessage(r: TrackingFailureReason) = when (r) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark for AR – switch on the lights."
        TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast – move the phone slowly."
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Plain surface – aim at an area with texture or edges."
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera unavailable."
        else -> "Tracking lost – hold steady and move slowly. Corners already placed are kept."
    }

    private fun say(msg: String) {
        if (msg == lastStatus) return
        lastStatus = msg
        runOnUiThread { status.text = msg }
    }

    // ---------------- actions (queued to the GL thread) ----------------
    private fun addCorner() = actions.add { frame ->
        if (frame.camera.trackingState != TrackingState.TRACKING) { toast("Wait until tracking is back."); return@add }
        val hit = pickHit(frame) ?: run { toast("No floor under the centre dot."); return@add }
        val a = hit.first.createAnchor()
        if (floorY == null) floorY = a.pose.ty()
        corners += CapturedCorner(a, hit.second, System.currentTimeMillis())
    }

    private fun undo() = actions.add { _ ->
        corners.removeLastOrNull()?.anchor?.detach()
        if (corners.isEmpty()) { floorY = null; ceilingY = null; heightSource = null }
    }

    private fun deleteNearCentre() = actions.add { _ ->
        val pts = overlay.state.corners
        val cx = viewW / 2f; val cy = viewH / 2f
        val limit = 60 * resources.displayMetrics.density
        val i = pts.indices.filter { pts[it] != null }.minByOrNull { hypot(pts[it]!![0] - cx, pts[it]!![1] - cy) }
        if (i == null || hypot(pts[i]!![0] - cx, pts[i]!![1] - cy) > limit) { toast("Aim the centre at the corner to delete."); return@add }
        corners.removeAt(i).anchor.detach()
        toast("C${i + 1} deleted. Add it again where it belongs.")
    }

    private fun setHeightMode(on: Boolean) {
        if (on) actions.add { _ -> if (floorY == null) toast("Add a floor corner first.") else { heightMode = true; runOnUiThread { showBars() } } }
        else { heightMode = false; showBars() }
    }

    private fun showBars() {
        floorBar.visibility = if (heightMode) View.GONE else View.VISIBLE
        heightBar.visibility = if (heightMode) View.VISIBLE else View.GONE
    }

    private fun setHeightFromCeiling() = actions.add { frame ->
        val hit = pickHit(frame) ?: run { toast("Ceiling not found – try Type height."); return@add }
        ceilingY = hit.first.hitPose.ty(); heightSource = "ar-" + hit.second; manualHeight = null
        val h = ceilingY!! - floorY!!
        toast(String.format(Locale.US, "Height %.2f m (AR estimate).", h))
        heightMode = false
        runOnUiThread { showBars() }
    }

    private fun typeHeight() {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; hint = "e.g. 3.05" }
        AlertDialog.Builder(this).setTitle("Room height (m)").setView(input)
            .setPositiveButton("OK") { _, _ ->
                val v = input.text.toString().replace(',', '.').toDoubleOrNull()
                if (v == null || v < 1.8 || v > 8) toast("Enter a height between 1.8 and 8 m.")
                else actions.add { _ -> manualHeight = v; ceilingY = null; heightSource = "manual"; heightMode = false; runOnUiThread { showBars() } }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun finishRoom() = actions.add { _ ->
        val fy = floorY ?: run { toast("Add the floor corners first."); return@add }
        val c3 = corners.map { val p = it.anchor.pose; Corner3(p.tx().toDouble(), p.ty().toDouble(), p.tz().toDouble(), it.source, it.timeMs) }
        val problems = RoomGeometry.problems(RoomGeometry.toPlan(c3))
        if (problems.isNotEmpty()) { toast(problems.first()); return@add }
        val height = manualHeight ?: ceilingY?.let { (it - fy).toDouble() }
        val json = ArRoomPayload.build(c3, fy.toDouble(), height, heightSource, depthUsed, trackingLosses, startedAt).toString()
        runOnUiThread {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ROOM, json))
            finish()
        }
    }

    private fun cancel() {
        if (corners.isEmpty()) { setResult(RESULT_CANCELED); finish(); return }
        AlertDialog.Builder(this).setTitle("Discard this capture?")
            .setPositiveButton("Discard") { _, _ -> setResult(RESULT_CANCELED); finish() }
            .setNegativeButton("Keep capturing", null).show()
    }

    private fun toast(msg: String) = runOnUiThread { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show() }

    private fun fail(msg: String) {
        AlertDialog.Builder(this).setTitle("AR capture").setMessage(msg).setCancelable(false)
            .setPositiveButton("OK") { _, _ -> setResult(RESULT_CANCELED); finish() }.show()
    }

    companion object {
        const val EXTRA_ROOM = "room"

        /** Quick check before opening the camera screen; UNKNOWN results are treated as "try". */
        fun mayBeSupported(activity: AppCompatActivity): Boolean =
            ArCoreApk.getInstance().checkAvailability(activity) != ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE

    }
}
