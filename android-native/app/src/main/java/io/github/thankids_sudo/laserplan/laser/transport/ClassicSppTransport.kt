package io.github.thankids_sudo.laserplan.laser.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import io.github.thankids_sudo.laserplan.laser.diagnostics.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth Classic RFCOMM / Serial Port Profile transport for paired meters that stream text.
 * Status EXPERIMENTAL: no meter is yet verified on this transport in LaserPlan.
 */
@SuppressLint("MissingPermission")
class ClassicSppTransport(private val context: Context) {
    private var socket: BluetoothSocket? = null

    suspend fun connect(address: String) = withContext(Dispatchers.IO) {
        close()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: throw IOException("No Bluetooth")
        val dev = adapter.getRemoteDevice(address)
        try { adapter.cancelDiscovery() } catch (_: SecurityException) {}
        val s = dev.createRfcommSocketToServiceRecord(SPP)
        s.connect()
        socket = s
        Diagnostics.log("SPP connected")
    }

    /** Text lines from the meter (CR, LF or CRLF terminated, max 64 characters per line). */
    fun lines(): Flow<String> = flow {
        val input = socket?.inputStream ?: throw IOException("Not connected")
        val sb = StringBuilder()
        val buf = ByteArray(256)
        while (true) {
            val n = input.read(buf)
            if (n < 0) throw IOException("Meter closed the link")
            for (i in 0 until n) {
                val ch = (buf[i].toInt() and 0xFF).toChar()
                if (ch == '\n' || ch == '\r') {
                    if (sb.isNotEmpty()) { Diagnostics.log("SPP line \"$sb\""); emit(sb.toString()); sb.clear() }
                } else if (sb.length < 64) sb.append(ch)
            }
        }
    }.flowOn(Dispatchers.IO)

    fun close() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
    }

    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
