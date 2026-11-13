package io.github.thankids_sudo.laserplan.laser.diagnostics

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory diagnostic log (last 300 lines). Nothing leaves the phone unless the user taps Share.
 * Bluetooth addresses are replaced by a short pseudonym, so a shared log does not identify the device.
 */
object Diagnostics {
    private const val MAX = 300
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(msg: String) {
        lines.addFirst(fmt.format(Date()) + "  " + msg)
        while (lines.size > MAX) lines.removeLast()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()

    /** Stable pseudonym for a Bluetooth address or input-device descriptor, e.g. "dev-3f9a1c". */
    fun pseudo(id: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        return "dev-" + h.take(3).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** Text for sharing: pseudonyms only, no addresses. */
    fun report(header: String): String = buildString {
        appendLine("LaserPlan laser diagnostics")
        appendLine(header)
        appendLine("Addresses are pseudonymised. Raw packets are included to help add support for this meter.")
        appendLine()
        snapshot().reversed().forEach { appendLine(it) }
    }
}
