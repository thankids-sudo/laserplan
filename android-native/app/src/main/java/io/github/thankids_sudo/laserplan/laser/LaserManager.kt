package io.github.thankids_sudo.laserplan.laser

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import io.github.thankids_sudo.laserplan.laser.adapters.BoschGlmAdapter
import io.github.thankids_sudo.laserplan.laser.adapters.HidKeyboardAdapter
import io.github.thankids_sudo.laserplan.laser.adapters.LaserMeterAdapter
import io.github.thankids_sudo.laserplan.laser.adapters.LeicaDistoAdapter
import io.github.thankids_sudo.laserplan.laser.adapters.SppTextAdapter
import io.github.thankids_sudo.laserplan.laser.diagnostics.Diagnostics
import io.github.thankids_sudo.laserplan.laser.measurement.ReadingGate
import io.github.thankids_sudo.laserplan.laser.model.ConnState
import io.github.thankids_sudo.laserplan.laser.model.ConnectionResult
import io.github.thankids_sudo.laserplan.laser.model.DiscoveredLaserDevice
import io.github.thankids_sudo.laserplan.laser.model.LaserMeasurement
import io.github.thankids_sudo.laserplan.laser.model.LaserUiState
import io.github.thankids_sudo.laserplan.laser.model.Transport
import io.github.thankids_sudo.laserplan.laser.registry.AdapterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One scanned device with the adapter its advertisement points to (or none). */
data class ScanItem(val device: DiscoveredLaserDevice, val adapter: LaserMeterAdapter?)

/**
 * Universal Laser Connectivity Engine: owns discovery, the single active meter, reconnects and the reading
 * pipeline (adapter → normaliser → gate → drawing). One active meter at a time; one preferred meter remembered.
 */
@SuppressLint("MissingPermission")
object LaserManager {
    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    lateinit var registry: AdapterRegistry
        private set
    lateinit var hid: HidKeyboardAdapter
        private set

    private val _state = MutableStateFlow(LaserUiState())
    val state: StateFlow<LaserUiState> = _state
    private val _readings = MutableSharedFlow<LaserMeasurement>(extraBufferCapacity = 8)
    /** Live readings only (no replay): a reading nobody is listening for is dropped, never queued for later. */
    val readings: SharedFlow<LaserMeasurement> = _readings
    private val _last = MutableStateFlow<LaserMeasurement?>(null)
    val lastReading: StateFlow<LaserMeasurement?> = _last
    private val _scan = MutableStateFlow<List<ScanItem>>(emptyList())
    val scanResults: StateFlow<List<ScanItem>> = _scan
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val gate = ReadingGate()
    private var active: LaserMeterAdapter? = null
    private var activeDevice: DiscoveredLaserDevice? = null
    private val jobs = mutableListOf<Job>()
    private var userDisconnect = false

    var unit: String
        get() = prefs.getString("unit", "m") ?: "m"
        set(v) { prefs.edit().putString("unit", v).apply() }

