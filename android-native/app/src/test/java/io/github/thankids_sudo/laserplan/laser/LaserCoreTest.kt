package io.github.thankids_sudo.laserplan.laser

import io.github.thankids_sudo.laserplan.bridge.Bridge
import io.github.thankids_sudo.laserplan.bridge.WebCommand
import io.github.thankids_sudo.laserplan.laser.measurement.MeasurementNormalizer
import io.github.thankids_sudo.laserplan.laser.measurement.ReadingGate
import io.github.thankids_sudo.laserplan.laser.model.MeasurementType
import io.github.thankids_sudo.laserplan.laser.model.SupportStatus
import io.github.thankids_sudo.laserplan.laser.protocol.BoschMtProtocol
import io.github.thankids_sudo.laserplan.laser.protocol.LeicaDistoProtocol
import io.github.thankids_sudo.laserplan.laser.registry.CompatibilityRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

class BoschMtProtocolTest {
    // Synthetic frame built to the layout the web app has decoded since v8: C0 55 10 <mode> … float32 LE at offset 7.
    @Test fun decodesDistanceFrame() {
        val r = BoschMtProtocol.decode(hex("C0 55 10 06 00 00 00 93 3A 5D 40 00"))
        assertNotNull(r); assertEquals(3.4567, r!!.value, 1e-4); assertEquals("m", r.unit)
    }
    @Test fun rejectsOutOfRangeAndForeignFrames() {
        assertNull(BoschMtProtocol.decode(hex("C0 55 10 01 00 00 00 00 00 00 00 00")))      // 0 m in a non-06 mode
        assertNull(BoschMtProtocol.decode(hex("C0 55 02 01 00 1A")))                        // our own command echo
        assertNull(BoschMtProtocol.decode(hex("C0 55 10 06 00 00 00 00 00 C0 7F 00")))      // NaN
        assertNull(BoschMtProtocol.decode(ByteArray(0)))
    }
    @Test fun legacyFrame() {
        // 0x00 header, uint32 LE at offset 2 in 0.05 mm steps: 60000 → 3.0 m
        val r = BoschMtProtocol.decode(hex("00 00 60 EA 00 00"))
        assertEquals(3.0, r!!.value, 1e-9)
    }
    @Test fun autoSyncCommandUnchanged() =
        assertEquals("C0 55 02 01 00 1A", BoschMtProtocol.CMD_AUTO_SYNC.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) })
}

class LeicaDistoProtocolTest {
    // Values as received from a DISTO and published (base64) in a public integration write-up (blog.consol.de).
    @Test fun decodesPublishedSamples() {
        assertEquals(1.1724, LeicaDistoProtocol.decodeDistance(Base64.getDecoder().decode("NBGWPw=="))!!.value, 1e-4)
        assertEquals(1.1400, LeicaDistoProtocol.decodeDistance(Base64.getDecoder().decode("heuRPw=="))!!.value, 1e-4)
    }
    @Test fun rejectsShortOrInvalid() {
        assertNull(LeicaDistoProtocol.decodeDistance(byteArrayOf(1, 2)))
        assertNull(LeicaDistoProtocol.decodeDistance(hex("00 00 80 BF")))   // -1.0
    }
}

class MeasurementNormalizerTest {
    private fun m(s: String, unit: String = "m") = MeasurementNormalizer.parse(s, unit)?.let { MeasurementNormalizer.toMetres(it.value, it.unit) }

    @Test fun metricForms() {
        assertEquals(1.234, m("1.234 m")!!, 1e-9)
        assertEquals(1.234, m("1,234m")!!, 1e-9)
        assertEquals(1.234, m("1234 mm")!!, 1e-9)
        assertEquals(1.234, m("123.4cm")!!, 1e-9)
        assertEquals(1234.5, m("1,234.5")!!, 1e-9)
        assertEquals(2.5, m("2.5\r\n")!!, 1e-9)
    }
    @Test fun bareNumberUsesChosenUnit() {
        assertEquals(1.5, m("1500", "mm")!!, 1e-9)
        assertEquals(0.3048, m("1", "ft")!!, 1e-9)
    }
    @Test fun imperialForms() {
        assertEquals((12 * 12 + 3.5) * 0.0254, m("12' 3 1/2\"")!!, 1e-9)
        assertEquals(4.5 * 0.3048, m("4.5ft")!!, 1e-9)
        assertEquals(3.5 * 0.0254, m("3 1/2 in")!!, 1e-9)
        assertEquals(10 * 0.0254, m("10in")!!, 1e-9)
    }
    @Test fun nonDistanceAndJunk() {
        val area = MeasurementNormalizer.parse("12.5 m2")
        assertEquals(MeasurementType.AREA, area!!.type)
        assertNull(MeasurementNormalizer.toMetres(area.value, area.unit))
        assertNull(MeasurementNormalizer.parse("hello"))
        assertNull(MeasurementNormalizer.parse("5 parsecs"))
        assertNull(MeasurementNormalizer.parse("1/0 in"))
        assertNull(MeasurementNormalizer.toMetres(Double.NaN, "m"))
    }
}

class ReadingGateTest {
    @Test fun dropsDuplicateNotificationOnly() {
        val g = ReadingGate()
        assertNull(g.check(3.0, 1000))
        assertEquals("duplicate notification", g.check(3.0, 1200))
        assertNull(g.check(3.0, 1700))      // same value measured again later is a real reading
        assertNull(g.check(3.1, 1750))
    }
    @Test fun dropsReplayAfterReconnectOnce() {
        val g = ReadingGate()
        assertNull(g.check(2.5, 0))
        g.onReconnected(10_000)
        assertEquals("replayed after reconnect", g.check(2.5, 10_300))
        assertNull(g.check(2.5, 11_000))     // pressed again deliberately → accepted
        g.onReconnected(20_000)
        assertNull(g.check(4.0, 20_100))     // a new value after reconnect is accepted
    }
}

class BridgeTest {
    @Test fun allowListAndValidation() {
        assertEquals(WebCommand.Hello, Bridge.parse("""{"cmd":"hello","v":13}"""))
        assertEquals(WebCommand.LaserOpen, Bridge.parse("""{"cmd":"laser.open"}"""))
        assertNull(Bridge.parse("""{"cmd":"exec","js":"x"}"""))
        assertNull(Bridge.parse("not json"))
        val s = Bridge.parse("""{"cmd":"save","name":"LaserPlan – Room 1.xlsx","mime":"application/vnd.ms-excel","b64":"QUJD"}""") as WebCommand.Save
        assertEquals("LaserPlan _ Room 1.xlsx", s.name)
        assertNull(Bridge.parse("""{"cmd":"save","name":"a.json","mime":"bad mime!","b64":"QUJD"}"""))
        assertNull(Bridge.parse("""{"cmd":"save","name":"a.json","mime":"application/json","b64":""}"""))
    }
}

class CompatibilityRegistryTest {
    @Test fun bundledListParsesAndIsHonest() {
        val f = listOf("src/main/assets/compatibility/devices.json", "app/src/main/assets/compatibility/devices.json").map(::File).first { it.isFile }
        val list = CompatibilityRegistry.parse(f.readText())
        assertTrue(list.size >= 10)
        // Nothing may claim VERIFIED until it has been tested with the physical meter in the native app.
        assertTrue(list.none { it.status == SupportStatus.VERIFIED })
        assertTrue(list.any { it.brand == "Bosch" && it.adapter == "bosch-mt" })
        assertTrue(list.any { it.brand == "Leica" && it.adapter == "leica-disto" })
    }
}
