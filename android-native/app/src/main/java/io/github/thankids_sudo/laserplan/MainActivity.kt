package io.github.thankids_sudo.laserplan

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.KeyEvent
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.thankids_sudo.laserplan.ar.ArRoomActivity
import io.github.thankids_sudo.laserplan.bridge.APP_ORIGIN
import io.github.thankids_sudo.laserplan.bridge.Bridge
import io.github.thankids_sudo.laserplan.bridge.START_URL
import io.github.thankids_sudo.laserplan.bridge.WebCommand
import io.github.thankids_sudo.laserplan.laser.HidKeyCollector
import io.github.thankids_sudo.laserplan.laser.LaserManager
import io.github.thankids_sudo.laserplan.laser.diagnostics.Diagnostics
import io.github.thankids_sudo.laserplan.ui.LaserActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts the LaserPlan drawing, CAD and estimate engine (bundled web assets, served from the app's own origin by
 * WebViewAssetLoader) and connects it to native Bluetooth through a narrow, origin-checked message bridge.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private var reply: JavaScriptReplyProxy? = null
    private val hidKeys = HidKeyCollector()
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingSave: WebCommand.Save? = null

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        fileCallback = null
    }

    private val createDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val s = pendingSave ?: return@registerForActivityResult
        pendingSave = null
        val uri = res.data?.data
        if (res.resultCode != RESULT_OK || uri == null) { post(Bridge.saved(s.name, false, "cancelled")); return@registerForActivityResult }
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = Base64.decode(s.base64, Base64.DEFAULT)
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Could not open the file")
                }.exceptionOrNull()
            }
            post(Bridge.saved(s.name, err == null, err?.message))
        }
    }

    private val arRoom = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val json = res.data?.getStringExtra(ArRoomActivity.EXTRA_ROOM)
        if (res.resultCode == RESULT_OK && json != null) post(json)
        else post(Bridge.toast("AR capture closed – nothing was added."))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LaserManager.init(this)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        web = WebView(this)
        setContentView(web)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            mediaPlaybackRequiresUserGesture = true
        }

        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assets.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.scheme == "https" && url.host == Uri.parse(APP_ORIGIN).host) return false
                if (url.scheme == "http" || url.scheme == "https") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (_: ActivityNotFoundException) {}
                }
                return true // never navigate the app window away from its own pages
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    pickFiles.launch(params.createIntent()); true
                } catch (_: ActivityNotFoundException) {
                    fileCallback = null; false
                }
            }
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "LPNative", setOf(APP_ORIGIN)) { _, message, sourceOrigin, isMainFrame, replyProxy ->
                if (!isMainFrame || sourceOrigin.toString().trimEnd('/') != APP_ORIGIN) return@addWebMessageListener
                reply = replyProxy
                val cmd = message.data?.let(Bridge::parse)
                if (cmd == null) Diagnostics.log("Bridge: rejected message") else handle(cmd)
            }
        } else {
            Toast.makeText(this, "Please update Android System WebView – the laser link needs a newer version.", Toast.LENGTH_LONG).show()
        }

        onBackPressedDispatcher.addCallback(this) {
            web.evaluateJavascript("window.lpNativeBack?window.lpNativeBack():'exit'") { r ->
                if (r == null || r.contains("exit")) finish()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { LaserManager.state.collect { post(Bridge.stateMessage(it)) } }
                launch { LaserManager.readings.collect { post(Bridge.readingMessage(it)) } }
            }
        }

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) web.loadUrl(START_URL)
    }

    private fun handle(cmd: WebCommand) {
        when (cmd) {
            WebCommand.Hello -> post(Bridge.stateMessage(LaserManager.state.value))
            WebCommand.LaserOpen -> startActivity(Intent(this, LaserActivity::class.java))
            WebCommand.LaserReconnect -> if (!LaserManager.reconnectPreferred()) startActivity(Intent(this, LaserActivity::class.java))
            WebCommand.LaserDisconnect -> LaserManager.disconnect()
            WebCommand.ArOpen -> arRoom.launch(Intent(this, ArRoomActivity::class.java))
            is WebCommand.Save -> {
                pendingSave = cmd
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(cmd.mime).putExtra(Intent.EXTRA_TITLE, cmd.name)
                try { createDocument.launch(i) } catch (_: ActivityNotFoundException) {
                    pendingSave = null; post(Bridge.saved(cmd.name, false, "no file picker on this phone"))
                }
            }
        }
    }

    private fun post(json: String) {
        try { reply?.postMessage(json) } catch (e: Exception) { Diagnostics.log("Bridge post failed: ${e.message}") }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = hidKeys.handle(event) || super.dispatchKeyEvent(event)

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        reply = null
        web.destroy()
        super.onDestroy()
    }
}
