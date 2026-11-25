package com.orionlite.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.net.URLEncoder
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session

class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: MRRenderer
    private lateinit var hands: HandTracker
    private val main = Handler(Looper.getMainLooper())
    @Volatile var displayRotation = android.view.Surface.ROTATION_90

    private var session: Session? = null
    private var installRequested = false
    private var homeOpened = false
    private var nextId = 1
    private var spawnN = 0
    private var speech: SpeechRecognizer? = null

    // =====================================================================
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()
        updateRotation()

        hands = HandTracker(this)
        hands.init()

        root = FrameLayout(this)
        setContentView(root)

        renderer = MRRenderer(this, hands)
        loadSettings()

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setZOrderOnTop(true)          // the 3D view covers the hidden web views
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        root.addView(glView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // tap = recenter, double tap = settings
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { glView.queueEvent { renderer.bringHere() }; return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { showSettings(); return true }
            override fun onDown(e: MotionEvent) = true
        })
        glView.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev) }

        startInvalidateLoop()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (!has(Manifest.permission.CAMERA)) toast("Camera permission is needed")
    }

    /** Starts ARCore (asks to install "Google Play Services for AR" if needed). */
    private fun startAr() {
        if (!has(Manifest.permission.CAMERA)) {
            val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter { !has(it) }
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
            return
        }
        try {
            if (session == null) {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return }
                    else -> {}
                }
                val s = Session(this)
                val cfg = Config(s).apply {
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.AUTO
                    planeFindingMode = Config.PlaneFindingMode.DISABLED
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                }
                s.configure(cfg)
                session = s
            }
            session?.resume()
            renderer.session = session
            renderer.geometryDirty = true
        } catch (e: Exception) {
            renderer.session = null
            toast("ARCore error: ${e.message}")
        }
    }

    /** Called by the renderer when tracking starts for the first time. */
    fun onFirstTracking() {
        if (homeOpened) return
        homeOpened = true
        openHome()
        toast(if (hands.error != null) "Hand tracking error: ${hands.error}" else "Ready. Pinch to tap.")
    }

    // =====================================================================
    // Windows
    private fun openHome() = openWindow("file:///android_asset/home.html", home = true)

    @SuppressLint("SetJavaScriptEnabled")
    fun openWindow(url: String, home: Boolean = false) {
        val w = if (home) WebWindow(nextId++, 1400, 760, 0.70f, true)
                else WebWindow(nextId++, 1600, 1000, 0.80f, false)

        // open right in front of you; each new window steps a little right/down so they don't hide each other
        if (home) renderer.placeInFront(w, 1.5f, 0f, -0.1f)
        else { val k = spawnN++ % 4; renderer.placeInFront(w, 1.2f - k * 0.05f, k * 0.12f, 0.02f - k * 0.06f) }

        w.host = WebHost(this)
        w.web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            setBackgroundColor(0xFFFFFFFF.toInt())
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    val scheme = req.url.scheme ?: return true
                    return !(scheme == "http" || scheme == "https" || scheme == "file")  // block "open in app" links
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView, title: String?) {
                    if (!w.isHome && !title.isNullOrBlank()) { w.title = title; w.barDirty = true }
                }
            }
            if (home) addJavascriptInterface(HomeBridge(), "OrionHome")
        }
        w.host.addView(w.web, FrameLayout.LayoutParams(w.pxW, w.pxH))
        root.addView(w.host, 0, FrameLayout.LayoutParams(w.pxW, w.pxH))
        w.web.loadUrl(url)
        renderer.windows.add(w)
    }

    fun closeWindow(w: WebWindow) {
        if (w.closing) return
        w.closing = true
        renderer.windows.remove(w)
        w.host.surface = null
        root.removeView(w.host)
        w.web.stopLoading()
        w.web.destroy()
        glView.queueEvent { renderer.releaseWindowGl(w) }
    }

    private fun closeAll() {
        renderer.windows.filter { !it.isHome }.forEach { closeWindow(it) }
        spawnN = 0
    }

    /** Buttons on the bar under each window. */
    fun onBarButton(w: WebWindow, id: String) {
        if (w.closing) return
        when (id) {
            "close" -> closeWindow(w)
            "back" -> if (w.web.canGoBack()) w.web.goBack()
            "mic" -> voiceSearch(w)
            "keyboard" -> typeInto(w)
        }
    }

    inner class HomeBridge {
        @JavascriptInterface fun open(url: String) = main.post { openWindow(url) }
        @JavascriptInterface fun recenter() = main.post { glView.queueEvent { renderer.bringHere() } }
        @JavascriptInterface fun closeAll() = main.post { this@MainActivity.closeAll() }
        @JavascriptInterface fun settings() = main.post { showSettings() }
    }

    // Keep web windows redrawing (needed for videos) at ~30 fps
    private fun startInvalidateLoop() {
        var tick = 0
        val r = object : Runnable {
            override fun run() {
                if (tick++ % 2 == 0) renderer.windows.forEach { if (!it.closing) it.host.invalidate() }
                root.postOnAnimation(this)
            }
        }
        root.postOnAnimation(r)
    }

    // =====================================================================
    // Voice search: say a site ("youtube.com") or anything to search Google
    private fun voiceSearch(w: WebWindow) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { toast("Speech recognition not available"); return }
        if (!has(Manifest.permission.RECORD_AUDIO)) { toast("Microphone permission needed"); return }
        speech?.destroy()
        val old = w.title
        w.title = "🎤 Listening…"; w.barDirty = true
        speech = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle) {
                    val text = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (text.isNullOrBlank()) { w.title = old; w.barDirty = true; return }
                    w.title = "🔎 $text"; w.barDirty = true
                    if (!w.closing) w.web.loadUrl(toUrl(text))
                }
                override fun onPartialResults(b: Bundle) {
                    b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { w.title = "🎤 $it"; w.barDirty = true }
                }
                override fun onError(error: Int) { w.title = old; w.barDirty = true; toast("Didn't catch that") }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            })
        }
    }

    private fun toUrl(spoken: String): String {
        // "youtube dot com" / "youtube.com" -> open the site, anything else -> Google search
        val site = spoken.trim().lowercase().replace(" dot ", ".").replace(" ", "")
        return if (Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}(/\\S*)?$").matches(site)) "https://$site"
        else "https://www.google.com/search?q=" + URLEncoder.encode(spoken, "UTF-8")
    }

    // Typing: take the phone out, type, and the text goes into the selected field
    private fun typeInto(w: WebWindow) {
        val input = EditText(this).apply { hint = "Text to type into the selected field" }
        AlertDialog.Builder(this)
            .setTitle("Type into ${w.title}")
            .setView(input)
            .setPositiveButton("Type + Enter") { _, _ -> injectText(w, input.text.toString(), true) }
            .setNeutralButton("Type") { _, _ -> injectText(w, input.text.toString(), false) }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { goImmersive() }
            .show()
    }

    private fun injectText(w: WebWindow, text: String, enter: Boolean) {
        if (w.closing) return
        val js = """
            (function(t){
              var e=document.activeElement; if(!e) return;
              if(e.isContentEditable){ document.execCommand('insertText', false, t); return; }
              var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e),'value');
              var v=(e.value||'')+t;
              if(d&&d.set){ d.set.call(e,v); } else { e.value=v; }
              e.dispatchEvent(new Event('input',{bubbles:true}));
              e.dispatchEvent(new Event('change',{bubbles:true}));
            })(${JSONObject.quote(text)})
        """.trimIndent()
        w.web.evaluateJavascript(js) {
            if (enter) {
                w.web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                w.web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
        }
    }

    // =====================================================================
    // Settings
    private fun loadSettings() {
        val p = getSharedPreferences("orion", MODE_PRIVATE)
        renderer.ipd = p.getFloat("ipd", 0.03f)
        renderer.k1 = p.getFloat("k1", 0.22f)
        renderer.lensScale = p.getFloat("scale", 1.15f)
        renderer.zoom = p.getFloat("zoom", 1.0f)
    }

    private fun showSettings() {
        val p = getSharedPreferences("orion", MODE_PRIVATE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 8) }
        fun row(label: String, key: String, min: Float, max: Float, cur: Float, apply: (Float) -> Unit) {
            val tv = TextView(this)
            val sb = SeekBar(this).apply { this.max = 100; progress = ((cur - min) / (max - min) * 100).toInt() }
            fun value() = min + (max - min) * sb.progress / 100f
            tv.text = "$label: ${"%.2f".format(cur)}"
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, pr: Int, fromUser: Boolean) {
                    val v = value(); tv.text = "$label: ${"%.2f".format(v)}"; apply(v); p.edit().putFloat(key, v).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            box.addView(tv); box.addView(sb)
        }
        row("Eye separation (m)", "ipd", 0f, 0.08f, renderer.ipd) { renderer.ipd = it }
        row("Lens correction", "k1", 0f, 0.6f, renderer.k1) { renderer.k1 = it }
        row("Image zoom (fit lens)", "scale", 0.7f, 1.8f, renderer.lensScale) { renderer.lensScale = it }
        row("Camera zoom", "zoom", 1f, 2f, renderer.zoom) { renderer.zoom = it }
        AlertDialog.Builder(this)
            .setTitle("Orion Lite settings")
            .setView(box)
            .setPositiveButton("Done", null)
            .setOnDismissListener { goImmersive() }
            .show()
    }

    // =====================================================================
    fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun updateRotation() {
        val r = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        if (r != displayRotation) { displayRotation = r; if (::renderer.isInitialized) renderer.geometryDirty = true }
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { goImmersive(); updateRotation() }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateRotation()
    }

    override fun onResume() {
        super.onResume()
        updateRotation()
        startAr()
        glView.onResume()
        renderer.windows.forEach { it.web.onResume() }
    }

    override fun onPause() {
        glView.onPause()
        renderer.session = null
        session?.pause()
        renderer.windows.forEach { it.web.onPause() }
        super.onPause()
    }

    override fun onDestroy() {
        speech?.destroy()
        renderer.windows.toList().forEach { closeWindow(it) }
        hands.close()
        session?.close()
        super.onDestroy()
    }
}
