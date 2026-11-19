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
    /** Camera frame the RGB image (if any) came from, and its pose – for colouring points later. */
    val rgbTimestampNs: Long = 0,
    val rgbPose: FloatArray? = null,
    /** Intrinsics of the RGB (CPU) image, needed to colour points from it. */
    val rgbIntrinsics: Intrinsics? = null,
    /** Save this RGB image as JPEG (every Nth keyframe); otherwise it is only used for colour. */
    val saveJpeg: Boolean = true,
    /** Where the depth pose came from. */
    val poseSource: String = "frame at depth timestamp",
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
    /** Recent world points (x,y,z packed) for the live coverage dots on the camera view. */
    @Volatile var live: FloatArray = FloatArray(0)
        private set
    private val liveRing = FloatArray(SurveyConfig.LIVE_POINTS * 3)
    private var liveAt = 0
    private var liveCount = 0
    @Volatile var colouredPoints = 0L
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
        val jpeg = if (!k.saveJpeg) null else k.rgbNv21?.let { runCatching { jpegEncoder?.invoke(it, k.rgbWidth, k.rgbHeight) }.getOrNull() }
        jpeg?.let { File(dir, "rgb/$id.jpg").writeBytes(it) }
        val rgbStatus = when {
            jpeg != null -> "saved"
            k.rgbNv21 != null && !k.saveJpeg -> "used for colour only"
            k.rgbNv21 != null -> "unavailable: JPEG encoding failed"
            else -> k.rgbStatus
        }
        val synced = DepthMath.isSynced(k.depthTimestampNs, k.cameraTimestampNs)
        val stats = PixelStats()
        if (synced) {
            val pts = DepthMath.framePoints(k.depth, k.confidence, k.width, k.height, k.depthIntrinsics, k.pose, stats)
            val sampler = k.rgbNv21?.let { nv -> k.rgbIntrinsics?.let { ki -> k.rgbPose?.let { pose -> runCatching { ColorSampler(nv, k.rgbWidth, k.rgbHeight, ki, pose) }.getOrNull() } } }
            val cols = sampler?.let { sm -> IntArray(pts.size / 4) { i -> sm.colourAt(pts[i * 4], pts[i * 4 + 1], pts[i * 4 + 2]) } }
            cols?.let { c -> colouredPoints += c.count { it != VoxelGrid.NO_COLOUR } }
            grid.addAll(pts, cols)
            updateLive(pts)
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
                .put("source", "Camera.getPose() of the camera frame the depth image belongs to (" + k.poseSource + "), ARCore world"))
            put("depth", JSONObject().put("file", "depth/$id.raw16le").put("width", k.width).put("height", k.height)
                .put("format", "uint16 little-endian, millimetres along the optical axis, 0 = no value, row-major tight"))
            put("confidence", if (k.confidence == null) JSONObject.NULL else JSONObject().put("file", "confidence/$id.raw8")
                .put("width", k.width).put("height", k.height).put("format", "uint8 0-255, row-major tight"))
            put("intrinsics", JSONObject().put("camera", k.cameraIntrinsics.toJson()).put("depth", k.depthIntrinsics.toJson())
                .put("note", "depth intrinsics = camera texture intrinsics scaled to the depth image size"))
            put("rgb", JSONObject().put("status", rgbStatus).apply {
                if (k.rgbNv21 != null) { put("usedForColour", k.rgbIntrinsics != null); k.rgbIntrinsics?.let { put("intrinsics", it.toJson()) } }
                if (jpeg != null) { put("file", "rgb/$id.jpg"); put("width", k.rgbWidth); put("height", k.rgbHeight)
                    put("cameraTimestampNs", k.rgbTimestampNs); k.rgbPose?.let { p -> put("poseMatrixColumnMajor", JSONArray(p.map { it.toDouble() })) } }
            })
            put("pixels", stats.toJson())
        }
        File(dir, "frames/$id.json").writeText(meta.toString(2))
    }

    /** Keeps a rolling sample of recent points for the live overlay (disk thread). */
    internal fun updateLive(pts: FloatArray) {
        val n = pts.size / 4
        if (n == 0) return
        val step = maxOf(1, n / 600)
        var i = 0
        while (i < n) {
            liveRing[liveAt * 3] = pts[i * 4]; liveRing[liveAt * 3 + 1] = pts[i * 4 + 1]; liveRing[liveAt * 3 + 2] = pts[i * 4 + 2]
            liveAt = (liveAt + 1) % SurveyConfig.LIVE_POINTS
            if (liveCount < SurveyConfig.LIVE_POINTS) liveCount++
            i += step
        }
        live = liveRing.copyOf(liveCount * 3)
    }

    /** Stop: wait for queued frames, then write cloud.xyzc, preview.bin and manifest.json. Blocking; call off the UI thread. */
    fun finish(appVersion: String): JSONObject {
        finished = true
        val f: Future<JSONObject> = io.submit<JSONObject> {
            val cloud = grid.points()
            val cols = grid.colours()
            val keep = grid.keepMask()
            val kept = keep.count { it }
            File(dir, "cloud.xyzc").bufferedWriter().use { w ->
                w.write("# LaserPlan AR survey processed cloud: x y z confidence\n")
                w.write("# metres, ARCore world frame (+Y up), voxel ${SurveyConfig.VOXEL_M} m averages; confidence 0-1; stray points removed\n")
                for (i in keep.indices) if (keep[i])
                    w.write(String.format(Locale.US, "%.4f %.4f %.4f %.3f\n", cloud[i * 4], cloud[i * 4 + 1], cloud[i * 4 + 2], cloud[i * 4 + 3]))
            }
            writePly(File(dir, "cloud.ply"), cloud, cols, keep, kept)
            val (px, pc) = grid.sample(keep = keep)
            writePreview5(File(dir, PREVIEW5), px, pc)
            val manifest = manifest(appVersion, kept).put("pointsBeforeStrayFilter", cloud.size / 4)
                .put("colouredPoints", cols.indices.count { keep[it] && cols[it] != VoxelGrid.NO_COLOUR })
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
            .put("method", "Per-pixel pinhole unprojection of ARCore raw depth with the pose of the camera frame the depth belongs to; reject 0 depth, confidence below minConfidence, range beyond maxRangeM, non-finite; voxel average; stray voxels with fewer than minNeighbours occupied neighbours removed (clouds of minPointsForFilter or more)")
            .put("colour", "Each point is projected into the RGB camera image of its keyframe (CPU image intrinsics and that image's own pose) and its colour sampled; voxel colour = average. Occlusion not checked.")
            .put("rawVsProcessed", "depth/ and confidence/ hold raw measurements; cloud.xyzc is processed and reduced"))
        put("accuracyNote", "Phone AR depth is estimate-grade (centimetres, worse at range and on plain or shiny surfaces). Not a survey-grade or LiDAR measurement. Confirm dimensions with the laser.")
        put("files", JSONObject().put("frames", "frames/NNNN.json").put("depth", "depth/NNNN.raw16le").put("confidence", "confidence/NNNN.raw8")
            .put("rgb", "rgb/NNNN.jpg (selected keyframes)").put("cloud", "cloud.xyzc").put("cloudColour", "cloud.ply (ASCII PLY with colour)"))
    }

    companion object {
        const val SCHEMA = "laserplan.ar-survey"
        const val VERSION = 1

        private fun iso(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

        const val PREVIEW5 = "preview5.bin"

        /** Viewer data: x,y,z,c and colour (as float bits) per point. */
        fun writePreview5(f: File, xyzc: FloatArray, rgb: IntArray) {
            val n = xyzc.size / 4
            val bb = ByteBuffer.allocate(n * 20).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) { for (j in 0..3) bb.putFloat(xyzc[i * 4 + j]); bb.putInt(rgb[i]) }
            f.writeBytes(bb.array())
        }

        /** Preview of a scan folder: v13.6+ (with colour) or older (no colour → colours null). */
        fun readPreviewAny(dir: File): Pair<FloatArray, IntArray?> {
            val f5 = File(dir, PREVIEW5)
            if (f5.isFile) {
                val bb = ByteBuffer.wrap(f5.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                val n = bb.remaining() / 20
                val p = FloatArray(n * 4); val c = IntArray(n)
                for (i in 0 until n) { for (j in 0..3) p[i * 4 + j] = bb.float; c[i] = bb.int }
                return p to c
            }
            return readPreview(File(dir, "preview.bin")) to null
        }

        /** ASCII PLY with colour (grey where no photo saw the point) and confidence; opens in CloudCompare / MeshLab. */
        fun writePly(f: File, xyzc: FloatArray, rgb: IntArray, keep: BooleanArray, kept: Int) {
            f.bufferedWriter().use { w ->
                w.write("ply\nformat ascii 1.0\ncomment LaserPlan AR survey, metres, ARCore world (+Y up); grey = no photo colour\n")
                w.write("element vertex $kept\nproperty float x\nproperty float y\nproperty float z\n")
                w.write("property uchar red\nproperty uchar green\nproperty uchar blue\nproperty float confidence\nend_header\n")
                for (i in keep.indices) if (keep[i]) {
                    val c = if (rgb[i] == VoxelGrid.NO_COLOUR) 0x808080 else rgb[i]
                    w.write(String.format(Locale.US, "%.4f %.4f %.4f %d %d %d %.3f\n", xyzc[i * 4], xyzc[i * 4 + 1], xyzc[i * 4 + 2],
                        (c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, xyzc[i * 4 + 3]))
                }
            }
        }

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

        /** Streams the session as one ZIP (manifest first; the viewer previews are internal and left out). */
        private val INTERNAL_FILES = setOf("preview.bin", PREVIEW5)

        fun zip(dir: File, out: OutputStream) {
            ZipOutputStream(out.buffered()).use { z ->
                val files = dir.walkTopDown().filter { it.isFile && it.name !in INTERNAL_FILES }
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
