package io.github.thankids_sudo.laserplan.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomGeometryTest {
    private val rect = listOf(P2(0.0, 0.0), P2(4.2, 0.0), P2(4.2, 3.6), P2(0.0, 3.6))
    private val lShape = listOf(P2(0.0, 0.0), P2(5.0, 0.0), P2(5.0, 2.0), P2(2.0, 2.0), P2(2.0, 4.0), P2(0.0, 4.0))

    @Test fun rectangleMetrics() {
        assertEquals(listOf(4.2, 3.6, 4.2, 3.6), RoomGeometry.wallLengths(rect).map { Math.round(it * 1e6) / 1e6 })
        assertEquals(15.12, RoomGeometry.area(rect), 1e-9)
        assertEquals(15.6, RoomGeometry.perimeter(rect), 1e-9)
        RoomGeometry.interiorAngles(rect).forEach { assertEquals(90.0, it, 1e-9) }
        val diag = RoomGeometry.diagonals(rect)
        assertEquals(2, diag.size)
        diag.forEach { assertEquals(Math.hypot(4.2, 3.6), it.third, 1e-9) }
    }

    @Test fun windingDoesNotMatter() {
        val cw = rect.reversed()
        assertEquals(15.12, RoomGeometry.area(cw), 1e-9)
        RoomGeometry.interiorAngles(cw).forEach { assertEquals(90.0, it, 1e-9) }
    }

    @Test fun lShapeHasOneReflexCorner() {
        assertEquals(14.0, RoomGeometry.area(lShape), 1e-9)
        val a = RoomGeometry.interiorAngles(lShape)
        assertEquals(1, a.count { Math.abs(it - 270.0) < 1e-6 })
        assertEquals(5, a.count { Math.abs(it - 90.0) < 1e-6 })
        assertEquals(720.0, a.sum(), 1e-6)          // (n − 2) × 180 for n = 6
    }

    @Test fun skewedCornerKept() {
        val skew = listOf(P2(0.0, 0.0), P2(4.0, 0.0), P2(4.1, 3.0), P2(0.0, 3.0))
        val a = RoomGeometry.interiorAngles(skew)
        assertEquals(360.0, a.sum(), 1e-6)
        assertTrue(a.any { Math.abs(it - 90.0) > 1.0 })
    }

    @Test fun invalidOutlines() {
        val bowTie = listOf(P2(0.0, 0.0), P2(4.0, 3.0), P2(4.0, 0.0), P2(0.0, 3.0))
        assertTrue(RoomGeometry.selfIntersects(bowTie))
        assertFalse(RoomGeometry.selfIntersects(rect))
        assertFalse(RoomGeometry.selfIntersects(lShape))
        assertTrue(RoomGeometry.problems(bowTie).any { it.contains("cross") })
        assertTrue(RoomGeometry.problems(rect.take(2)).isNotEmpty())
        assertTrue(RoomGeometry.problems(listOf(P2(0.0, 0.0), P2(0.02, 0.0), P2(3.0, 3.0))).any { it.contains("5 cm") })
        assertTrue(RoomGeometry.problems(rect).isEmpty())
    }

    @Test fun payloadIsVersionedAndKeepsProvenance() {
        val c = listOf(
            Corner3(0.0, -1.40, 0.0, "plane", 1L), Corner3(4.2, -1.41, 0.0, "plane", 2L),
            Corner3(4.2, -1.40, 3.6, "depth", 3L), Corner3(0.0, -1.39, 3.6, "plane", 4L),
        )
        val j = ArRoomPayload.build(c, -1.40, 2.95, "ar-ceiling-plane", true, 1, 99L)
        assertEquals("ar.room", j.getString("type"))
        assertEquals("laserplan.ar-room", j.getString("schema"))
        assertEquals(1, j.getInt("version"))
        assertEquals(4, j.getJSONArray("plan").length())
        assertEquals(4.2, j.getJSONArray("plan").getJSONObject(1).getDouble("x"), 1e-9)
        assertEquals("depth", j.getJSONArray("corners").getJSONObject(2).getString("src"))
        assertEquals(2.95, j.getDouble("height"), 1e-9)
        assertEquals("ar-ceiling-plane", j.getString("heightSource"))
        assertEquals(15.12, j.getJSONObject("metrics").getDouble("area"), 1e-9)
        assertEquals("AR_ESTIMATED", j.getString("status"))
        val noHeight = ArRoomPayload.build(c, -1.40, null, null, false, 0, 99L)
        assertFalse(noHeight.has("height"))
    }
}
