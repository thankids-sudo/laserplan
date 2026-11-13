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
        assertEquals(8, st.total); assertEquals(1, st.invalid); assertEquals(1, st.lowConfidence); assertEquals(1, st.outOfRange)
        assertEquals(5, st.accepted); assertEquals(5 * 4, pts.size)
        for (i in 0 until 5) { assertTrue(pts[i * 4 + 2] < 0); assertTrue(pts[i * 4 + 3] in 0.5f..1f) }
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
        assertEquals(100 * 4, g.sample(100).size)
        assertArrayEquals(g.sample(100), g.sample(100), 0f)
        assertEquals(1000 * 4, g.sample(5000).size)
    }
}

class KeyframePolicyTest {
    @Test fun oneSecondApartAndMax90() {
        val p = KeyframePolicy(intervalMs = 1000, max = 90)
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
        assertEquals(2 * 4, SurveyRecorder.readPreview(File(dir, "preview.bin")).size)
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
