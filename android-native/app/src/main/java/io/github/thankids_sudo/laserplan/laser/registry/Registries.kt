package io.github.thankids_sudo.laserplan.laser.registry

import io.github.thankids_sudo.laserplan.laser.adapters.LaserMeterAdapter
import io.github.thankids_sudo.laserplan.laser.model.DiscoveredLaserDevice
import io.github.thankids_sudo.laserplan.laser.model.SupportStatus
import org.json.JSONObject

/** Chooses an adapter for a discovered device from evidence in its advertisement. Never guesses. */
class AdapterRegistry(private val adapters: List<LaserMeterAdapter>) {
    val all: List<LaserMeterAdapter> get() = adapters
    fun match(device: DiscoveredLaserDevice): LaserMeterAdapter? = adapters.firstOrNull { it.matches(device) }
    fun byId(id: String): LaserMeterAdapter? = adapters.firstOrNull { it.adapterId == id }
}

data class CompatibilityEntry(
    val brand: String,
    val models: String,
    val transport: String,
    val adapter: String?,
    val status: SupportStatus,
    val evidence: String,
    val notes: String,
)

/** Reads compatibility/devices.json (bundled in assets) – the published list of what is and is not supported. */
object CompatibilityRegistry {
    fun parse(json: String): List<CompatibilityEntry> {
        val arr = JSONObject(json).getJSONArray("devices")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CompatibilityEntry(
                brand = o.getString("brand"),
                models = o.getString("models"),
                transport = o.optString("transport", "unknown"),
                adapter = o.optString("adapter").ifEmpty { null },
                status = runCatching { SupportStatus.valueOf(o.getString("status")) }.getOrDefault(SupportStatus.NOT_EVALUATED),
                evidence = o.optString("evidence"),
                notes = o.optString("notes"),
            )
        }
    }
}
