package io.github.thankids_sudo.laserplan.survey

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Shows a finished survey's preview cloud and exports the session ZIP through the Storage Access Framework. */
class SurveyViewerActivity : AppCompatActivity() {
    private lateinit var dir: File
    private lateinit var view: PointCloudView

    private val save = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openOutputStream(uri)?.use { SurveyRecorder.zip(dir, it) } ?: error("Could not open the file") }.exceptionOrNull()
            }
            Toast.makeText(this@SurveyViewerActivity, err?.let { "Export failed: ${it.message}" } ?: "Survey ZIP saved.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_DIR)
        val root = File(cacheDir, ROOT)
        dir = path?.let(::File)?.takeIf { it.canonicalPath.startsWith(root.canonicalPath) } ?: root
        view = PointCloudView(this)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.parseColor("#CC0B1220"))
            listOf("Reset view" to { view.resetView() }, "Export ZIP" to { exportZip() }, "Close" to { finish() }).forEach { (t, fn) ->
                addView(Button(this@SurveyViewerActivity).apply { text = t; isAllCaps = false; setOnClickListener { fn() } },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bar)
        })
        lifecycleScope.launch {
            val p = withContext(Dispatchers.IO) { SurveyRecorder.readPreview(File(dir, "preview.bin")) }
            view.setPoints(p)
        }
    }

    private fun exportZip() {
        if (!File(dir, "manifest.json").isFile) { Toast.makeText(this, "Stop the survey first – nothing to export yet.", Toast.LENGTH_LONG).show(); return }
        save.launch("LaserPlan-survey-${dir.name}.zip")
    }

    companion object {
        const val EXTRA_DIR = "dir"
        const val ROOT = "surveys"
    }
}
