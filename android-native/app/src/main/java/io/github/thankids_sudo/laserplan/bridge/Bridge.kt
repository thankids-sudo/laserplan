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
    data class ArOpen(val projectId: String?, val roomId: String?, val roomName: String) : WebCommand()
    data class ScanView(val projectId: String, val scanId: String) : WebCommand()
    data class ScanExport(val projectId: String, val scanId: String) : WebCommand()
    data class ScanDelete(val projectId: String, val scanId: String) : WebCommand()
    data class ScansDeleteProject(val projectId: String) : WebCommand()
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
            "ar.open" -> WebCommand.ArOpen(id(o, "projectId"), id(o, "roomId"), o.optString("roomName").take(60))
            "scan.view", "scan.export", "scan.delete" -> {
                val p = id(o, "projectId"); val s = id(o, "scanId")
                if (p == null || s == null) null else when (o.optString("cmd")) {
                    "scan.view" -> WebCommand.ScanView(p, s); "scan.export" -> WebCommand.ScanExport(p, s); else -> WebCommand.ScanDelete(p, s)
                }
            }
            "scans.deleteProject" -> id(o, "projectId")?.let { WebCommand.ScansDeleteProject(it) }
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

    private val safeId = Regex("^[A-Za-z0-9_-]{1,64}$")
    private fun id(o: JSONObject, k: String): String? = o.optString(k).takeIf { safeId.matches(it) }

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
