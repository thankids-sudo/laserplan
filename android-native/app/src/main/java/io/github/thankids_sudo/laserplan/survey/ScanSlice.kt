package io.github.thankids_sudo.laserplan.survey

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.floor
import kotlin.math.max

/**
 * Top-view "wall slice" of a scan for the plan (Phase C): the points between floor and ceiling (2nd–98th height
 * percentile, minus [MARGIN]), reduced to one point per [CELL] square in plan (x, z). It is a small 2D summary for the
 * web plan, never the point cloud itself; at most [MAX_CELLS] cells are sent. Pure; unit-tested.
 */
object ScanSlice {
    const val CELL = 0.03
    const val MARGIN = 0.20
    const val MAX_CELLS = 15000

    class Slice(val xzMm: IntArray, val floor: Double, val ceil: Double, val cell: Double, val sourcePoints: Int) { val cells get() = xzMm.size / 2 }

    /** [xyz] packed x, y, z per point (stride 3), metres, +Y up. */
    fun fromPoints(xyz: FloatArray, cell: Double = CELL, maxCells: Int = MAX_CELLS): Slice? {
        val n = xyz.size / 3; if (n < 50) return null
        val ys = FloatArray(n) { xyz[it * 3 + 1] }; ys.sort()
        val fl = ys[(n * 0.02).toInt()].toDouble(); val ce = ys[minOf(n - 1, (n * 0.98).toInt())].toDouble()
        val lo: Double; val hi: Double
        if (ce - fl > 1.0) { lo = fl + MARGIN; hi = ce - MARGIN } else { lo = Double.NEGATIVE_INFINITY; hi = Double.POSITIVE_INFINITY }
        val seen = LinkedHashSet<Long>()
        for (i in 0 until n) { val y = xyz[i * 3 + 1]; if (y < lo || y > hi) continue
            val ix = floor(xyz[i * 3] / cell).toLong(); val iz = floor(xyz[i * 3 + 2] / cell).toLong()
            seen.add(((ix + (1L shl 30)) shl 32) or (iz + (1L shl 30))) }
        if (seen.isEmpty()) return null
        val step = max(1, (seen.size + maxCells - 1) / maxCells)
        val out = ArrayList<Int>(minOf(seen.size, maxCells) * 2); var k = 0
        for (key in seen) { if (k++ % step != 0) continue
            val ix = (key ushr 32) - (1L shl 30); val iz = (key and 0xFFFFFFFFL) - (1L shl 30)
            out.add(Math.round((ix + 0.5) * cell * 1000).toInt()); out.add(Math.round((iz + 0.5) * cell * 1000).toInt()) }
        return Slice(out.toIntArray(), fl, ce, cell, n)
    }

    /** Reads cloud.xyzc of a finished scan (or the viewer preview for older scans). */
    fun fromDir(dir: File): Slice? {
        val f = File(dir, "cloud.xyzc")
        if (f.isFile) {
            val v = ArrayList<Float>(1 shl 16)
            f.forEachLine { l -> if (l.isEmpty() || l[0] == '#') return@forEachLine
                val p = l.split(' '); if (p.size >= 3) { p[0].toFloatOrNull()?.let { x -> p[1].toFloatOrNull()?.let { y -> p[2].toFloatOrNull()?.let { z -> v.add(x); v.add(y); v.add(z) } } } } }
            return fromPoints(v.toFloatArray())
        }
        val (pv, _) = SurveyRecorder.readPreviewAny(dir); if (pv.isEmpty()) return null
        return fromPoints(FloatArray(pv.size / 4 * 3) { val i = it / 3; pv[i * 4 + it % 3] })
    }

    fun message(projectId: String, scanId: String, s: Slice): String = JSONObject()
        .put("type", "scan.slice").put("projectId", projectId).put("scanId", scanId).put("cell", s.cell)
        .put("floor", s.floor).put("ceil", s.ceil).put("sourcePoints", s.sourcePoints).put("pts", JSONArray(s.xzMm.toList())).toString()
}
