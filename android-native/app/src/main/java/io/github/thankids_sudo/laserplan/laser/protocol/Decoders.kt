package io.github.thankids_sudo.laserplan.laser.protocol

import io.github.thankids_sudo.laserplan.laser.model.RawReading
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
private fun ByteArray.f32le(off: Int): Float = ByteBuffer.wrap(this, off, 4).order(ByteOrder.LITTLE_ENDIAN).float
private fun ByteArray.u32le(off: Int): Long = ByteBuffer.wrap(this, off, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

/**
 * Bosch "MT protocol" over BLE, as used by the LaserPlan web app since v8 for the GLM 50-27 CG
 * (service 02a6c0d0-…, one characteristic for commands and notifications). Ported unchanged from index.html.
 */
object BoschMtProtocol {
    val SERVICE: UUID = UUID.fromString("02a6c0d0-0451-4000-b000-fb3210111989")
    val CHARACTERISTIC: UUID = UUID.fromString("02a6c0d1-0451-4000-b000-fb3210111989")

    /** Auto-sync: the meter pushes every measurement it takes. */
    val CMD_AUTO_SYNC = byteArrayOf(0xC0.toByte(), 0x55, 0x02, 0x01, 0x00, 0x1A)

    fun decode(b: ByteArray): RawReading? {
        if (b.size >= 11 && b.u8(0) == 0xC0 && b.u8(1) == 0x55 && b.u8(2) == 0x10) {
            val d = b.f32le(7).toDouble()
            val min = if (b.u8(3) == 0x06) 0.0 else 0.02
            return if (d.isFinite() && d > min && d < 300) RawReading(d, "m") else null
        }
        if (b.size >= 6 && b.u8(0) == 0x00) {
            val v = b.u32le(2) * 0.00005
            if (v > 0 && v < 300) return RawReading(v, "m")
        }
        return null
    }
}

/**
 * Leica DISTO BLE distance service. The distance characteristic carries a float32 little-endian value in metres
 * regardless of the unit on the display, according to public third-party integrations (not yet checked against
 * Leica's own SDK documentation). Status: PROTOCOL_SUPPORTED until tested with a physical DISTO.
 */
object LeicaDistoProtocol {
    val SERVICE: UUID = UUID.fromString("3ab10100-f831-4395-b29d-570977d5bf94")
    val DISTANCE: UUID = UUID.fromString("3ab10101-f831-4395-b29d-570977d5bf94")
    val DISTANCE_UNIT: UUID = UUID.fromString("3ab10102-f831-4395-b29d-570977d5bf94")

    fun decodeDistance(b: ByteArray): RawReading? {
        if (b.size < 4) return null
        val d = b.f32le(0).toDouble()
        return if (d.isFinite() && d > 0 && d < 500) RawReading(d, "m") else null
    }
}
