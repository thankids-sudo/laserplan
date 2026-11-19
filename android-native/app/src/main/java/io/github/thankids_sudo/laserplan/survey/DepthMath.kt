package io.github.thankids_sudo.laserplan.survey

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.sqrt

/** Acquisition limits for a 3D survey session. Change here; they are written into every manifest. */
object SurveyConfig {
    // Keyframes are taken by movement (Phase B): every MIN_MOVE_M or MIN_TURN_DEG, but not faster than MIN_INTERVAL_MS;
    // when standing still, one every MAX_INTERVAL_MS.
    const val MIN_INTERVAL_MS = 250L
    const val MAX_INTERVAL_MS = 1500L
    const val MIN_MOVE_M = 0.10
    const val MIN_TURN_DEG = 10.0
    const val MAX_KEYFRAMES = 300                 // per session (~20 MB with 160×90 depth)
    const val RGB_EVERY_N_KEYFRAMES = 3           // JPEG saved on keyframes 1, 4, 7 …; every keyframe is used for colour
    const val JPEG_QUALITY = 85
    const val MIN_CONFIDENCE = 200                // raw-depth confidence byte, 0–255 (≈ 0.78; chosen from the first S24 Ultra scans)
    const val MAX_RANGE_M = 5.0                   // farther raw depth is too noisy for a room survey
    const val VOXEL_M = 0.02                      // 2 cm voxel reduction of the processed cloud
    const val MAX_VOXELS = 600_000                // memory cap for the processed cloud
    const val MIN_NEIGHBOURS = 3                  // stray-point filter: a voxel needs 3 of its 26 neighbours occupied
    const val MIN_POINTS_FOR_FILTER = 500         // tiny clouds are not filtered
    const val MAX_PREVIEW_POINTS = 40_000         // interactive viewer
    const val LIVE_POINTS = 4_000                 // green dots shown on the camera view while recording
    const val MAX_DEPTH_POSE_DELTA_NS = 50_000_000L // depth image vs camera pose must be within 50 ms
    const val RESUME_SETTLE_MS = 700L             // ignore frames right after tracking returns
    const val MAX_PENDING_WRITES = 4              // frames waiting for the disk thread; more are skipped

    fun toJson(): JSONObject = JSONObject()
        .put("minIntervalMs", MIN_INTERVAL_MS).put("maxIntervalMs", MAX_INTERVAL_MS).put("minMoveM", MIN_MOVE_M).put("minTurnDeg", MIN_TURN_DEG)
        .put("maxKeyframes", MAX_KEYFRAMES)
        .put("rgbEveryNKeyframes", RGB_EVERY_N_KEYFRAMES).put("jpegQuality", JPEG_QUALITY)
        .put("minConfidence", MIN_CONFIDENCE).put("minConfidenceNote", "raw depth confidence byte 0-255")
        .put("maxRangeM", MAX_RANGE_M).put("voxelM", VOXEL_M).put("maxVoxels", MAX_VOXELS)
        .put("minNeighbours", MIN_NEIGHBOURS).put("minPointsForFilter", MIN_POINTS_FOR_FILTER)
        .put("maxPreviewPoints", MAX_PREVIEW_POINTS).put("maxDepthPoseDeltaNs", MAX_DEPTH_POSE_DELTA_NS)
        .put("resumeSettleMs", RESUME_SETTLE_MS)
}

/** Pinhole intrinsics in pixels for an image of width × height. */
data class Intrinsics(val fx: Double, val fy: Double, val cx: Double, val cy: Double, val width: Int, val height: Int) {
    /** Same camera, different resolution of the same field of view (e.g. camera texture → depth image). */
    fun scaledTo(w: Int, h: Int): Intrinsics {
        val sx = w.toDouble() / width
        val sy = h.toDouble() / height
        return Intrinsics(fx * sx, fy * sy, cx * sx, cy * sy, w, h)
    }

    fun toJson(): JSONObject = JSONObject().put("fx", fx).put("fy", fy).put("cx", cx).put("cy", cy).put("width", width).put("height", height)
}

