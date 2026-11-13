package io.github.thankids_sudo.laserplan.laser.adapters

import android.content.Context
import io.github.thankids_sudo.laserplan.laser.diagnostics.Diagnostics
import io.github.thankids_sudo.laserplan.laser.measurement.MeasurementNormalizer
import io.github.thankids_sudo.laserplan.laser.model.ConnectionResult
import io.github.thankids_sudo.laserplan.laser.model.DiscoveredLaserDevice
import io.github.thankids_sudo.laserplan.laser.model.LaserCapabilities
import io.github.thankids_sudo.laserplan.laser.model.LaserMeasurement
import io.github.thankids_sudo.laserplan.laser.model.RawReading
import io.github.thankids_sudo.laserplan.laser.model.SupportStatus
import io.github.thankids_sudo.laserplan.laser.model.Transport
import io.github.thankids_sudo.laserplan.laser.protocol.BoschMtProtocol
import io.github.thankids_sudo.laserplan.laser.protocol.LeicaDistoProtocol
import io.github.thankids_sudo.laserplan.laser.protocol.toHex
import io.github.thankids_sudo.laserplan.laser.transport.BleTransport
import io.github.thankids_sudo.laserplan.laser.transport.ClassicSppTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import java.util.UUID

/** One adapter per protocol family. Selected by verified service UUIDs or name patterns, or by explicit user choice. */
interface LaserMeterAdapter {
    val adapterId: String
    val displayName: String
    val manufacturer: String
    val transport: Transport
    val status: SupportStatus
    /** True only when the advertisement carries evidence (service UUID / name) for this protocol. */
    fun matches(device: DiscoveredLaserDevice): Boolean
    suspend fun connect(device: DiscoveredLaserDevice): ConnectionResult
    fun measurements(): Flow<LaserMeasurement>
    suspend fun getCapabilities(): LaserCapabilities
    suspend fun disconnect()
    /** Link state, for automatic reconnect; null when the transport has no observable link (keyboard mode). */
    val linkUp: StateFlow<Boolean>?
}

private var seq = 0L
internal fun measurementOf(a: LaserMeterAdapter, dev: DiscoveredLaserDevice?, r: RawReading, raw: ByteArray?): LaserMeasurement =
    LaserMeasurement(
        measurementId = "m${System.currentTimeMillis()}-${++seq}",
        adapterId = a.adapterId,
        manufacturer = a.manufacturer,
        model = dev?.name,
        devicePseudoId = dev?.let { Diagnostics.pseudo(it.address) } ?: "unknown",
        transport = a.transport,
        originalValue = r.value,
        originalUnit = r.unit,
        metres = MeasurementNormalizer.toMetres(r.value, r.unit),
        type = r.type,
        timestampMs = System.currentTimeMillis(),
        rawHex = raw?.toHex(),
    )

/** Common BLE behaviour: connect, check the service, subscribe, decode notifications of one characteristic. */
abstract class BleAdapter(context: Context) : LaserMeterAdapter {
    protected val ble = BleTransport(context)
    protected var device: DiscoveredLaserDevice? = null
    override val transport = Transport.BLE
    override val linkUp: StateFlow<Boolean> get() = ble.connected
    abstract val service: UUID
    abstract val notifyCharacteristic: UUID
    abstract fun decode(bytes: ByteArray): RawReading?
    /** Commands sent after subscribing (only documented ones). */
    protected open suspend fun afterSubscribe() {}

    override suspend fun connect(device: DiscoveredLaserDevice): ConnectionResult = try {
        this.device = device
        ble.connect(device.address)
        if (!ble.hasService(service)) {
            ble.describeServices().forEach { Diagnostics.log(it) }
            ble.close()
            ConnectionResult.Failed("$displayName service not found on this device")
        } else {
            ble.enableNotifications(service, notifyCharacteristic)
            afterSubscribe()
            ConnectionResult.Connected
        }
    } catch (e: Exception) {
        ble.close()
        ConnectionResult.Failed(e.message ?: e.javaClass.simpleName)
    }

    override fun measurements(): Flow<LaserMeasurement> = ble.notifications
        .filter { it.characteristic == notifyCharacteristic }
        .mapNotNull { p -> decode(p.bytes)?.let { measurementOf(this, device, it, p.bytes) } }

    override suspend fun disconnect() = ble.close()
}

class BoschGlmAdapter(context: Context) : BleAdapter(context) {
    override val adapterId = "bosch-mt"
    override val displayName = "Bosch GLM / PLR (MT protocol)"
    override val manufacturer = "Bosch"
    override val status = SupportStatus.PROTOCOL_SUPPORTED
    override val service: UUID = BoschMtProtocol.SERVICE
    override val notifyCharacteristic: UUID = BoschMtProtocol.CHARACTERISTIC
    private val namePattern = Regex("^(GLM|PLR|Bosch|BOSCH|UniversalDistance|AdvancedDistance)", RegexOption.IGNORE_CASE)

