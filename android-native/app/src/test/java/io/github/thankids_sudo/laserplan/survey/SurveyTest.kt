package io.github.thankids_sudo.laserplan.survey

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

private fun u16(vararg mm: Int): ByteArray = ByteArray(mm.size * 2).also { b -> mm.forEachIndexed { i, v -> b[i * 2] = (v and 0xFF).toByte(); b[i * 2 + 1] = (v shr 8).toByte() } }
private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

class DepthMathTest {
    private val k = Intrinsics(fx = 100.0, fy = 100.0, cx = 2.0, cy = 1.0, width = 4, height = 2)

    @Test fun unprojectUsesOpticalAxisDepthAndArcoreSigns() {
        val p = DepthMath.unproject(4.0, 0.0, 2.0, k)            // right of and above the principal point
        assertEquals(0.04, p[0], 1e-12)                            // (u − cx)·z/fx
        assertEquals(0.02, p[1], 1e-12)                            // +Y up because image v grows downward
        assertEquals(-2.0, p[2], 1e-12)                            // camera looks along −Z; z is optical-axis depth, not range
        val c = DepthMath.unproject(k.cx, k.cy, 1.5, k)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, -1.5), c, 1e-12)
    }

    @Test fun intrinsicsScaleWithResolution() {
        val tex = Intrinsics(1440.0, 1440.0, 960.0, 540.0, 1920, 1080)
        val d = tex.scaledTo(160, 90)
        assertEquals(120.0, d.fx, 1e-9); assertEquals(120.0, d.fy, 1e-9)
        assertEquals(80.0, d.cx, 1e-9); assertEquals(45.0, d.cy, 1e-9)
        assertEquals(160, d.width); assertEquals(90, d.height)
    }

    @Test fun worldTransformAppliesRotationAndTranslation() {
        // Camera at (1, 1.5, 2), turned 90° left about +Y (column-major): camera −Z points to world −X.
        val m = floatArrayOf(0f, 0f, -1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 1.5f, 2f, 1f)
        val w = DepthMath.transform(m, doubleArrayOf(0.0, 0.0, -3.0))
        assertArrayEquals(doubleArrayOf(-2.0, 1.5, 2.0), w, 1e-9)
    }

    @Test fun stridedPlanesAreCompacted() {
        // 2×2 u16 image, pixelStride 2, rowStride 6 (2 padding bytes per row)
        val src = byteArrayOf(0x10, 0x27, 0x20, 0x4E, 9, 9, 0x30, 0x75, 0x00, 0x00, 9, 9)
        val tight = DepthMath.compact16(src, rowStride = 6, pixelStride = 2, w = 2, h = 2)
        assertEquals(10000, DepthMath.depthMm(tight, 2, 0, 0))
        assertEquals(20000, DepthMath.depthMm(tight, 2, 1, 0))
        assertEquals(30000, DepthMath.depthMm(tight, 2, 0, 1))
        assertEquals(0, DepthMath.depthMm(tight, 2, 1, 1))
        val c = DepthMath.compact8(byteArrayOf(1, 7, 2, 7, 3, 7, 4, 7), rowStride = 4, pixelStride = 2, w = 2, h = 2)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), c)
    }

    @Test fun rejectsInvalidLowConfidenceFarAndKeepsGood() {
        val depth = u16(0, 1500, 1500, 9000, 2000, 2000, 2000, 2000)       // 4×2
        val conf = byteArrayOf(-1, 50, -1, -1, -1, -1, -1, -128)            // 255,50,255,255,255,255,255,128
        val st = PixelStats()
        val pts = DepthMath.framePoints(depth, conf, 4, 2, k, IDENTITY, st)
        // Default cut-off is 200: the pixel with confidence 128 is now rejected too.
        assertEquals(8, st.total); assertEquals(1, st.invalid); assertEquals(2, st.lowConfidence); assertEquals(1, st.outOfRange)
        assertEquals(4, st.accepted); assertEquals(4 * 4, pts.size)
        for (i in 0 until 4) { assertTrue(pts[i * 4 + 2] < 0); assertTrue(pts[i * 4 + 3] >= 200 / 255f) }
        val st128 = PixelStats()
        DepthMath.framePoints(depth, conf, 4, 2, k, IDENTITY, st128, minConfidence = 128)
        assertEquals(5, st128.accepted)
    }

    @Test fun noConfidenceImageMeansNoConfidenceFilter() {
        val st = PixelStats()
        DepthMath.framePoints(u16(1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000), null, 4, 2, k, IDENTITY, st)
        assertEquals(8, st.accepted)
    }

    @Test fun timestampsMustMatch() {
        assertTrue(DepthMath.isSynced(1_000_000_000, 1_000_000_000))
        assertTrue(DepthMath.isSynced(1_000_000_000, 1_040_000_000))
        assertFalse(DepthMath.isSynced(1_000_000_000, 1_060_000_000))
        assertFalse(DepthMath.isSynced(0, 1_000_000_000))
    }
}