/** Why pixels were rejected, per frame. Kept in the frame metadata. */
data class PixelStats(
    var total: Int = 0, var invalid: Int = 0, var lowConfidence: Int = 0,
    var outOfRange: Int = 0, var nonFinite: Int = 0, var accepted: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("total", total).put("invalidDepth", invalid).put("lowConfidence", lowConfidence)
        .put("outOfRange", outOfRange).put("nonFinite", nonFinite).put("accepted", accepted)
}

/**
 * Raw-depth geometry. Conventions (ARCore):
 *  - image: u to the right, v down, pixel indices;
 *  - camera space: +X right, +Y up, −Z forward (OpenGL), metres;
 *  - raw depth is the distance along the optical axis (Z), in millimetres, 0 = no value;
 *  - world = cameraPose (Camera.getPose(), sensor-oriented) × camera point; column-major 4×4 matrix.
 */
object DepthMath {
    /** Copies a 16-bit little-endian plane with any row/pixel stride into a tight w×h×2 byte array. */
    fun compact16(src: ByteArray, rowStride: Int, pixelStride: Int, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h * 2)
        for (v in 0 until h) for (u in 0 until w) {
            val i = v * rowStride + u * pixelStride
            val o = (v * w + u) * 2
            out[o] = src[i]; out[o + 1] = src[i + 1]
        }
        return out
    }

    /** Copies an 8-bit plane with any row/pixel stride into a tight w×h array. */
    fun compact8(src: ByteArray, rowStride: Int, pixelStride: Int, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        for (v in 0 until h) for (u in 0 until w) out[v * w + u] = src[v * rowStride + u * pixelStride]
        return out
    }

    /** Depth in millimetres from a tight u16le array. */
    fun depthMm(tight: ByteArray, w: Int, u: Int, v: Int): Int {
        val o = (v * w + u) * 2
        return (tight[o].toInt() and 0xFF) or ((tight[o + 1].toInt() and 0xFF) shl 8)
    }

    /** Camera-space point (metres) for pixel (u, v) with optical-axis depth [zM]. */
    fun unproject(u: Double, v: Double, zM: Double, k: Intrinsics): DoubleArray =
        doubleArrayOf((u - k.cx) * zM / k.fx, -(v - k.cy) * zM / k.fy, -zM)

    /** Applies a column-major 4×4 rigid transform (Pose.toMatrix) to a point. */
    fun transform(m: FloatArray, p: DoubleArray): DoubleArray = doubleArrayOf(
        m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12],
        m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13],
        m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14],
    )

    /** True when the depth image and the camera pose belong to the same moment (within [maxDeltaNs]). */
    fun isSynced(depthTimestampNs: Long, cameraTimestampNs: Long, maxDeltaNs: Long = SurveyConfig.MAX_DEPTH_POSE_DELTA_NS) =
        depthTimestampNs > 0 && cameraTimestampNs > 0 && abs(depthTimestampNs - cameraTimestampNs) <= maxDeltaNs

    /**
     * World points from one keyframe: rejects 0 depth, low confidence (when a confidence image exists), points beyond
     * [maxRangeM] and non-finite results. Returns packed x,y,z,confidence(0–1) floats; counts go into [stats].
     */
    fun framePoints(
        depthTight: ByteArray, confTight: ByteArray?, w: Int, h: Int, k: Intrinsics, pose: FloatArray,
        stats: PixelStats, minConfidence: Int = SurveyConfig.MIN_CONFIDENCE, maxRangeM: Double = SurveyConfig.MAX_RANGE_M,
    ): FloatArray {
        require(k.width == w && k.height == h) { "Intrinsics must be scaled to the depth image" }
        val out = FloatArray(w * h * 4)
        var n = 0
        for (v in 0 until h) for (u in 0 until w) {
            stats.total++
            val mm = depthMm(depthTight, w, u, v)
            if (mm <= 0) { stats.invalid++; continue }
            val c = confTight?.let { it[v * w + u].toInt() and 0xFF } ?: 255
            if (c < minConfidence) { stats.lowConfidence++; continue }
            val z = mm / 1000.0
            if (z > maxRangeM) { stats.outOfRange++; continue }
            val wp = transform(pose, unproject(u.toDouble(), v.toDouble(), z, k))
            if (!wp[0].isFinite() || !wp[1].isFinite() || !wp[2].isFinite()) { stats.nonFinite++; continue }
            out[n++] = wp[0].toFloat(); out[n++] = wp[1].toFloat(); out[n++] = wp[2].toFloat(); out[n++] = c / 255f
            stats.accepted++
        }
        return out.copyOf(n)
    }
}

