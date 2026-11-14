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

    /**
     * Frame checksum: CRC-8, polynomial 0xA6, initial value 0xAA, no reflection. Derived from five captured frames
     * (field log 14 Nov 2026 + published examples): C0 11 00→3A, C0 55 02 01 00→1A, C0 40 00→EE, 00 04 4A 86 00 00→16,
     * and the 18-byte status reply →84.
     */
    fun crc8(b: ByteArray, len: Int = b.size): Int {
        var c = 0xAA
        for (i in 0 until len) {
            c = c xor (b[i].toInt() and 0xFF)
            repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0xA6) and 0xFF else (c shl 1) and 0xFF }
        }
        return c
    }

    /** "OK, no data" reply: status 00, length 00, checksum. */
    val ACK = byteArrayOf(0x00, 0x00, 0x82.toByte())

    /**
     * A frame the METER sends as a request (C0 <cmd> <len> <payload…> <crc>) with a valid checksum. The GLM 50-27 CG
     * repeats C0 11 00 3A every ~1.6 s until it gets a reply, and sends no distance meanwhile (field log 14 Nov 2026).
     */
    fun needsAck(b: ByteArray): Boolean =
        b.size >= 4 && b.u8(0) == 0xC0 && b.size == b.u8(2) + 4 && crc8(b, b.size - 1) == b.u8(b.size - 1)

    fun decode(b: ByteArray): RawReading? {
        if (b.size >= 11 && b.u8(0) == 0xC0 && b.u8(1) == 0x55 && b.u8(2) == 0x10) {
            val d = b.f32le(7).toDouble()
            val min = if (b.u8(3) == 0x06) 0.0 else 0.02
            return if (d.isFinite() && d > min && d < 300) RawReading(d, "m") else null
        }
        // Reply to a remote-measure request: status 00, length 04, uint32 LE in 0.05 mm, checksum (7 bytes).
        if (b.size == 7 && b.u8(0) == 0x00 && b.u8(1) == 0x04) {
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
