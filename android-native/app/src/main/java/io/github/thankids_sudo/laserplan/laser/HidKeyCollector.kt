package io.github.thankids_sudo.laserplan.laser

import android.view.KeyEvent

/**
 * Collects keystrokes from the one input device chosen for keyboard mode and hands complete readings to the
 * keyboard adapter. Keys from every other device (the phone's own keyboard, a real keyboard) pass through untouched.
 */
class HidKeyCollector {
    private val sb = StringBuilder()
    private var lastKeyAt = 0L

    /** @return true when the event came from the keyboard-mode meter and was consumed. */
    fun handle(event: KeyEvent): Boolean {
        val wanted = LaserManager.hidDescriptor ?: return false
        val dev = event.device ?: return false
        if (dev.descriptor != wanted) return false
        if (event.action == KeyEvent.ACTION_UP) return true
        val now = event.eventTime
        if (now - lastKeyAt > 1500) sb.clear()
        lastKeyAt = now
        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB -> {
                val text = sb.toString(); sb.clear()
                if (text.isNotBlank()) LaserManager.hid.onTyped(text)
            }
            KeyEvent.KEYCODE_DEL -> if (sb.isNotEmpty()) sb.setLength(sb.length - 1)
            else -> {
                val c = event.getUnicodeChar(event.metaState)
                if (c > 0 && sb.length < 40) sb.append(c.toChar())
            }
        }
        return true
    }
}