/**
 * Voxel reduction of the processed cloud: points in the same cube of [voxel] metres are averaged, and so are their
 * photo colours (when known). Bounded to [maxVoxels]; new voxels beyond the cap are counted as dropped.
 */
class VoxelGrid(private val voxel: Double = SurveyConfig.VOXEL_M, private val maxVoxels: Int = SurveyConfig.MAX_VOXELS) {
    private val index = HashMap<Long, Int>()
    private var keys = LongArray(1024)
    private var sums = FloatArray(4 * 1024)
    private var counts = IntArray(1024)
    private var rgbSums = FloatArray(3 * 1024)
    private var rgbCounts = IntArray(1024)
    var size = 0
        private set
    var dropped = 0L
        private set

    private fun cell(v: Float) = floor(v / voxel).toLong() + OFFSET
    private fun key(ix: Long, iy: Long, iz: Long): Long = (ix and MASK) or ((iy and MASK) shl 21) or ((iz and MASK) shl 42)

    @Synchronized
    fun addAll(xyzc: FloatArray, rgb: IntArray? = null) {
        var i = 0
        while (i + 3 < xyzc.size) { add(xyzc[i], xyzc[i + 1], xyzc[i + 2], xyzc[i + 3], rgb?.get(i / 4) ?: NO_COLOUR); i += 4 }
    }

    @Synchronized
    fun add(x: Float, y: Float, z: Float, c: Float, rgb: Int = NO_COLOUR) {
        val k = key(cell(x), cell(y), cell(z))
        val at = index[k] ?: run {
            if (size >= maxVoxels) { dropped++; return }
            if (size == counts.size) {
                val n = size * 2
                counts = counts.copyOf(n); keys = keys.copyOf(n); sums = sums.copyOf(n * 4); rgbSums = rgbSums.copyOf(n * 3); rgbCounts = rgbCounts.copyOf(n)
            }
            index[k] = size; keys[size] = k; size++; size - 1
        }
        sums[at * 4] += x; sums[at * 4 + 1] += y; sums[at * 4 + 2] += z; sums[at * 4 + 3] += c
        counts[at]++
        if (rgb != NO_COLOUR) {
            rgbSums[at * 3] += ((rgb shr 16) and 0xFF).toFloat(); rgbSums[at * 3 + 1] += ((rgb shr 8) and 0xFF).toFloat(); rgbSums[at * 3 + 2] += (rgb and 0xFF).toFloat()
            rgbCounts[at]++
        }
    }

    /** All voxel centroids as packed x,y,z,c. */
    @Synchronized
    fun points(): FloatArray {
        val out = FloatArray(size * 4)
        for (i in 0 until size) { val n = counts[i].toFloat(); for (j in 0..3) out[i * 4 + j] = sums[i * 4 + j] / n }
        return out
    }

    /** Average photo colour per voxel as 0xRRGGBB, or [NO_COLOUR] where no photo saw it. */
    @Synchronized
    fun colours(): IntArray = IntArray(size) { i ->
        val n = rgbCounts[i]
        if (n == 0) NO_COLOUR else {
            val r = (rgbSums[i * 3] / n).toInt().coerceIn(0, 255); val g = (rgbSums[i * 3 + 1] / n).toInt().coerceIn(0, 255); val b = (rgbSums[i * 3 + 2] / n).toInt().coerceIn(0, 255)
            (r shl 16) or (g shl 8) or b
        }
    }

