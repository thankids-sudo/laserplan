package io.github.thankids_sudo.laserplan.survey

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One keyframe, already copied out of ARCore images (tight buffers), handed to the disk thread. */
class Keyframe(
    val depth: ByteArray,                 // u16le millimetres, w×h×2
    val confidence: ByteArray?,           // u8, w×h, null if the phone gave none
    val width: Int,
    val height: Int,
    val depthIntrinsics: Intrinsics,      // scaled to the depth image
    val cameraIntrinsics: Intrinsics,     // as reported by ARCore (texture)
    val pose: FloatArray,                 // camera sensor pose, column-major 4×4
    val poseTranslation: FloatArray,
    val poseQuaternion: FloatArray,       // x, y, z, w
    val depthTimestampNs: Long,
    val cameraTimestampNs: Long,
    val rgbNv21: ByteArray?,              // camera image as NV21, compressed to JPEG on the disk thread; null if none
    val rgbStatus: String,                // "captured", "not-requested", "unavailable: <reason>"
    val rgbWidth: Int = 0,
    val rgbHeight: Int = 0,
)

/**
 * A 3D survey session on disk (app-private cache, never uploaded): raw depth, confidence, optional RGB keyframes and
 * per-frame metadata, plus a voxel-reduced processed cloud. All disk work and point processing run on one background
 * thread, so the AR render thread only copies image buffers.
 */
