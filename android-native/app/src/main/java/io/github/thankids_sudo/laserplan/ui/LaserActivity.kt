package io.github.thankids_sudo.laserplan.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.thankids_sudo.laserplan.laser.HidKeyCollector
import io.github.thankids_sudo.laserplan.laser.LaserManager
import io.github.thankids_sudo.laserplan.laser.ScanItem
import io.github.thankids_sudo.laserplan.laser.diagnostics.Diagnostics
import io.github.thankids_sudo.laserplan.laser.model.ConnState
import io.github.thankids_sudo.laserplan.laser.model.DiscoveredLaserDevice
import io.github.thankids_sudo.laserplan.laser.model.SupportStatus
import io.github.thankids_sudo.laserplan.laser.registry.CompatibilityRegistry
import io.github.thankids_sudo.laserplan.laser.transport.BleTransport
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Universal laser connection screen: scan, connect, reconnect, keyboard mode, paired Classic meters,
 * test reading, compatibility list and diagnostics. Readings shown here are NOT sent to the drawing.
 */
@SuppressLint("MissingPermission", "SetTextI18n")
class LaserActivity : AppCompatActivity() {
    private val bg = Color.parseColor("#0B1220")
    private val card = Color.parseColor("#141C2E")
    private val fg = Color.parseColor("#E8EEF8")
    private val dim = Color.parseColor("#93A1B8")
    private val ok = Color.parseColor("#3DDC84")
    private val warn = Color.parseColor("#F5B841")
    private val bad = Color.parseColor("#FF6B6B")