class VoxelGridTest {
    @Test fun averagesWithinAVoxelAndCaps() {
        val g = VoxelGrid(voxel = 0.02, maxVoxels = 3)
        g.add(0.001f, 0.001f, 0.001f, 1f); g.add(0.009f, 0.009f, 0.009f, 0.5f)   // same voxel
        g.add(0.5f, 0f, 0f, 1f); g.add(-0.5f, 0f, 0f, 1f); g.add(2f, 2f, 2f, 1f)   // last one exceeds the cap
        assertEquals(3, g.size); assertEquals(1, g.dropped)
        val p = g.points()
        assertEquals(0.005f, p[0], 1e-6f); assertEquals(0.75f, p[3], 1e-6f)
    }

    @Test fun negativeCoordinatesDoNotCollide() {
        val g = VoxelGrid(voxel = 0.02)
        g.add(-0.001f, 0f, 0f, 1f); g.add(0.001f, 0f, 0f, 1f)
        assertEquals(2, g.size)
    }

    @Test fun previewIsCappedAndDeterministic() {
        val g = VoxelGrid(voxel = 0.01)
        for (i in 0 until 1000) g.add(i * 0.05f, 0f, 0f, 1f)
        assertEquals(100 * 4, g.sample(100).first.size)
        assertEquals(100, g.sample(100).second.size)
        assertArrayEquals(g.sample(100).first, g.sample(100).first, 0f)
        assertEquals(1000 * 4, g.sample(5000).first.size)
    }
}

class PoseHistoryTest {
    private fun rec(ts: Long, x: Float) = PoseHistory.Rec(ts, IDENTITY.copyOf().also { it[12] = x }, floatArrayOf(x, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f))

    @Test fun findsThePoseOfTheDepthFrame() {
        val h = PoseHistory()
        for (i in 0 until 60) h.add(rec(1_000_000_000L + i * 33_333_333L, i.toFloat()))   // 30 fps for 2 s
        // Depth from 97 ms (≈ 3 frames) ago, as in the first S24 Ultra scan
        val depthTs = 1_000_000_000L + 56 * 33_333_333L
        assertEquals(56f, h.find(depthTs)!!.matrix[12])
        assertNull(h.find(depthTs + 10_000_000L))            // 10 ms off any frame → no guess
        assertEquals(56f, h.find(depthTs + 2_000_000L)!!.matrix[12])   // field data: depth stamps sit ~2.0 ms from their frame
    }

    @Test fun dropsOldAndOutOfOrderPoses() {
        val h = PoseHistory(keepNs = 1_000_000_000L)
        h.add(rec(0L, 0f)); h.add(rec(500_000_000L, 1f)); h.add(rec(400_000_000L, 9f))   // out of order: ignored
        assertEquals(2, h.size)
        h.add(rec(1_400_000_000L, 2f))                                                       // first is now 1.4 s old: trimmed
        assertNull(h.find(0L)); assertEquals(1f, h.find(500_000_000L)!!.matrix[12])
        h.clear(); assertEquals(0, h.size)
    }
}

class KeyframePolicyTest {
    @Test fun oneSecondApartAndMax90() {
        val p = KeyframePolicy(maxIntervalMs = 1000, max = 90)
        assertTrue(p.shouldCapture(10_000)); p.captured(10_000)
        assertFalse(p.shouldCapture(10_500))
        assertTrue(p.shouldCapture(11_000))
        var t = 11_000L
        while (!p.full) { p.captured(t); t += 1000 }
        assertEquals(90, p.taken)
        assertFalse(p.shouldCapture(t + 5000))
    }

    @Test fun waitsAfterTrackingReturns() {
        val p = KeyframePolicy()
        p.onTrackingRegained(20_000)
        assertFalse(p.shouldCapture(20_300))
        assertTrue(p.shouldCapture(20_000 + SurveyConfig.RESUME_SETTLE_MS))
    }
}

