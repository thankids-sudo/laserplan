package io.github.thankids_sudo.laserplan.survey

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.sqrt

/** Rigid transform: rotation quaternion (x, y, z, w) and translation, metres. Pure; unit-tested. */
class Rigid(val q: FloatArray, val t: FloatArray) {
    init { require(q.size == 4 && t.size == 3) }

    fun apply(x: Float, y: Float, z: Float, out: FloatArray, o: Int) {
        val r = rot(q, x, y, z)
        out[o] = r[0] + t[0]; out[o + 1] = r[1] + t[1]; out[o + 2] = r[2] + t[2]
    }

    /** this ∘ other (apply [other] first). */
    fun compose(other: Rigid): Rigid { val r = rot(q, other.t[0], other.t[1], other.t[2]); return Rigid(mul(q, other.q), floatArrayOf(r[0] + t[0], r[1] + t[1], r[2] + t[2])) }

    fun inverse(): Rigid { val qi = conj(q); val r = rot(qi, t[0], t[1], t[2]); return Rigid(qi, floatArrayOf(-r[0], -r[1], -r[2])) }

    val translationM get() = sqrt((t[0] * t[0] + t[1] * t[1] + t[2] * t[2]).toDouble())
    val rotationDeg get() = Math.toDegrees(2 * acos(abs(q[3].toDouble()).coerceAtMost(1.0)))

    companion object {
        val IDENTITY = Rigid(floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(0f, 0f, 0f))

        fun mul(a: FloatArray, b: FloatArray) = floatArrayOf(
            a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
            a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
            a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
            a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
        )
        fun conj(a: FloatArray) = floatArrayOf(-a[0], -a[1], -a[2], a[3])
        fun rot(q: FloatArray, x: Float, y: Float, z: Float): FloatArray {
            val r = mul(mul(q, floatArrayOf(x, y, z, 0f)), conj(q)); return floatArrayOf(r[0], r[1], r[2])
        }

        /** Interpolates two transforms: translation linear, rotation spherical. [f] 0 → a, 1 → b. */
        fun lerp(a: Rigid, b: Rigid, f: Float): Rigid {
            var bq = b.q
            var d = a.q[0] * bq[0] + a.q[1] * bq[1] + a.q[2] * bq[2] + a.q[3] * bq[3]
            if (d < 0) { bq = FloatArray(4) { -bq[it] }; d = -d }
            val q = if (d > 0.9995f) FloatArray(4) { a.q[it] + f * (bq[it] - a.q[it]) } else {
                val th = acos(d.toDouble()); val s = sin(th)
                val wa = (sin((1 - f) * th) / s).toFloat(); val wb = (sin(f * th) / s).toFloat()
                FloatArray(4) { wa * a.q[it] + wb * bq[it] }
            }
            val n = sqrt(q.sumOf { (it * it).toDouble() }).toFloat()
            return Rigid(FloatArray(4) { q[it] / n }, FloatArray(3) { a.t[it] + f * (b.t[it] - a.t[it]) })
        }
    }
}

/**
 * Uses ARCore anchors to correct drift after the scan. While recording, an anchor is placed at the camera pose of every
 * [SurveyConfig.ANCHOR_EVERY_N_KEYFRAMES]th keyframe. ARCore keeps refining anchor poses as its map improves, so at
 * Stop the change of each anchor (now · then⁻¹) says how far that part of the scan has moved in ARCore's own, better
 * map. Each keyframe gets the correction interpolated between its nearest usable anchors (by keyframe number).
 * An anchor is not used when ARCore no longer tracks it or its change is implausibly large. Pure; unit-tested.
 */
object PoseRefinement {
    class Obs(val frame: Int, val then: Rigid, val now: Rigid?, val tracking: Boolean)

    class Result(val perFrame: Map<Int, Rigid>, val used: Int, val rejected: Int, val notTracking: Int) {
        fun summary(total: Int): JSONObject {
            val mm = perFrame.values.map { it.translationM * 1000 }.sorted()
            val deg = perFrame.values.map { it.rotationDeg }.sorted()
            return JSONObject()
                .put("method", "ARCore anchors at keyframe camera poses; correction = anchor pose at Stop × anchor pose when placed⁻¹, interpolated per keyframe")
                .put("anchors", used + rejected + notTracking).put("anchorsUsed", used).put("anchorsRejectedTooLarge", rejected)
                .put("anchorsNotTracking", notTracking).put("framesCorrected", perFrame.size).put("framesTotal", total)
                .put("medianCorrectionMm", if (mm.isEmpty()) 0.0 else mm[mm.size / 2]).put("maxCorrectionMm", mm.lastOrNull() ?: 0.0)
                .put("medianCorrectionDeg", if (deg.isEmpty()) 0.0 else deg[deg.size / 2]).put("maxCorrectionDeg", deg.lastOrNull() ?: 0.0)
                .put("limits", JSONObject().put("maxCorrectionM", SurveyConfig.MAX_ANCHOR_CORRECTION_M).put("maxCorrectionDeg", SurveyConfig.MAX_ANCHOR_CORRECTION_DEG))
        }

        fun perFrameJson(): JSONArray = JSONArray().apply {
            perFrame.toSortedMap().forEach { (i, c) ->
                put(JSONObject().put("frame", i).put("translation", JSONArray(c.t.map { it.toDouble() }))
                    .put("quaternionXYZW", JSONArray(c.q.map { it.toDouble() })).put("mm", c.translationM * 1000).put("deg", c.rotationDeg))
            }
        }
    }

    fun correction(then: Rigid, now: Rigid): Rigid = now.compose(then.inverse())

    fun solve(
        obs: List<Obs>, frames: Collection<Int>,
        maxM: Double = SurveyConfig.MAX_ANCHOR_CORRECTION_M, maxDeg: Double = SurveyConfig.MAX_ANCHOR_CORRECTION_DEG,
    ): Result {
        var rejected = 0; var notTracking = 0
        val good = sortedMapOf<Int, Rigid>()
        for (o in obs) {
            val now = o.now
            if (!o.tracking || now == null) { notTracking++; continue }
            val c = correction(o.then, now)
            if (c.translationM > maxM || c.rotationDeg > maxDeg) { rejected++; continue }
            good[o.frame] = c
        }
        if (good.isEmpty()) return Result(emptyMap(), 0, rejected, notTracking)
        val keys = good.keys.toList()
        val out = HashMap<Int, Rigid>()
        for (f in frames) {
            val after = keys.firstOrNull { it >= f }
            val before = keys.lastOrNull { it <= f }
            out[f] = when {
                before == null -> good[after]!!
                after == null || after == before -> good[before]!!
                else -> Rigid.lerp(good[before]!!, good[after]!!, (f - before).toFloat() / (after - before))
            }
        }
        return Result(out, good.size, rejected, notTracking)
    }
}

/** Floor-to-ceiling band of a cloud, for the "walls only" view: skips the floor and ceiling layers (0.20 m margin chosen on the 20 Nov scan, whose floor layer was ~10 cm thick). Pure; unit-tested. */
object WallBand {
    /** (low, high) Y limits, or null when the cloud is too small or too flat to tell. */
    fun of(ys: FloatArray, margin: Float = 0.20f): Pair<Float, Float>? {
        if (ys.size < 100) return null
        val s = ys.sortedArray()
        val floor = s[(s.size * 0.02).toInt()]; val ceil = s[(s.size * 0.98).toInt().coerceAtMost(s.size - 1)]
        if (ceil - floor < 0.8f) return null
        return (floor + margin) to (ceil - margin)
    }
}