    /** Stray-point filter: true for voxels with at least [minNeighbours] of their 26 neighbours occupied. */
    @Synchronized
    fun keepMask(minNeighbours: Int = SurveyConfig.MIN_NEIGHBOURS): BooleanArray {
        if (size < SurveyConfig.MIN_POINTS_FOR_FILTER) return BooleanArray(size) { true }
        return BooleanArray(size) { i ->
            val k = keys[i]
            val ix = k and MASK; val iy = (k shr 21) and MASK; val iz = (k shr 42) and MASK
            var n = 0
            loop@ for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                if (dx == 0 && dy == 0 && dz == 0) continue
                if (index.containsKey(key(ix + dx, iy + dy, iz + dz))) { n++; if (n >= minNeighbours) break@loop }
            }
            n >= minNeighbours
        }
    }

    /** At most [maxN] kept points, evenly strided (deterministic): packed x,y,z,c and matching colours. */
    @Synchronized
    fun sample(maxN: Int = SurveyConfig.MAX_PREVIEW_POINTS, keep: BooleanArray? = null): Pair<FloatArray, IntArray> {
        val all = points(); val cols = colours()
        val idx = (0 until size).filter { keep == null || keep[it] }
        val n = idx.size
        val m = minOf(n, maxN)
        val out = FloatArray(m * 4); val oc = IntArray(m)
        for (i in 0 until m) { val src = idx[(i.toLong() * n / m).toInt()]; System.arraycopy(all, src * 4, out, i * 4, 4); oc[i] = cols[src] }
        return out to oc
    }

    companion object {
        const val NO_COLOUR = -1
        private const val MASK = 0x1FFFFFL
        private const val OFFSET = 1L shl 20
    }
}

/**
 * Colours world points from one camera image (NV21) taken at a known pose. The image is in sensor orientation, like
 * the depth image, so the same camera convention applies: camera looks along −Z, image v grows downward.
 * Points behind the camera or outside the image get [VoxelGrid.NO_COLOUR]. Occlusion is not checked; the voxel
 * average over many keyframes smooths the occasional wrong sample.
 */
class ColorSampler(private val nv21: ByteArray, private val w: Int, private val h: Int, private val k: Intrinsics, pose: FloatArray) {
    private val m = pose
    init { require(nv21.size >= w * h * 3 / 2) { "NV21 buffer too small" } }

    fun colourAt(x: Float, y: Float, z: Float): Int {
        val dx = x - m[12]; val dy = y - m[13]; val dz = z - m[14]
        // camera = Rᵀ · (p − t); R's columns are m[0..2], m[4..6], m[8..10]
        val cx = m[0] * dx + m[1] * dy + m[2] * dz
        val cy = m[4] * dx + m[5] * dy + m[6] * dz
        val cz = m[8] * dx + m[9] * dy + m[10] * dz
        if (cz > -0.05f) return VoxelGrid.NO_COLOUR
        val d = -cz
        val u = (k.fx * cx / d + k.cx).toInt()
        val v = (k.cy - k.fy * cy / d).toInt()
        if (u < 0 || v < 0 || u >= w || v >= h) return VoxelGrid.NO_COLOUR
        return nv21Rgb(nv21, w, h, u, v)
    }

    companion object {
        /** NV21 pixel → 0xRRGGBB (BT.601, full range). */
        fun nv21Rgb(nv21: ByteArray, w: Int, h: Int, u: Int, v: Int): Int {
            val yy = (nv21[v * w + u].toInt() and 0xFF).toFloat()
            val o = w * h + (v / 2) * w + (u / 2) * 2
            val vv = (nv21[o].toInt() and 0xFF) - 128f
            val uu = (nv21[o + 1].toInt() and 0xFF) - 128f
            val r = (yy + 1.402f * vv).toInt().coerceIn(0, 255)
            val g = (yy - 0.344136f * uu - 0.714136f * vv).toInt().coerceIn(0, 255)
            val b = (yy + 1.772f * uu).toInt().coerceIn(0, 255)
            return (r shl 16) or (g shl 8) or b
        }
    }
}

