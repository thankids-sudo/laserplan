package io.github.thankids_sudo.laserplan.laser.model

/** How a meter talks to the phone. */
enum class Transport { BLE, CLASSIC_SPP, HID_KEYBOARD }

/**
 * Evidence level for a meter or adapter (see compatibility/devices.json).
 * VERIFIED = tested with physical hardware in this app; PROTOCOL_SUPPORTED = implemented from documented or
 * published protocol, hardware not yet tested; EXPERIMENTAL = may work, needs evidence; UNSUPPORTED; NOT_EVALUATED.
 */
enum class SupportStatus(val label: String) {
    VERIFIED("Verified"),
    PROTOCOL_SUPPORTED("Protocol supported – untested"),
    EXPERIMENTAL("Experimental"),
    UNSUPPORTED("Unsupported"),
    NOT_EVALUATED("Not evaluated"),
}

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }

enum class MeasurementType { DISTANCE, AREA, VOLUME, ANGLE, UNKNOWN }

/** A device seen in a scan, the paired list, or the input-device list. */
data class DiscoveredLaserDevice(
    val address: String,
    val name: String?,
    val transport: Transport,
    val serviceUuids: List<String> = emptyList(),
    val manufacturerIds: List<Int> = emptyList(),
    val rssi: Int? = null,
    val bonded: Boolean = false,
)

/** A value exactly as the meter (or keyboard stream) delivered it, before unit conversion. */
data class RawReading(
    val value: Double,
    val unit: String,
    val type: MeasurementType = MeasurementType.DISTANCE,
    val text: String? = null,
)

/** Normalised measurement handed to the drawing engine. Metres are null for non-distance readings. */
data class LaserMeasurement(
    val measurementId: String,
    val adapterId: String,
    val manufacturer: String,
    val model: String?,
    val devicePseudoId: String,
    val transport: Transport,
    val originalValue: Double,
    val originalUnit: String,
    val metres: Double?,
    val type: MeasurementType,
    val timestampMs: Long,
    val rawHex: String?,
)

data class LaserCapabilities(
    val distance: Boolean = true,
    val remoteTrigger: Boolean = false,
    val unitsReported: Boolean = false,
    val notes: String = "",
)

sealed class ConnectionResult {
    data object Connected : ConnectionResult()
    data class Failed(val reason: String) : ConnectionResult()
}

/** What the connection screen and the drawing engine are told about the active meter. */
data class LaserUiState(
    val conn: ConnState = ConnState.DISCONNECTED,
    val deviceName: String? = null,
    val adapterId: String? = null,
    val adapterName: String? = null,
    val transport: Transport? = null,
    val status: SupportStatus? = null,
    val error: String? = null,
)
