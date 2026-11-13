package io.github.thankids_sudo.laserplan.bridge

import io.github.thankids_sudo.laserplan.laser.model.ConnState
import io.github.thankids_sudo.laserplan.laser.model.LaserMeasurement
import io.github.thankids_sudo.laserplan.laser.model.LaserUiState
import org.json.JSONObject

/** The only origin allowed to talk to the native side: the app's own bundled web assets. */
const val APP_ORIGIN = "https://appassets.androidplatform.net"
const val START_URL = "$APP_ORIGIN/assets/www/index.html"

/** A validated command from the web app. Anything else is rejected. */
sealed class WebCommand {
    data object Hello : WebCommand()
    data object LaserOpen : WebCommand()
    data object LaserReconnect : WebCommand()
    data object LaserDisconnect : WebCommand()
    data class Save(val name: String, val mime: String, val base64: String) : WebCommand()
}

object Bridge {
    const val MAX_SAVE_BASE64 = 40 * 1024 * 1024
    private val safeName = Regex("""^[\w\-. ()\[\]]{1,120}$""")
    private val safeMime = Regex("""^[\w.+-]+/[\w.+-]+$""")

    /** Parse and validate one message. Returns null for anything malformed or not on the allow-list. */
    fun parse(json: String): WebCommand? {
        if (json.length > MAX_SAVE_BASE64 + 1024) return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return when (o.optString("cmd")) {
            "hello" -> WebCommand.Hello
            "laser.open" -> WebCommand.LaserOpen
            "laser.reconnect" -> WebCommand.LaserReconnect
            "laser.disconnect" -> WebCommand.LaserDisconnect
            "save" -> {
                val name = o.optString("name").trim().replace(Regex("""[^\w\-. ()\[\]]"""), "_").take(120)
                val mime = o.optString("mime", "application/octet-stream")
                val b64 = o.optString("b64")
                if (!safeName.matches(name) || !safeMime.matches(mime) || b64.isEmpty() || b64.length > MAX_SAVE_BASE64) null
                else WebCommand.Save(name, mime, b64)
            }
            else -> null
        }
    }

    fun stateMessage(s: LaserUiState): String = JSONObject().apply {
        put("type", "state")
        put("state", when (s.conn) {
            ConnState.CONNECTED -> "on"
            ConnState.CONNECTING, ConnState.RECONNECTING -> "busy"
            else -> "off"
        })
        s.deviceName?.let { put("device", it) }
        s.adapterId?.let { put("adapter", it) }
        s.transport?.let { put("transport", it.name) }
        s.status?.let { put("status", it.name) }
        s.error?.let { put("error", it) }
    }.toString()

    fun readingMessage(m: LaserMeasurement): String = JSONObject().apply {
        put("type", "reading")
        put("v", m.originalValue)
        put("u", m.originalUnit)
        m.metres?.let { put("m", it) }
        put("adapter", m.adapterId)
        m.model?.let { put("device", it) }
        put("id", m.measurementId)
        put("t", m.timestampMs)
    }.toString()

    fun toast(text: String): String = JSONObject().put("type", "toast").put("text", text).toString()
    fun saved(name: String, ok: Boolean, error: String? = null): String =
        JSONObject().put("type", "saved").put("name", name).put("ok", ok).apply { error?.let { put("error", it) } }.toString()
}
