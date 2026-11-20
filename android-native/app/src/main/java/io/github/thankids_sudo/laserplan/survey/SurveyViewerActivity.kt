package io.github.thankids_sudo.laserplan.survey

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Shows a finished scan's preview cloud (OpenGL) and exports the scan ZIP through the Storage Access Framework. */
class SurveyViewerActivity : AppCompatActivity() {
    private lateinit var dir: File
    private lateinit var view: PointCloudView
    private lateinit var info: TextView
    private lateinit var modeBtn: Button

    private val save = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openOutputStream(uri)?.use { SurveyRecorder.zip(dir, it) } ?: error("Could not open the file") }.exceptionOrNull()
            }
            Toast.makeText(this@SurveyViewerActivity, err?.let { "Export failed: ${it.message}" } ?: "Scan ZIP saved.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_DIR)
        val root = ScanStore.root(this)
        dir = path?.let(::File)?.takeIf { it.canonicalPath.startsWith(root.canonicalPath) } ?: root
        view = PointCloudView(this)
        info = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; setPadding(24, 16, 24, 16); setBackgroundColor(Color.parseColor("#990B1220"))
            text = "Loading…"
        }
        view.onSummary = { info.text = "$it\nDrag to rotate · pinch to zoom · double-tap to reset" }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.parseColor("#CC0B1220"))
            fun add(t: String, fn: (Button) -> Unit) = Button(this@SurveyViewerActivity).apply { text = t; isAllCaps = false; textSize = 12f; setOnClickListener { fn(this) } }
                .also { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
            modeBtn = add("Height colour") { b ->
                if (view.mode == PointCloudView.ColourMode.PHOTO) { view.setMode(PointCloudView.ColourMode.HEIGHT); b.text = "Photo colour" }
                else { view.setMode(PointCloudView.ColourMode.PHOTO); b.text = "Height colour" }
            }
            add("Points −") { view.pointSize(-1f) }
            add("Points +") { view.pointSize(1f) }
            add("Reset") { view.resetView() }
            add("Export ZIP") { exportZip() }
            add("Close") { finish() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(FrameLayout(this@SurveyViewerActivity).apply {
                addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(info, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bar)
        })
        lifecycleScope.launch {
            val (p, c) = withContext(Dispatchers.IO) { SurveyRecorder.readPreviewAny(dir) }
            view.setPoints(p, c)
            if (view.mode == PointCloudView.ColourMode.HEIGHT) modeBtn.text = "Photo colour"
        }
    }

    override fun onResume() { super.onResume(); view.onResume() }
    override fun onPause() { view.onPause(); super.onPause() }

    private fun exportZip() {
        if (!File(dir, "manifest.json").isFile) { Toast.makeText(this, "Stop the scan first – nothing to export yet.", Toast.LENGTH_LONG).show(); return }
        save.launch("LaserPlan-scan-${dir.name}.zip")
    }

    companion object {
        const val EXTRA_DIR = "dir"
    }
}