    override fun matches(device: DiscoveredLaserDevice) = device.transport == Transport.BLE &&
        (device.serviceUuids.any { it.equals(service.toString(), true) } || namePattern.containsMatchIn(device.name.orEmpty()))

    override fun decode(bytes: ByteArray) = BoschMtProtocol.decode(bytes)
    override suspend fun afterSubscribe() = ble.write(service, notifyCharacteristic, BoschMtProtocol.CMD_AUTO_SYNC)
    override suspend fun getCapabilities() = LaserCapabilities(notes = "Auto-sync: every measurement taken on the meter is sent. Distance in metres.")
}

class LeicaDistoAdapter(context: Context) : BleAdapter(context) {
    override val adapterId = "leica-disto"
    override val displayName = "Leica DISTO (BLE distance service)"
    override val manufacturer = "Leica"
    override val status = SupportStatus.PROTOCOL_SUPPORTED
    override val service: UUID = LeicaDistoProtocol.SERVICE
    override val notifyCharacteristic: UUID = LeicaDistoProtocol.DISTANCE

    override fun matches(device: DiscoveredLaserDevice) = device.transport == Transport.BLE &&
        (device.serviceUuids.any { it.equals(service.toString(), true) } || device.name.orEmpty().startsWith("DISTO", true))

    override fun decode(bytes: ByteArray) = LeicaDistoProtocol.decodeDistance(bytes)
    override suspend fun getCapabilities() = LaserCapabilities(notes = "Distance in metres from the DISTO distance characteristic. Press measure on the DISTO; no commands are sent.")
}

/** Paired Classic meter streaming text lines such as "1.234 m". Only used when the user chooses it. */
class SppTextAdapter(context: Context, private val defaultUnit: () -> String) : LaserMeterAdapter {
    private val spp = ClassicSppTransport(context)
    private var device: DiscoveredLaserDevice? = null
    override val adapterId = "spp-text"
    override val displayName = "Serial text stream (Bluetooth Classic)"
    override val manufacturer = "Generic"
    override val transport = Transport.CLASSIC_SPP
    override val status = SupportStatus.EXPERIMENTAL
    override val linkUp: StateFlow<Boolean>? = null
    override fun matches(device: DiscoveredLaserDevice) = false
    override suspend fun connect(device: DiscoveredLaserDevice): ConnectionResult = try {
        this.device = device; spp.connect(device.address); ConnectionResult.Connected
    } catch (e: Exception) { spp.close(); ConnectionResult.Failed(e.message ?: "Could not open the serial link") }
    override fun measurements(): Flow<LaserMeasurement> = spp.lines()
        .mapNotNull { MeasurementNormalizer.parse(it, defaultUnit())?.let { r -> measurementOf(this, device, r, it.toByteArray()) } }
    override suspend fun getCapabilities() = LaserCapabilities(unitsReported = true, notes = "Experimental: parses text lines with a number and unit.")
    override suspend fun disconnect() = spp.close()
}

/**
 * Keyboard (HID) mode: the meter is paired as a Bluetooth keyboard and types its readings. LaserPlan only reads keys
 * from the one input device the user picked, and only while its own window is in front. Never system-wide.
 */
class HidKeyboardAdapter(private val defaultUnit: () -> String) : LaserMeterAdapter {
    private val out = MutableSharedFlow<LaserMeasurement>(extraBufferCapacity = 8)
    var device: DiscoveredLaserDevice? = null
        private set
    override val adapterId = "hid-keyboard"
    override val displayName = "Keyboard mode (meter types the reading)"
    override val manufacturer = "Any"
    override val transport = Transport.HID_KEYBOARD
    override val status = SupportStatus.EXPERIMENTAL
    override val linkUp: StateFlow<Boolean>? = null
    override fun matches(device: DiscoveredLaserDevice) = false
    override suspend fun connect(device: DiscoveredLaserDevice): ConnectionResult { this.device = device; return ConnectionResult.Connected }
    override fun measurements(): Flow<LaserMeasurement> = out
    override suspend fun getCapabilities() = LaserCapabilities(unitsReported = true, notes = "Uses whatever the meter types; bare numbers use the unit you chose.")
    override suspend fun disconnect() { device = null }

    /** @return true when the text was a reading. */
    fun onTyped(text: String): Boolean {
        val r = MeasurementNormalizer.parse(text, defaultUnit()) ?: run { Diagnostics.log("Keyboard text not a reading: \"$text\""); return false }
        Diagnostics.log("Keyboard reading \"$text\"")
        return out.tryEmit(measurementOf(this, device, r, null))
    }
}