class SurveyPackageTest {
    private fun keyframe(depthTs: Long, camTs: Long) = Keyframe(
        depth = u16(1000, 1200, 0, 1400), confidence = byteArrayOf(-1, -1, -1, 10), width = 2, height = 2,
        depthIntrinsics = Intrinsics(2.0, 2.0, 1.0, 1.0, 2, 2), cameraIntrinsics = Intrinsics(1440.0, 1440.0, 960.0, 540.0, 1920, 1080),
        pose = IDENTITY, poseTranslation = floatArrayOf(0f, 0f, 0f), poseQuaternion = floatArrayOf(0f, 0f, 0f, 1f),
        depthTimestampNs = depthTs, cameraTimestampNs = camTs, rgbNv21 = null, rgbStatus = "not-requested",
    )

    @Test fun zipHasTheDocumentedLayoutAndMetadata() {
        val dir = Files.createTempDirectory("survey").toFile()
        val rec = SurveyRecorder(dir, JSONObject().put("model", "test"), JSONObject().put("rawDepthSupported", true))
        assertTrue(rec.submit(keyframe(5_000_000_000, 5_000_000_000)))
        Thread.sleep(50)
        assertTrue(rec.submit(keyframe(6_000_000_000, 6_200_000_000)))   // depth 200 ms off the pose → kept but invalid
        val m = rec.finish("13.2")
        assertFalse(rec.submit(keyframe(7_000_000_000, 7_000_000_000)))  // nothing accepted after Stop

        assertEquals("laserplan.ar-survey", m.getString("schema")); assertEquals(1, m.getInt("version"))
        assertEquals(2, m.getInt("frameCount")); assertEquals(1, m.getInt("validFrameCount"))
        assertEquals(2, m.getInt("pointCount"))   // 0 depth and low confidence rejected; only the valid frame counted
        assertEquals("metre", m.getJSONObject("units").getString("length"))
        assertEquals(SurveyConfig.MAX_KEYFRAMES, m.getJSONObject("settings").getInt("maxKeyframes"))

        val bos = ByteArrayOutputStream()
        SurveyRecorder.zip(dir, bos)
        val names = mutableListOf<String>(); var manifestText = ""
        ZipInputStream(bos.toByteArray().inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; names += e.name; if (e.name == "manifest.json") manifestText = z.readBytes().decodeToString() }
        }
        assertEquals("manifest.json", names.first())
        assertTrue(names.containsAll(listOf("frames/0001.json", "frames/0002.json", "depth/0001.raw16le", "depth/0002.raw16le",
            "confidence/0001.raw8", "cloud.xyzc")))
        assertFalse(names.contains("preview.bin"))
        assertFalse(names.contains("preview5.bin")); assertTrue(names.contains("cloud.ply"))
        assertEquals(2, JSONObject(manifestText).getInt("frameCount"))

        val f2 = JSONObject(File(dir, "frames/0002.json").readText())
        assertFalse(f2.getBoolean("valid")); assertTrue(f2.has("invalidReason"))
        assertEquals(-200_000_000L, f2.getLong("depthToCameraDeltaNs"))
        assertEquals("not-requested", f2.getJSONObject("rgb").getString("status"))
        val f1 = JSONObject(File(dir, "frames/0001.json").readText())
        assertEquals(2, f1.getJSONObject("pixels").getInt("accepted"))
        assertEquals(8, File(dir, "depth/0001.raw16le").length())
        val cloudLines = File(dir, "cloud.xyzc").readLines().filter { !it.startsWith("#") }
        assertEquals(2, cloudLines.size)
        val (prev, prevCol) = SurveyRecorder.readPreviewAny(dir)
        assertEquals(2 * 4, prev.size); assertEquals(2, prevCol!!.size)
        val ply = File(dir, "cloud.ply").readLines()
        assertTrue(ply.contains("element vertex 2")); assertEquals(2, ply.size - ply.indexOf("end_header") - 1)
        assertEquals(2, m.getInt("pointsBeforeStrayFilter"))   // tiny cloud: stray filter not applied
        dir.deleteRecursively()
    }

    @Test fun pruneKeepsNewestSessions() {
        val root = Files.createTempDirectory("surveys").toFile()
        listOf("20261101-100000", "20261102-100000", "20261103-100000", "20261104-100000").forEach { File(root, it).mkdirs() }
        SurveyRecorder.prune(root, keep = 2)
        assertEquals(listOf("20261103-100000", "20261104-100000"), root.list()!!.sorted())
        root.deleteRecursively()
    }

    @Test fun missingPreviewIsEmpty() {
        assertEquals(0, SurveyRecorder.readPreview(File("does-not-exist.bin")).size)
        assertNull(null)
    }
}


class PhaseBTest {
    private val k = Intrinsics(fx = 100.0, fy = 100.0, cx = 50.0, cy = 40.0, width = 100, height = 80)

