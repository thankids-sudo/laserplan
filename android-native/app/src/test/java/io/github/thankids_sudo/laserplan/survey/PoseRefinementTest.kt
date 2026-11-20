package io.github.thankids_sudo.laserplan.survey

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.cos
import kotlin.math.sin

class PoseRefinementTest {
    private fun yaw(deg: Double, tx: Float = 0f, ty: Float = 0f, tz: Float = 0f): Rigid {
        val h = Math.toRadians(deg) / 2
        return Rigid(floatArrayOf(0f, sin(h).toFloat(), 0f, cos(h).toFloat()), floatArrayOf(tx, ty, tz))
    }
    private fun applied(r: Rigid, x: Float, y: Float, z: Float) = FloatArray(3).also { r.apply(x, y, z, it, 0) }

    @Test fun inverseAndComposeUndoEachOther() {
        val a = yaw(30.0, 1f, 2f, 3f)
        val p = applied(a.compose(a.inverse()), 0.4f, -0.7f, 1.1f)
        assertEquals(0.4f, p[0], 1e-5f); assertEquals(-0.7f, p[1], 1e-5f); assertEquals(1.1f, p[2], 1e-5f)
        val q = applied(yaw(90.0), 1f, 0f, 0f)   // +90° about +Y turns +X into −Z
        assertEquals(0f, q[0], 1e-5f); assertEquals(-1f, q[2], 1e-5f)
    }

    @Test fun correctionMovesWhatWasAtTheOldPoseToTheNewPose() {
        val then = yaw(10.0, 2f, 0f, -1f); val now = yaw(12.0, 2.03f, 0f, -1.01f)
        val c = PoseRefinement.correction(then, now)
        // A point 1 m in front of the anchor, expressed in the anchor's frame, must land where `now` puts it.
        val wThen = applied(then, 0f, 0f, -1f); val wNow = applied(now, 0f, 0f, -1f)
        val moved = applied(c, wThen[0], wThen[1], wThen[2])
        for (i in 0..2) assertEquals(wNow[i], moved[i], 1e-4f)
        assertEquals(2.0, c.rotationDeg, 1e-2)
    }

    @Test fun framesAreInterpolatedBetweenAnchors() {
        val obs = listOf(
            PoseRefinement.Obs(1, Rigid.IDENTITY, yaw(0.0, 0f, 0f, 0f), true),
            PoseRefinement.Obs(9, Rigid.IDENTITY, yaw(0.0, 0.08f, 0f, 0f), true),
        )
        val r = PoseRefinement.solve(obs, listOf(1, 3, 5, 9, 12))
        assertEquals(0f, r.perFrame[1]!!.t[0], 1e-6f)
        assertEquals(0.04f, r.perFrame[5]!!.t[0], 1e-6f)          // halfway
        assertEquals(0.08f, r.perFrame[12]!!.t[0], 1e-6f)         // after the last anchor: its correction
        assertEquals(2, r.used)
    }

    @Test fun lostOrImplausibleAnchorsAreNotUsed() {
        val obs = listOf(
            PoseRefinement.Obs(1, Rigid.IDENTITY, null, false),
            PoseRefinement.Obs(5, Rigid.IDENTITY, yaw(0.0, 0.9f, 0f, 0f), true),   // 0.9 m jump: relocalisation error
            PoseRefinement.Obs(9, Rigid.IDENTITY, yaw(25.0), true),                // 25° turn: too large
        )
        val r = PoseRefinement.solve(obs, listOf(1, 5, 9))
        assertTrue(r.perFrame.isEmpty()); assertEquals(1, r.notTracking); assertEquals(2, r.rejected)
        val s = r.summary(3); assertEquals(0, s.getInt("framesCorrected")); assertEquals(3, s.getInt("anchors"))
    }

    @Test fun rotationInterpolationStaysUnitLength() {
        val m = Rigid.lerp(yaw(0.0), yaw(8.0), 0.5f)
        assertEquals(4.0, m.rotationDeg, 1e-3)
        assertEquals(1.0, m.q.sumOf { (it * it).toDouble() }, 1e-6)
    }

    @Test fun wallBandSkipsFloorAndCeiling() {
        val ys = FloatArray(1000) { i -> when { i < 200 -> -1.30f; i > 900 -> 1.40f; else -> -1.3f + 2.7f * (i - 200) / 700f } }
        val (lo, hi) = WallBand.of(ys)!!
        assertEquals(-1.10f, lo, 1e-3f); assertEquals(1.20f, hi, 1e-3f)
        assertNull(WallBand.of(FloatArray(500) { 0.01f * (it % 10) }))   // flat: no band
        assertNull(WallBand.of(FloatArray(10)))
    }
}

class RefinedRecorderTest {
    @Test fun anchorsMoveTheSavedCloud() {
        val dir = Files.createTempDirectory("survey").toFile()
        val rec = SurveyRecorder(dir, JSONObject(), JSONObject())
        val u16 = { vararg mm: Int -> ByteArray(mm.size * 2).also { b -> mm.forEachIndexed { i, v -> b[i * 2] = v.toByte(); b[i * 2 + 1] = (v shr 8).toByte() } } }
        assertTrue(rec.submit(Keyframe(u16(1000, 1200, 0, 0), null, 2, 2, Intrinsics(2.0, 2.0, 1.0, 1.0, 2, 2), Intrinsics(2.0, 2.0, 1.0, 1.0, 2, 2),
            floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f), floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f),
            5_000_000_000, 5_000_000_000, null, "not-requested")))
        // The anchor placed at keyframe 1 has since moved 1 m along +X in ARCore's refined map.
        val m = rec.finish("13.7", listOf(PoseRefinement.Obs(1, Rigid.IDENTITY, Rigid(floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(0.3f, 0f, 0f)), true)))
        val xs = File(dir, "cloud.xyzc").readLines().filter { !it.startsWith("#") }.map { it.split(" ")[0].toFloat() }.sorted()
        assertEquals(listOf(-0.2f, 0.3f), xs)                     // recorded at −0.5 and 0.0, both moved by +0.3
        assertEquals(1, m.getJSONObject("poseRefinement").getInt("framesCorrected"))
        assertEquals(300.0, m.getJSONObject("poseRefinement").getDouble("medianCorrectionMm"), 0.01)
        assertTrue(File(dir, "poses_refined.json").isFile)
        dir.deleteRecursively()
    }

    @Test fun withoutAnchorsTheCloudIsAsRecorded() {
        val dir = Files.createTempDirectory("survey").toFile()
        val rec = SurveyRecorder(dir, JSONObject(), JSONObject())
        val m = rec.finish("13.7")
        assertEquals("none (no anchors)", m.getJSONObject("poseRefinement").getString("method"))
        dir.deleteRecursively()
    }
}
