package io.github.thankids_sudo.laserplan.laser.measurement

import kotlin.math.abs

/**
 * Drops readings that must not reach the drawing: repeated notifications of the same value (some meters send a
 * reading twice), and a value replayed by the meter right after a reconnect. Pure logic, unit-tested.
 * Target arming, stale-target and lock rules stay in the drawing engine's laser session.
 */
class ReadingGate(
    private val duplicateWindowMs: Long = 400,
    private val replayWindowMs: Long = 1500,
) {
    private var lastValue: Double? = null
    private var lastAt: Long = Long.MIN_VALUE
    private var replayValue: Double? = null
    private var replayUntil: Long = Long.MIN_VALUE

    /** Call when a link is re-established: an identical value arriving shortly afterwards is treated as a replay. */
    fun onReconnected(now: Long) {
        replayValue = lastValue
        replayUntil = now + replayWindowMs
    }

    fun reset() {
        lastValue = null; lastAt = Long.MIN_VALUE; replayValue = null; replayUntil = Long.MIN_VALUE
    }

    /** @return the reason to drop, or null to accept. */
    fun check(metres: Double, now: Long): String? {
        val rv = replayValue
        if (rv != null && now <= replayUntil && same(rv, metres)) {
            replayValue = null
            return "replayed after reconnect"
        }
        val lv = lastValue
        if (lv != null && same(lv, metres) && now - lastAt < duplicateWindowMs) return "duplicate notification"
        lastValue = metres
        lastAt = now
        return null
    }

    private fun same(a: Double, b: Double) = abs(a - b) < 1e-6
}
