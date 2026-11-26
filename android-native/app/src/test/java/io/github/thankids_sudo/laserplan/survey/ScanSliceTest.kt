package io.github.thankids_sudo.laserplan.survey

import io.github.thankids_sudo.laserplan.bridge.Bridge
import io.github.thankids_sudo.laserplan.bridge.WebCommand
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ScanSliceTest {
    /** A 4 × 3 m box room from y = 0 (floor) to y = 2.8 (ceiling): walls, floor and ceiling sampled every 2 cm. */
    private fun box(): FloatArray { val v = ArrayList<Float>()
        fun add(x: Double, y: Double, z: Double) { v.add(x.toFloat()); v.add(y.toFloat()); v.add(z.toFloat()) }
        var y = 0.0; while (y <= 2.8) { var s = 0.0; while (s <= 4.0) { add(s, y, 0.0); add(s, y, 3.0); s += 0.02 }; s = 0.0; while (s <= 3.0) { add(0.0, y, s); add(4.0, y, s); s += 0.02 }; y += 0.05 }
        var x = 0.0; while (x <= 4.0) { var z = 0.0; while (z <= 3.0) { add(x, 0.0, z); add(x, 2.8, z); z += 0.02 }; x += 0.02 }
        return v.toFloatArray() }

    @Test fun sliceKeepsWallsAndDropsFloorAndCeiling() {
        val s = ScanSlice.fromPoints(box())!!
        assertEquals(0.0, s.floor, 0.01); assertEquals(2.8, s.ceil, 0.01)
        // every cell lies on a wall line (x = 0 or 4, or z = 0 or 3), none in the middle of the room
        for (k in 0 until s.cells) { val x = s.xzMm[2 * k] / 1000.0; val z = s.xzMm[2 * k + 1] / 1000.0
            val onWall = minOf(Math.abs(x), Math.abs(x - 4.0), Math.abs(z), Math.abs(z - 3.0)) < 0.03
            assertTrue("cell $x,$z is not on a wall", onWall) }
        assertTrue(s.cells in 400..600)   // perimeter 14 m / 3 cm ≈ 467 cells
    }

    @Test fun cellsAreCappedEvenly() {
        val s = ScanSlice.fromPoints(box(), maxCells = 100)!!
        assertTrue(s.cells <= 100); assertTrue(s.cells >= 80)
    }

    @Test fun flatCloudsUseEveryPointAndTinyCloudsNone() {
        val flat = FloatArray(300) { if (it % 3 == 1) 0f else (it / 3) * 0.05f }
        assertNotNull(ScanSlice.fromPoints(flat)); assertNull(ScanSlice.fromPoints(FloatArray(30)))
    }

    @Test fun readsTheProcessedCloudAndBuildsTheMessage() {
        val dir = Files.createTempDirectory("slice").toFile(); val b = box()
        File(dir, "cloud.xyzc").bufferedWriter().use { w -> w.write("# header\n# second\n"); for (i in 0 until b.size / 3) w.write("${b[3 * i]} ${b[3 * i + 1]} ${b[3 * i + 2]} 0.9\n") }
        val s = ScanSlice.fromDir(dir)!!; val m = JSONObject(ScanSlice.message("p1", "s1", s))
        assertEquals("scan.slice", m.getString("type")); assertEquals("s1", m.getString("scanId"))
        assertEquals(s.cells * 2, m.getJSONArray("pts").length()); assertEquals(0.03, m.getDouble("cell"), 1e-9)
        dir.deleteRecursively()
    }

    @Test fun bridgeAcceptsSliceRequestsWithSafeIdsOnly() {
        assertEquals(WebCommand.ScanSlice("p_1", "s-2"), Bridge.parse("""{"cmd":"scan.slice","projectId":"p_1","scanId":"s-2"}"""))
        assertNull(Bridge.parse("""{"cmd":"scan.slice","projectId":"../x","scanId":"s"}"""))
        assertNull(Bridge.parse("""{"cmd":"scan.slice","projectId":"p"}"""))
    }
}