/**
 * Recent camera poses by frame timestamp. ARCore's raw depth image usually belongs to an earlier camera frame
 * (in the first field test a median of 97 ms, up to seconds, earlier), so its points must be placed with the pose of
 * THAT frame, not the current one. Pure, unit-tested; used on the GL thread only.
 */
class PoseHistory(private val keepNs: Long = 3_000_000_000L) {
    class Rec(val timestampNs: Long, val matrix: FloatArray, val translation: FloatArray, val quaternion: FloatArray)
    private val recs = ArrayDeque<Rec>()

    fun add(r: Rec) {
        if (recs.isNotEmpty() && r.timestampNs <= recs.last().timestampNs) return
        recs.addLast(r)
        while (recs.isNotEmpty() && r.timestampNs - recs.first().timestampNs > keepNs) recs.removeFirst()
    }

    fun clear() = recs.clear()
    val size get() = recs.size

    /** The pose recorded at [timestampNs] (within [toleranceNs]), or null when it is not in the history. */
    /** Field data (S24 Ultra): depth stamps sit 1.9–2.0 ms from their frame; frames are ~33 ms apart, so 8 ms is unambiguous. */
    fun find(timestampNs: Long, toleranceNs: Long = 8_000_000L): Rec? =
        recs.minByOrNull { abs(it.timestampNs - timestampNs) }?.takeIf { abs(it.timestampNs - timestampNs) <= toleranceNs }
}

/**
 * When to take the next keyframe: after the phone has moved [minMoveM] or turned [minTurnDeg], but not faster than
 * [minIntervalMs]; while standing still, once every [maxIntervalMs]. Pure, unit-tested.
 */
class KeyframePolicy(
    private val minIntervalMs: Long = SurveyConfig.MIN_INTERVAL_MS,
    private val maxIntervalMs: Long = SurveyConfig.MAX_INTERVAL_MS,
    private val max: Int = SurveyConfig.MAX_KEYFRAMES,
    private val minMoveM: Double = SurveyConfig.MIN_MOVE_M,
    private val minTurnDeg: Double = SurveyConfig.MIN_TURN_DEG,
) {
    private var lastAt = Long.MIN_VALUE / 2
    private var trackingSince = Long.MIN_VALUE / 2
    private var lastT: FloatArray? = null
    private var lastQ: FloatArray? = null
    var taken = 0
        private set

    val full get() = taken >= max

    fun onTrackingRegained(nowMs: Long) { trackingSince = nowMs }

    /** [t] camera translation, [q] rotation quaternion (x, y, z, w); without them only the time rule applies. */
    fun shouldCapture(nowMs: Long, t: FloatArray? = null, q: FloatArray? = null): Boolean {
        if (full || nowMs - trackingSince < SurveyConfig.RESUME_SETTLE_MS) return false
        val dt = nowMs - lastAt
        if (dt < minIntervalMs) return false
        val lt = lastT; val lq = lastQ
        if (t == null || q == null || lt == null || lq == null) return dt >= maxIntervalMs
        return dt >= maxIntervalMs || dist(t, lt) >= minMoveM || turnDeg(q, lq) >= minTurnDeg
    }

    fun captured(nowMs: Long, t: FloatArray? = null, q: FloatArray? = null) { lastAt = nowMs; lastT = t?.copyOf(); lastQ = q?.copyOf(); taken++ }

    companion object {
        fun dist(a: FloatArray, b: FloatArray): Double { val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]; return sqrt((x * x + y * y + z * z).toDouble()) }
        /** Angle between two orientations, degrees. */
        fun turnDeg(a: FloatArray, b: FloatArray): Double {
            val d = abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]).toDouble().coerceAtMost(1.0)
            return Math.toDegrees(2 * acos(d))
        }
    }
}