    /** Input-device descriptor of the meter used in keyboard mode, when that mode is active. */
    val hidDescriptor: String? get() = if (active === hid) activeDevice?.address else null

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = app.getSharedPreferences("laser", Context.MODE_PRIVATE)
        hid = HidKeyboardAdapter { unit }
        registry = AdapterRegistry(listOf(BoschGlmAdapter(app), LeicaDistoAdapter(app), SppTextAdapter(app) { unit }, hid))
    }

    fun hasPreferred() = prefs.contains("addr")
    fun preferredName(): String? = prefs.getString("name", null)

    // ---------------- discovery ----------------
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val rec = r.scanRecord
            val name = rec?.deviceName ?: runCatching { r.device.name }.getOrNull()
            val uuids = rec?.serviceUuids?.map { it.uuid.toString() }.orEmpty()
            val mfr = rec?.manufacturerSpecificData?.let { sa -> (0 until sa.size()).map { sa.keyAt(it) } }.orEmpty()
            val dev = DiscoveredLaserDevice(r.device.address, name, Transport.BLE, uuids, mfr, r.rssi)
            val list = _scan.value
            val known = list.any { it.device.address == dev.address }
            if (!known) Diagnostics.log("Seen ${Diagnostics.pseudo(dev.address)} \"${name ?: "?"}\" rssi=${r.rssi} services=$uuids mfr=$mfr")
            val item = ScanItem(dev, registry.match(dev))
            _scan.value = (list.filterNot { it.device.address == dev.address } + item)
                .sortedWith(compareByDescending<ScanItem> { it.adapter != null }.thenByDescending { it.device.rssi ?: -200 })
        }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
            Diagnostics.log("Scan failed: $errorCode")
        }
    }

    /** @return an error message, or null when scanning started. */
    fun startScan(): String? {
        val bt = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return "This phone has no Bluetooth."
        if (!bt.isEnabled) return "Bluetooth is off."
        val sc = bt.bluetoothLeScanner ?: return "Bluetooth LE scanning is unavailable."
        stopScan()
        _scan.value = emptyList()
        sc.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        _scanning.value = true
        Diagnostics.log("Scan started")
        scope.launch { delay(15_000); stopScan() }
        return null
    }

    fun stopScan() {
        if (!_scanning.value) return
        runCatching { app.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        _scanning.value = false
        Diagnostics.log("Scan stopped")
    }

    fun pairedDevices(): List<DiscoveredLaserDevice> = runCatching {
        app.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
            .map { DiscoveredLaserDevice(it.address, it.name, Transport.CLASSIC_SPP, bonded = true) }
    }.getOrDefault(emptyList())

    // ---------------- connection ----------------
    fun connect(device: DiscoveredLaserDevice, adapter: LaserMeterAdapter) {
        stopScan()
        scope.launch {
            teardown()
            userDisconnect = false
            gate.reset()
            _state.value = LaserUiState(ConnState.CONNECTING, device.name, adapter.adapterId, adapter.displayName, adapter.transport, adapter.status)
            Diagnostics.log("Connect ${Diagnostics.pseudo(device.address)} via ${adapter.adapterId}")
            when (val res = adapter.connect(device)) {
                is ConnectionResult.Connected -> {
                    active = adapter; activeDevice = device
                    prefs.edit().putString("addr", device.address).putString("adapter", adapter.adapterId)
                        .putString("name", device.name).putString("transport", device.transport.name).apply()
                    _state.value = _state.value.copy(conn = ConnState.CONNECTED, error = null)
                    Diagnostics.log("Connected: ${adapter.getCapabilities().notes}")
                    startPipelines(adapter, device)
                }
                is ConnectionResult.Failed -> {
                    Diagnostics.log("Connect failed: ${res.reason}")
                    _state.value = _state.value.copy(conn = ConnState.ERROR, error = res.reason)
                }
            }
        }
    }

    private fun startPipelines(adapter: LaserMeterAdapter, device: DiscoveredLaserDevice) {
        jobs += scope.launch {
            adapter.measurements().collect { m ->
                val metres = m.metres
                if (metres == null || metres <= 0) { Diagnostics.log("Ignored non-distance reading ${m.originalValue} ${m.originalUnit}"); return@collect }
                val why = gate.check(metres, System.currentTimeMillis())
                if (why != null) { Diagnostics.log("Dropped ${m.originalValue} ${m.originalUnit}: $why"); return@collect }
                _last.value = m
                _readings.tryEmit(m)
            }
        }
        val link = adapter.linkUp ?: return
        jobs += scope.launch {
            while (true) {
                link.first { !it }
                if (userDisconnect || active !== adapter) return@launch
                if (!reconnect(adapter, device)) return@launch
            }
        }
    }

    private suspend fun reconnect(adapter: LaserMeterAdapter, device: DiscoveredLaserDevice): Boolean {
        _state.value = _state.value.copy(conn = ConnState.RECONNECTING, error = null)
        for (i in 1..3) {
            delay(1200L * i)
            if (userDisconnect || active !== adapter) return false
            Diagnostics.log("Reconnect attempt $i")
            if (adapter.connect(device) is ConnectionResult.Connected) {
                gate.onReconnected(System.currentTimeMillis())
                _state.value = _state.value.copy(conn = ConnState.CONNECTED, error = null)
                return true
            }
        }
        _state.value = _state.value.copy(conn = ConnState.DISCONNECTED, error = "Meter out of range or switched off")
        active = null
        return false
    }

    /** Reconnect to the remembered meter, or return false if there is none. */
    fun reconnectPreferred(): Boolean {
        val addr = prefs.getString("addr", null) ?: return false
        val adapter = registry.byId(prefs.getString("adapter", "") ?: "") ?: return false
        if (adapter === hid) return false
        val t = runCatching { Transport.valueOf(prefs.getString("transport", "BLE")!!) }.getOrDefault(Transport.BLE)
        connect(DiscoveredLaserDevice(addr, prefs.getString("name", null), t), adapter)
        return true
    }

    fun useKeyboardMeter(descriptor: String, name: String) =
        connect(DiscoveredLaserDevice(descriptor, name, Transport.HID_KEYBOARD), hid)

    fun disconnect() {
        userDisconnect = true
        scope.launch {
            teardown()
            _state.value = LaserUiState(ConnState.DISCONNECTED)
            Diagnostics.log("Disconnected by user")
        }
    }

    private suspend fun teardown() {
        jobs.forEach { it.cancel() }; jobs.clear()
        active?.let { runCatching { it.disconnect() } }
        active = null; activeDevice = null
    }
}