    private fun nv21(w: Int, h: Int, y: Int, u: Int, v: Int) = ByteArray(w * h * 3 / 2).also { b ->
        for (i in 0 until w * h) b[i] = y.toByte()
        var o = w * h; while (o < b.size) { b[o] = v.toByte(); b[o + 1] = u.toByte(); o += 2 }
    }

    @Test fun nv21ColourConversion() {
        assertEquals(0x808080, ColorSampler.nv21Rgb(nv21(4, 4, 128, 128, 128), 4, 4, 1, 1))
        val red = ColorSampler.nv21Rgb(nv21(4, 4, 76, 85, 255), 4, 4, 2, 2)   // BT.601 red ≈ (255, 0, 0)
        assertTrue((red shr 16) and 0xFF > 240); assertTrue((red shr 8) and 0xFF < 15); assertTrue(red and 0xFF < 15)
    }

    @Test fun pointsProjectIntoTheRightPixel() {
        val img = nv21(100, 80, 200, 128, 128)
        img[40 * 100 + 60] = 10   // a dark pixel at (u=60, v=40)
        val s = ColorSampler(img, 100, 80, k, IDENTITY)
        // camera at origin looking down −Z: (0.2, 0, −2) projects to u = 100·0.2/2 + 50 = 60, v = 40
        assertEquals(0x0A0A0A, s.colourAt(0.2f, 0f, -2f))
        assertEquals(0xC8C8C8, s.colourAt(0f, 0f, -2f))
        assertEquals(VoxelGrid.NO_COLOUR, s.colourAt(0f, 0f, 2f))       // behind the camera
        assertEquals(VoxelGrid.NO_COLOUR, s.colourAt(5f, 0f, -1f))       // outside the image
    }

    @Test fun samplerUsesItsOwnPose() {
        // Camera moved to x = 1: the same world point now projects 50 px further left.
        val moved = IDENTITY.copyOf().also { it[12] = 1f }
        val img = nv21(100, 80, 200, 128, 128); img[40 * 100 + 10] = 10
        assertEquals(0x0A0A0A, ColorSampler(img, 100, 80, k, moved).colourAt(0.2f, 0f, -2f))   // u = 100·(−0.8)/2 + 50 = 10
    }

    @Test fun voxelColoursAverageAndUncolouredStayUnknown() {
        val g = VoxelGrid(voxel = 0.02)
        g.add(0.001f, 0f, 0f, 1f, 0x000000); g.add(0.002f, 0f, 0f, 1f, 0xFEFEFE); g.add(0.5f, 0f, 0f, 1f)
        assertEquals(0x7F7F7F, g.colours()[0])
        assertEquals(VoxelGrid.NO_COLOUR, g.colours()[1])
    }

    @Test fun strayPointsAreRemovedFromDenseClouds() {
        val g = VoxelGrid(voxel = 0.02)
        for (x in 0 until 10) for (y in 0 until 10) for (z in 0 until 10) g.add(x * 0.02f + 0.01f, y * 0.02f + 0.01f, z * 0.02f + 0.01f, 1f)   // 1000-voxel cube
        g.add(3f, 3f, 3f, 1f)   // lone stray point
        val keep = g.keepMask()
        assertEquals(1001, keep.size)
        assertFalse(keep[1000])
        assertEquals(1000, keep.count { it })
    }

    @Test fun keyframesFollowMovementNotTheClock() {
        val p = KeyframePolicy(minIntervalMs = 250, maxIntervalMs = 1500)
        val t0 = floatArrayOf(0f, 0f, 0f); val q0 = floatArrayOf(0f, 0f, 0f, 1f)
        assertTrue(p.shouldCapture(10_000, t0, q0)); p.captured(10_000, t0, q0)
        assertFalse(p.shouldCapture(10_200, floatArrayOf(0.5f, 0f, 0f), q0))                  // moved, but < 250 ms
        assertTrue(p.shouldCapture(10_300, floatArrayOf(0.12f, 0f, 0f), q0))                  // moved 12 cm
        assertFalse(p.shouldCapture(10_300, floatArrayOf(0.05f, 0f, 0f), q0))                 // 5 cm: not yet
        val turn15 = floatArrayOf(0f, Math.sin(Math.toRadians(7.5)).toFloat(), 0f, Math.cos(Math.toRadians(7.5)).toFloat())
        assertTrue(p.shouldCapture(10_300, t0, turn15))                                       // turned 15°
        assertEquals(15.0, KeyframePolicy.turnDeg(turn15, q0), 1e-3)
        assertTrue(p.shouldCapture(11_500, t0, q0))                                           // standing still: every 1.5 s
    }
}
