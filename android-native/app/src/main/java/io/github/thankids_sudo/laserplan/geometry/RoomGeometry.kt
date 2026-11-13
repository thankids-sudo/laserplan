package io.github.thankids_sudo.laserplan.geometry

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A point on the floor plan, metres. */
data class P2(val x: Double, val y: Double)

/** A captured corner in ARCore world space (metres, y up), with where it came from. */
data class Corner3(val x: Double, val y: Double, val z: Double, val source: String, val timeMs: Long)

/**
 * Pure room geometry for AR capture: wall lengths, angles, diagonals, area, closure and validity checks.
 * Phone-AR dimensions are sketch-grade; the drawing marks them as estimates until confirmed by the laser.
 */
object RoomGeometry {
    /** ARCore world (x, y-up, z) → plan (x, z), the same mapping the WebXR capture used. */
    fun toPlan(c: List<Corner3>): List<P2> = c.map { P2(it.x, it.z) }

    fun wallLengths(p: List<P2>): List<Double> =
        if (p.size < 2) emptyList() else p.indices.map { i -> dist(p[i], p[(i + 1) % p.size]) }

    fun perimeter(p: List<P2>) = if (p.size < 3) 0.0 else wallLengths(p).sum()

    /** Unsigned shoelace area. */
    fun area(p: List<P2>): Double {
        if (p.size < 3) return 0.0
        var s = 0.0
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += a.x * b.y - b.x * a.y }
        return abs(s) / 2
    }

    /** Interior angle at each corner, degrees (works for either winding of a simple polygon). */
    fun interiorAngles(p: List<P2>): List<Double> {
        if (p.size < 3) return emptyList()
        val ccw = signedArea(p) > 0
        return p.indices.map { i ->
            val a = p[(i - 1 + p.size) % p.size]; val b = p[i]; val c = p[(i + 1) % p.size]
            val v1x = a.x - b.x; val v1y = a.y - b.y; val v2x = c.x - b.x; val v2y = c.y - b.y
            val cos = ((v1x * v2x + v1y * v2y) / (hypot(v1x, v1y) * hypot(v2x, v2y))).coerceIn(-1.0, 1.0)
            val ang = Math.toDegrees(acos(cos))
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if ((cross > 0) == ccw) ang else 360 - ang
        }
    }

    /** Diagonals from each corner to every non-adjacent corner (for a quadrilateral: the two diagonals). */
    fun diagonals(p: List<P2>): List<Triple<Int, Int, Double>> {
        val out = mutableListOf<Triple<Int, Int, Double>>()
        for (i in p.indices) for (j in i + 2 until p.size) {
            if (i == 0 && j == p.size - 1) continue
            out += Triple(i, j, dist(p[i], p[j]))
        }
        return out
    }

    /** True when two non-adjacent walls cross: the polygon is not a valid room outline. */
    fun selfIntersects(p: List<P2>): Boolean {
        val n = p.size
        if (n < 4) return false
        for (i in 0 until n) for (j in i + 1 until n) {
            if (j == i + 1 || (i == 0 && j == n - 1)) continue
            if (segmentsCross(p[i], p[(i + 1) % n], p[j], p[(j + 1) % n])) return true
        }
        return false
    }

    /** Problems that block "Finish", in plain words. Empty when the outline is usable. */
    fun problems(p: List<P2>): List<String> {
        val out = mutableListOf<String>()
        if (p.size < 3) out += "At least 3 corners are needed."
        wallLengths(p).forEachIndexed { i, l -> if (p.size >= 3 && l < 0.05) out += "Corners C${i + 1} and C${(i + 1) % p.size + 1} are less than 5 cm apart." }
        if (selfIntersects(p)) out += "Walls cross each other – corners are out of order."
        if (p.size >= 3 && area(p) < 0.5) out += "Room area is under 0.5 m² – check the corners."
        return out
    }

    private fun signedArea(p: List<P2>): Double {
        var s = 0.0
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += a.x * b.y - b.x * a.y }
        return s / 2
    }

    private fun dist(a: P2, b: P2) = hypot(b.x - a.x, b.y - a.y)
    private fun orient(a: P2, b: P2, c: P2) = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
    private fun onSeg(a: P2, b: P2, c: P2) = min(a.x, b.x) - 1e-9 <= c.x && c.x <= max(a.x, b.x) + 1e-9 && min(a.y, b.y) - 1e-9 <= c.y && c.y <= max(a.y, b.y) + 1e-9
    private fun segmentsCross(p1: P2, p2: P2, p3: P2, p4: P2): Boolean {
        val d1 = orient(p3, p4, p1); val d2 = orient(p3, p4, p2); val d3 = orient(p1, p2, p3); val d4 = orient(p1, p2, p4)
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return true
        if (abs(d1) < 1e-12 && onSeg(p3, p4, p1)) return true
        if (abs(d2) < 1e-12 && onSeg(p3, p4, p2)) return true
        if (abs(d3) < 1e-12 && onSeg(p1, p2, p3)) return true
        if (abs(d4) < 1e-12 && onSeg(p1, p2, p4)) return true
        return false
    }
}

/**
 * Versioned AR-to-CAD payload ("laserplan.ar-room", version 1) sent to the drawing engine.
 * Keeps the raw 3D corners, their source and time, the floor height, the room height and how it was obtained.
 */
object ArRoomPayload {
    const val SCHEMA = "laserplan.ar-room"
    const val VERSION = 1

    fun build(
        corners: List<Corner3>,
        floorY: Double,
        height: Double?,
        heightSource: String?,
        depthUsed: Boolean,
        trackingLosses: Int,
        captureTimeMs: Long,
    ): JSONObject {
        val plan = RoomGeometry.toPlan(corners)
        val r3 = { v: Double -> Math.round(v * 1000.0) / 1000.0 }
        return JSONObject().apply {
            put("type", "ar.room")
            put("schema", SCHEMA)
            put("version", VERSION)
            put("captureTime", captureTimeMs)
            put("frame", "ARCore world: metres, y up; plan x = world x, plan y = world z")
            put("floorY", r3(floorY))
            put("plan", JSONArray(plan.map { JSONObject().put("x", r3(it.x)).put("y", r3(it.y)) }))
            put("corners", JSONArray(corners.map {
                JSONObject().put("x", r3(it.x)).put("y", r3(it.y)).put("z", r3(it.z)).put("src", it.source).put("t", it.timeMs)
            }))
            if (height != null) { put("height", r3(height)); put("heightSource", heightSource ?: "ar") }
            put("depthUsed", depthUsed)
            put("trackingLosses", trackingLosses)
            put("metrics", JSONObject().apply {
                put("walls", JSONArray(RoomGeometry.wallLengths(plan).map(r3)))
                put("angles", JSONArray(RoomGeometry.interiorAngles(plan).map { Math.round(it * 10) / 10.0 }))
                put("area", r3(RoomGeometry.area(plan)))
                put("perimeter", r3(RoomGeometry.perimeter(plan)))
            })
            put("status", "AR_ESTIMATED")
        }
    }
}