    private lateinit var status: TextView
    private lateinit var lastReading: TextView
    private lateinit var scanList: LinearLayout
    private lateinit var scanBtn: Button
    private lateinit var pairedList: LinearLayout
    private lateinit var keyboardList: LinearLayout
    private lateinit var unitRow: LinearLayout
    private val hidKeys = HidKeyCollector()
    private var afterPermission: (() -> Unit)? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) afterPermission?.invoke()
        else note("Bluetooth permission was not given. Allow \"Nearby devices\" for LaserPlan in Android settings.", bad)
        afterPermission = null
    }
    private val enableBt = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { afterPermission?.invoke(); afterPermission = null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LaserManager.init(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(32)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(root) })

        root.addView(text("Laser meters", 22f, fg, bold = true))
        root.addView(text("One meter at a time. Readings appear on the plan only after you return to it and arm a wall.", 13f, dim))
        status = text("", 15f, fg).also { root.addView(box(it)) }

        root.addView(row(
            button("Scan for meters") { withBluetooth { toggleScan() } }.also { scanBtn = it },
            button("Reconnect") { withBluetooth { if (!LaserManager.reconnectPreferred()) note("No saved meter yet. Scan and connect once.", warn) } },
            button("Disconnect") { LaserManager.disconnect() },
        ))

        root.addView(header("Nearby Bluetooth LE meters"))
        scanList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(scanList)

        root.addView(header("Last reading (test only – not sent to the plan from this screen)"))
        lastReading = text("–", 28f, ok, bold = true).also { root.addView(box(it)) }

        root.addView(header("Keyboard mode (meter types the reading)"))
        root.addView(text("Pair the meter in Android Bluetooth settings in its keyboard / HID mode, then pick it here. Only keys from that meter are read, and only inside LaserPlan.", 13f, dim))
        unitRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(unitRow)
        renderUnits()
        keyboardList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(keyboardList)
        root.addView(button("Find keyboard-mode meters") { renderKeyboards() })

        root.addView(header("Paired Bluetooth Classic meters (experimental)"))
        pairedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(pairedList)
        root.addView(button("Show paired devices") { withBluetooth { renderPaired() } })

        root.addView(header("Help"))
        root.addView(row(
            button("Compatibility list") { showCompatibility() },
            button("Diagnostics") { showDiagnostics() },
        ))
        root.addView(button("Done – back to the plan") { finish() })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { LaserManager.state.collect { renderState() } }
                launch { LaserManager.scanResults.collect { renderScan(it) } }
                launch { LaserManager.scanning.collect { scanBtn.text = if (it) "Stop scan" else "Scan for meters" } }
                launch {
                    LaserManager.lastReading.collect { m ->
                        lastReading.text = m?.let {
                            val v = String.format(Locale.US, "%.4f", it.originalValue).trimEnd('0').trimEnd('.')
                            "$v ${it.originalUnit}" + (it.metres?.let { mm -> String.format(Locale.US, "  =  %.3f m", mm) } ?: "") + "\n${it.manufacturer} · ${it.adapterId}"
                        } ?: "–"
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        LaserManager.stopScan()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = hidKeys.handle(event) || super.dispatchKeyEvent(event)

    // ---------------- permissions ----------------
    private fun withBluetooth(action: () -> Unit) {
        val need = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = need.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        val run = {
            val bt = getSystemService(BluetoothManager::class.java)?.adapter
            when {
                bt == null -> note("This phone has no Bluetooth.", bad)
                !bt.isEnabled -> { afterPermission = action; enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                else -> action()
            }
        }
        if (missing.isEmpty()) run() else { afterPermission = run; permissions.launch(missing.toTypedArray()) }
    }

    private fun toggleScan() {
        if (LaserManager.scanning.value) { LaserManager.stopScan(); return }
        LaserManager.startScan()?.let { note(it, bad) }
    }

    // ---------------- rendering ----------------
    private fun renderState() {
        val s = LaserManager.state.value
        val (word, color) = when (s.conn) {
            ConnState.CONNECTED -> "Connected" to ok
            ConnState.CONNECTING -> "Connecting…" to warn
            ConnState.RECONNECTING -> "Reconnecting…" to warn
            ConnState.ERROR -> "Could not connect" to bad
            ConnState.DISCONNECTED -> "Disconnected" to dim
        }
        status.setTextColor(color)
        status.text = buildString {
            append(word)
            s.deviceName?.let { append(" · ").append(it) }
            s.adapterName?.let { append("\nAdapter: ").append(it) }
            s.transport?.let { append("\nLink: ").append(it.name.replace('_', ' ')) }
            s.status?.let { append("\nSupport: ").append(it.label) }
            s.error?.let { append("\n").append(it) }
            if (s.conn == ConnState.DISCONNECTED && LaserManager.hasPreferred()) append("\nSaved meter: ${LaserManager.preferredName() ?: "unnamed"} – tap Reconnect")
        }
    }

    private fun renderScan(items: List<ScanItem>) {
        scanList.removeAllViews()
        if (items.isEmpty()) {
            scanList.addView(text(if (LaserManager.scanning.value) "Searching… switch the meter on and turn on its Bluetooth." else "Tap Scan for meters.", 13f, dim))
            return
        }
        items.forEach { item ->
            val d = item.device
            val a = item.adapter
            val line = "${d.name ?: "Unnamed device"}  ·  ${Diagnostics.pseudo(d.address)}  ·  ${d.rssi ?: "?"} dBm\n" +
                (a?.let { "${it.displayName} – ${it.status.label}" } ?: "No adapter – not a known laser protocol")
            val t = text(line, 13f, if (a != null) fg else dim)
            val actions = if (a != null) button("Connect") { LaserManager.connect(d, a) }
            else button("Inspect") { inspect(d) }
            scanList.addView(box(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; addView(t); addView(actions)
            }))
        }
    }

    private fun renderUnits() {
        unitRow.removeAllViews()
        unitRow.addView(text("Unit for bare numbers: ", 13f, dim))
        listOf("m", "mm", "cm", "ft", "in").forEach { u ->
            unitRow.addView(Button(this).apply {
                text = u; isAllCaps = false
                setTextColor(if (LaserManager.unit == u) ok else fg)
                setOnClickListener { LaserManager.unit = u; renderUnits() }
            })
        }
    }

    private fun renderKeyboards() {
        keyboardList.removeAllViews()
        val im = getSystemService(InputManager::class.java)
        val devices = im.inputDeviceIds.mapNotNull { im.getInputDevice(it) }.filter { dev ->
            !dev.isVirtual && dev.sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || dev.isExternal)
        }
        if (devices.isEmpty()) {
            keyboardList.addView(text("No external keyboard device found. Pair the meter in keyboard mode first.", 13f, warn))
            return
        }
        devices.forEach { dev ->
            keyboardList.addView(box(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("${dev.name} · ${Diagnostics.pseudo(dev.descriptor)}", 13f, fg))
                addView(button("Use as laser") {
                    LaserManager.useKeyboardMeter(dev.descriptor, dev.name)
                    note("Keyboard mode on. Take a measurement: it should appear under Last reading.", ok)
                })
            }))
        }
    }

    private fun renderPaired() {
        pairedList.removeAllViews()
        val devices = LaserManager.pairedDevices()
        if (devices.isEmpty()) { pairedList.addView(text("No paired devices.", 13f, dim)); return }
        val spp = LaserManager.registry.byId("spp-text") ?: return
        devices.forEach { d ->
            pairedList.addView(box(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("${d.name ?: "Unnamed"} · ${Diagnostics.pseudo(d.address)}", 13f, fg))
                addView(button("Connect as text stream (experimental)") { LaserManager.connect(d, spp) })
            }))
        }
    }

    /** Read-only look at an unknown BLE device: connect, list services, disconnect. Sends no commands. */
    private fun inspect(d: DiscoveredLaserDevice) {
        LaserManager.stopScan()
        note("Inspecting ${d.name ?: "device"}… (read-only)", warn)
        lifecycleScope.launch {
            val t = BleTransport(this@LaserActivity)
            val lines = try {
                t.connect(d.address); t.describeServices()
            } catch (e: Exception) { listOf("Inspect failed: ${e.message}") } finally { t.close() }
            Diagnostics.log("Inspect ${Diagnostics.pseudo(d.address)} \"${d.name}\"")
            lines.forEach { Diagnostics.log(it) }
            AlertDialog.Builder(this@LaserActivity)
                .setTitle("Services on ${d.name ?: "device"}")
                .setMessage(lines.joinToString("\n") + "\n\nNot a known laser protocol yet. Share the diagnostics report to request support.")
                .setPositiveButton("Share diagnostics") { _, _ -> shareDiagnostics() }
                .setNegativeButton("Close", null).show()
        }
    }

    private fun showCompatibility() {
        val entries = runCatching { CompatibilityRegistry.parse(assets.open("compatibility/devices.json").bufferedReader().readText()) }.getOrElse { emptyList() }
        val msg = entries.joinToString("\n\n") { e ->
            val mark = when (e.status) { SupportStatus.VERIFIED -> "✔"; SupportStatus.PROTOCOL_SUPPORTED -> "◐"; SupportStatus.EXPERIMENTAL -> "△"; else -> "○" }
            "$mark ${e.brand} – ${e.models}\n   ${e.status.label} · ${e.transport}" + (if (e.notes.isNotEmpty()) "\n   ${e.notes}" else "")
        }
        AlertDialog.Builder(this).setTitle("Compatibility").setMessage(msg).setPositiveButton("Close", null).show()
    }

    private fun showDiagnostics() {
        val lines = Diagnostics.snapshot()
        AlertDialog.Builder(this).setTitle("Diagnostics (latest first)")
            .setMessage(if (lines.isEmpty()) "Nothing logged yet." else lines.take(120).joinToString("\n"))
            .setPositiveButton("Share") { _, _ -> shareDiagnostics() }
            .setNeutralButton("Clear") { _, _ -> Diagnostics.clear() }
            .setNegativeButton("Close", null).show()
    }

    private fun shareDiagnostics() {
        val s = LaserManager.state.value
        val header = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "State: ${s.conn} · ${s.deviceName ?: "-"} · ${s.adapterId ?: "-"}"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "LaserPlan laser diagnostics")
            .putExtra(Intent.EXTRA_TEXT, Diagnostics.report(header)), "Share diagnostics"))
    }

    // ---------------- small view helpers ----------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun header(s: String) = text(s, 15f, fg, bold = true).apply { setPadding(0, dp(18), 0, dp(4)) }
    private fun button(s: String, onClick: () -> Unit) = Button(this).apply { text = s; isAllCaps = false; setOnClickListener { onClick() } }
    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        v.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }
    private fun box(v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(card); setPadding(dp(12), dp(10), dp(12), dp(10))
        addView(v)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
    }
    private fun note(s: String, color: Int) {
        Diagnostics.log(s)
        status.text = s; status.setTextColor(color)
    }
}