class SurveyRecorder(
    val dir: File,
    private val device: JSONObject,
    private val depthInfo: JSONObject,
    /** NV21 → JPEG; supplied by the Android side (YuvImage). Null in unit tests. */
    private val jpegEncoder: ((ByteArray, Int, Int) -> ByteArray?)? = null,
) {
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "survey-io").apply { isDaemon = true } }
    private val grid = VoxelGrid()
    private val pending = AtomicInteger(0)
    val frames = AtomicInteger(0)
    val validFrames = AtomicInteger(0)
    val skippedBusy = AtomicInteger(0)
    @Volatile var points = 0
        private set
    @Volatile var finished = false
        private set
    private val startedAt = System.currentTimeMillis()

    init {
        listOf("frames", "depth", "confidence", "rgb").forEach { File(dir, it).mkdirs() }
    }

    /** Queue a keyframe. Returns false (and counts it) when the disk thread is behind, so memory stays bounded. */
    fun submit(k: Keyframe): Boolean {
        if (finished) return false
        if (pending.get() >= SurveyConfig.MAX_PENDING_WRITES) { skippedBusy.incrementAndGet(); return false }
        val n = frames.incrementAndGet()
        pending.incrementAndGet()
        io.execute {
            try { write(n, k) } finally { pending.decrementAndGet() }
        }
        return true
    }

    private fun write(n: Int, k: Keyframe) {
        val id = "%04d".format(n)
        File(dir, "depth/$id.raw16le").writeBytes(k.depth)
        k.confidence?.let { File(dir, "confidence/$id.raw8").writeBytes(it) }
        val jpeg = k.rgbNv21?.let { runCatching { jpegEncoder?.invoke(it, k.rgbWidth, k.rgbHeight) }.getOrNull() }
        jpeg?.let { File(dir, "rgb/$id.jpg").writeBytes(it) }
        val rgbStatus = when {
            jpeg != null -> "saved"
            k.rgbNv21 != null -> "unavailable: JPEG encoding failed"
            else -> k.rgbStatus
        }
        val synced = DepthMath.isSynced(k.depthTimestampNs, k.cameraTimestampNs)
        val stats = PixelStats()
        if (synced) {
            val pts = DepthMath.framePoints(k.depth, k.confidence, k.width, k.height, k.depthIntrinsics, k.pose, stats)
            grid.addAll(pts)
            points = grid.size
            validFrames.incrementAndGet()
        }
        val meta = JSONObject().apply {
            put("index", n)
            put("valid", synced)
            if (!synced) put("invalidReason", "depth image and camera pose more than ${SurveyConfig.MAX_DEPTH_POSE_DELTA_NS / 1_000_000} ms apart – raw data kept, not added to the cloud")
            put("depthTimestampNs", k.depthTimestampNs)
            put("cameraTimestampNs", k.cameraTimestampNs)
            put("depthToCameraDeltaNs", k.depthTimestampNs - k.cameraTimestampNs)
            put("pose", JSONObject()
                .put("matrixColumnMajor", JSONArray(k.pose.map { it.toDouble() }))
                .put("translation", JSONArray(k.poseTranslation.map { it.toDouble() }))
                .put("quaternionXYZW", JSONArray(k.poseQuaternion.map { it.toDouble() }))
                .put("source", "Camera.getPose() – camera sensor frame, ARCore world"))
            put("depth", JSONObject().put("file", "depth/$id.raw16le").put("width", k.width).put("height", k.height)
                .put("format", "uint16 little-endian, millimetres along the optical axis, 0 = no value, row-major tight"))
            put("confidence", if (k.confidence == null) JSONObject.NULL else JSONObject().put("file", "confidence/$id.raw8")
                .put("width", k.width).put("height", k.height).put("format", "uint8 0-255, row-major tight"))
            put("intrinsics", JSONObject().put("camera", k.cameraIntrinsics.toJson()).put("depth", k.depthIntrinsics.toJson())
                .put("note", "depth intrinsics = camera texture intrinsics scaled to the depth image size"))
            put("rgb", JSONObject().put("status", rgbStatus).apply {
                if (jpeg != null) { put("file", "rgb/$id.jpg"); put("width", k.rgbWidth); put("height", k.rgbHeight) }
            })
            put("pixels", stats.toJson())
        }
        File(dir, "frames/$id.json").writeText(meta.toString(2))
    }

    /** Stop: wait for queued frames, then write cloud.xyzc, preview.bin and manifest.json. Blocking; call off the UI thread. */
    fun finish(appVersion: String): JSONObject {
        finished = true
        val f: Future<JSONObject> = io.submit<JSONObject> {
            val cloud = grid.points()
            File(dir, "cloud.xyzc").bufferedWriter().use { w ->
                w.write("# LaserPlan AR survey processed cloud: x y z confidence\n")
                w.write("# metres, ARCore world frame (+Y up), voxel ${SurveyConfig.VOXEL_M} m averages; confidence 0-1\n")
                var i = 0
                while (i < cloud.size) {
                    w.write(String.format(Locale.US, "%.4f %.4f %.4f %.3f\n", cloud[i], cloud[i + 1], cloud[i + 2], cloud[i + 3])); i += 4
                }
            }
            writePreview(File(dir, "preview.bin"), grid.sample())
            val manifest = manifest(appVersion, cloud.size / 4)
            File(dir, "manifest.json").writeText(manifest.toString(2))
            manifest
        }
        val m = f.get(60, TimeUnit.SECONDS)
        io.shutdown()
        return m
    }

    private fun manifest(appVersion: String, pointCount: Int): JSONObject = JSONObject().apply {
        put("schema", SCHEMA); put("version", VERSION)
        put("app", JSONObject().put("name", "LaserPlan").put("version", appVersion))
        put("device", device)
        put("createdAt", iso(startedAt)); put("finishedAt", iso(System.currentTimeMillis()))
        put("units", JSONObject().put("length", "metre").put("rawDepth", "millimetre").put("time", "nanosecond (ARCore timestamps)"))
        put("coordinates", JSONObject()
            .put("world", "ARCore world: right-handed, metres, +Y up (gravity aligned); origin and heading fixed at session start")
            .put("camera", "+X right, +Y up, -Z forward (OpenGL); image u right, v down")
            .put("unprojection", "x=(u-cx)*z/fx, y=-(v-cy)*z/fy, camera point=(x,y,-z); world = pose matrix × camera point"))
        put("settings", SurveyConfig.toJson())
        put("depth", depthInfo)
        put("frameCount", frames.get()); put("validFrameCount", validFrames.get()); put("skippedBecauseBusy", skippedBusy.get())
        put("pointCount", pointCount); put("pointsDroppedAtCap", grid.dropped)
        put("processing", JSONObject()
            .put("method", "Per-pixel pinhole unprojection of ARCore raw depth; reject 0 depth, confidence below minConfidence, range beyond maxRangeM, non-finite; voxel average")
            .put("rawVsProcessed", "depth/ and confidence/ hold raw measurements; cloud.xyzc is processed and reduced"))
        put("accuracyNote", "Phone AR depth is estimate-grade (centimetres, worse at range and on plain or shiny surfaces). Not a survey-grade or LiDAR measurement. Confirm dimensions with the laser.")
        put("files", JSONObject().put("frames", "frames/NNNN.json").put("depth", "depth/NNNN.raw16le").put("confidence", "confidence/NNNN.raw8")
            .put("rgb", "rgb/NNNN.jpg (selected keyframes)").put("cloud", "cloud.xyzc"))
    }

    companion object {
        const val SCHEMA = "laserplan.ar-survey"
        const val VERSION = 1

        private fun iso(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

        fun writePreview(f: File, xyzc: FloatArray) {
            val bb = ByteBuffer.allocate(xyzc.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(xyzc)
            f.writeBytes(bb.array())
        }

        fun readPreview(f: File): FloatArray {
            if (!f.isFile) return FloatArray(0)
            val b = f.readBytes()
            val out = FloatArray(b.size / 4)
            ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
            return out
        }

        /** Streams the session as one ZIP (manifest first; preview.bin is internal and left out). */
        fun zip(dir: File, out: OutputStream) {
            ZipOutputStream(out.buffered()).use { z ->
                val files = dir.walkTopDown().filter { it.isFile && it.name != "preview.bin" }
                    .sortedWith(compareBy<File> { it.name != "manifest.json" }.thenBy { it.relativeTo(dir).path })
                for (f in files) {
                    z.putNextEntry(ZipEntry(f.relativeTo(dir).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
        }

        /** Keeps the newest [keep] sessions in [root]; older ones are deleted to bound storage. */
        fun prune(root: File, keep: Int = 3) {
            root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.drop(keep)?.forEach { it.deleteRecursively() }
        }
    }
}
