package io.github.thankids_sudo.laserplan.survey

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor

/** Acquisition limits for a 3D survey session. Change here; they are written into every manifest. */
object SurveyConfig {
    const val KEYFRAME_INTERVAL_MS = 1000L        // about one depth keyframe per second
    const val MAX_KEYFRAMES = 90                  // per session
    const val RGB_EVERY_N_KEYFRAMES = 3           // JPEG on keyframes 1, 4, 7 …
    const val JPEG_QUALITY = 85
    const val MIN_CONFIDENCE = 200                // raw-depth confidence byte, 0–255 (≈ 0.78; chosen from the first S24 Ultra scans)
    const val MAX_RANGE_M = 5.0                   // farther raw depth is too noisy for a room survey
    const val VOXEL_M = 0.02                      // 2 cm voxel reduction of the processed cloud
    const val MAX_VOXELS = 600_000                // memory cap for the processed cloud
    const val MAX_PREVIEW_POINTS = 40_000         // interactive viewer
    const val MAX_DEPTH_POSE_DELTA_NS = 50_000_000L // depth image vs camera pose must be within 50 ms
    const val RESUME_SETTLE_MS = 700L             // ignore frames right after tracking returns
    const val MAX_PENDING_WRITES = 4              // frames waiting for the disk thread; more are skipped

    fun toJson(): JSONObject = JSONObject()
        .put("keyframeIntervalMs", KEYFRAME_INTERVAL_MS).put("maxKeyframes", MAX_KEYFRAMES)
        .put("rgbEveryNKeyframes", RGB_EVERY_N_KEYFRAMES).put("jpegQuality", JPEG_QUALITY)
        .put("minConfidence", MIN_CONFIDENCE).put("minConfidenceNote", "raw depth confidence byte 0-255")
        .put("maxRangeM", MAX_RANGE_M).put("voxelM", VOXEL_M).put("maxVoxels", MAX_VOXELS)
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
 * Voxel reduction of the processed cloud: points falling in the same cube of [voxel] metres are averaged.
 * Bounded to [maxVoxels]; new voxels beyond the cap are counted as dropped, existing ones still refine.
 */
class VoxelGrid(private val voxel: Double = SurveyConfig.VOXEL_M, private val maxVoxels: Int = SurveyConfig.MAX_VOXELS) {
    private val index = HashMap<Long, Int>()
    private var sums = FloatArray(4 * 1024)
    private var counts = IntArray(1024)
    var size = 0
        private set
    var dropped = 0L
        private set

    private fun key(x: Float, y: Float, z: Float): Long {
        val ix = floor(x / voxel).toLong() + (1L shl 20)
        val iy = floor(y / voxel).toLong() + (1L shl 20)
        val iz = floor(z / voxel).toLong() + (1L shl 20)
        return (ix and 0x1FFFFF) or ((iy and 0x1FFFFF) shl 21) or ((iz and 0x1FFFFF) shl 42)
    }

    @Synchronized
    fun addAll(xyzc: FloatArray) {
        var i = 0
        while (i + 3 < xyzc.size) { add(xyzc[i], xyzc[i + 1], xyzc[i + 2], xyzc[i + 3]); i += 4 }
    }

    @Synchronized
    fun add(x: Float, y: Float, z: Float, c: Float) {
        val k = key(x, y, z)
        val at = index[k] ?: run {
            if (size >= maxVoxels) { dropped++; return }
            if (size == counts.size) { counts = counts.copyOf(size * 2); sums = sums.copyOf(size * 8) }
            index[k] = size; size++; size - 1
        }
        sums[at * 4] += x; sums[at * 4 + 1] += y; sums[at * 4 + 2] += z; sums[at * 4 + 3] += c
        counts[at]++
    }

    /** All voxel centroids as packed x,y,z,c. */
    @Synchronized
    fun points(): FloatArray {
        val out = FloatArray(size * 4)
        for (i in 0 until size) { val n = counts[i].toFloat(); for (j in 0..3) out[i * 4 + j] = sums[i * 4 + j] / n }
        return out
    }

    /** At most [maxN] points, evenly strided over the cloud (deterministic). */
    @Synchronized
    fun sample(maxN: Int = SurveyConfig.MAX_PREVIEW_POINTS): FloatArray {
        val all = points()
        val n = all.size / 4
        if (n <= maxN) return all
        val out = FloatArray(maxN * 4)
        for (i in 0 until maxN) { val src = (i.toLong() * n / maxN).toInt(); System.arraycopy(all, src * 4, out, i * 4, 4) }
        return out
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

/** When to take the next keyframe. Pure, unit-tested. */
class KeyframePolicy(private val intervalMs: Long = SurveyConfig.KEYFRAME_INTERVAL_MS, private val max: Int = SurveyConfig.MAX_KEYFRAMES) {
    private var lastAt = Long.MIN_VALUE / 2
    private var trackingSince = Long.MIN_VALUE / 2
    var taken = 0
        private set

    val full get() = taken >= max

    fun onTrackingRegained(nowMs: Long) { trackingSince = nowMs }

    fun shouldCapture(nowMs: Long): Boolean =
        !full && nowMs - lastAt >= intervalMs && nowMs - trackingSince >= SurveyConfig.RESUME_SETTLE_MS

    fun captured(nowMs: Long) { lastAt = nowMs; taken++ }
}
