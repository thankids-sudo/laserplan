package io.github.thankids_sudo.laserplan.laser.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import io.github.thankids_sudo.laserplan.laser.diagnostics.Diagnostics
import io.github.thankids_sudo.laserplan.laser.protocol.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

/**
 * BLE GATT transport. GATT operations are serialised (Android allows one outstanding operation per connection),
 * each with a timeout. Notifications/indications are delivered on [notifications] with the characteristic UUID.
 * Permissions (BLUETOOTH_CONNECT) are checked by the caller before use.
 */
@SuppressLint("MissingPermission")
class BleTransport(private val context: Context) {
    data class Packet(val characteristic: UUID, val bytes: ByteArray, val atMs: Long)

    private var gatt: BluetoothGatt? = null
    private val opLock = Mutex()
    @Volatile private var pendingOp: CompletableDeferred<Int>? = null
    @Volatile private var pendingConnect: CompletableDeferred<Boolean>? = null
    @Volatile private var pendingServices: CompletableDeferred<Boolean>? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    private val _notifications = MutableSharedFlow<Packet>(extraBufferCapacity = 64)
    val notifications: SharedFlow<Packet> = _notifications

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Diagnostics.log("GATT state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _connected.value = true
                pendingConnect?.complete(true)
            } else {
                _connected.value = false
                pendingConnect?.complete(false)
                pendingServices?.complete(false)
                pendingOp?.completeExceptionally(IOException("Disconnected (status $status)"))
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    g.close()
                    if (gatt === g) gatt = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pendingServices?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendingOp?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingOp?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            emit(c.uuid, value)
        }

        @Deprecated("Used on Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                c.value?.let { emit(c.uuid, it.copyOf()) }
            }
        }
    }

    private fun emit(uuid: UUID, value: ByteArray) {
        Diagnostics.log("RX ${uuid.toString().take(8)}  ${value.toHex()}")
        _notifications.tryEmit(Packet(uuid, value, System.currentTimeMillis()))
    }

    suspend fun connect(address: String, timeoutMs: Long = 15_000) {
        close()
        val mgr = context.getSystemService(BluetoothManager::class.java) ?: throw IOException("No Bluetooth on this phone")
        val adapter = mgr.adapter ?: throw IOException("No Bluetooth on this phone")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")
        val device: BluetoothDevice = adapter.getRemoteDevice(address)
        val c = CompletableDeferred<Boolean>().also { pendingConnect = it }
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        val ok = try { withTimeout(timeoutMs) { c.await() } } finally { pendingConnect = null }
        if (!ok) throw IOException("Could not connect")
        val s = CompletableDeferred<Boolean>().also { pendingServices = it }
        if (gatt?.discoverServices() != true) throw IOException("Service discovery refused")
        val found = try { withTimeout(10_000) { s.await() } } finally { pendingServices = null }
        if (!found) throw IOException("Service discovery failed")
    }

    fun hasService(service: UUID): Boolean = gatt?.getService(service) != null

    /** Service and characteristic UUIDs with property flags, for diagnostics. Read-only. */
    fun describeServices(): List<String> = gatt?.services.orEmpty().flatMap { svc ->
        listOf("service ${svc.uuid}") + svc.characteristics.map { "  char ${it.uuid} props=0x%02X".format(it.properties) }
    }

    private fun characteristic(service: UUID, ch: UUID): BluetoothGattCharacteristic =
        gatt?.getService(service)?.getCharacteristic(ch) ?: throw IOException("Characteristic $ch not found")

    private suspend fun op(timeoutMs: Long = 5_000, start: () -> Boolean): Int = opLock.withLock {
        val d = CompletableDeferred<Int>()
        pendingOp = d
        try {
            if (!start()) throw IOException("GATT operation refused")
            withTimeout(timeoutMs) { d.await() }
        } finally {
            pendingOp = null
        }
    }

    /** Subscribe to notifications, or indications when the characteristic only supports those. */
    suspend fun enableNotifications(service: UUID, ch: UUID) {
        val g = gatt ?: throw IOException("Not connected")
        val c = characteristic(service, ch)
        if (!g.setCharacteristicNotification(c, true)) throw IOException("Could not enable notifications")
        val cccd = c.getDescriptor(CCCD) ?: return
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        val status = op {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { cccd.value = value; g.writeDescriptor(cccd) }
            }
        }
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("Subscribe failed (status $status)")
    }

    suspend fun write(service: UUID, ch: UUID, bytes: ByteArray) {
        val g = gatt ?: throw IOException("Not connected")
        val c = characteristic(service, ch)
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        Diagnostics.log("TX ${ch.toString().take(8)}  ${bytes.toHex()}")
        op {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, bytes, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { c.writeType = type; c.value = bytes; g.writeCharacteristic(c) }
            }
        }
    }

    fun close() {
        val g = gatt ?: return
        gatt = null
        _connected.value = false
        try { g.disconnect() } catch (_: Exception) {}
        try { g.close() } catch (_: Exception) {}
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
