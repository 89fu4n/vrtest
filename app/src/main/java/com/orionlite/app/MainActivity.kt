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
import android.util.Size
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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: MRRenderer
    private lateinit var head: HeadTracker
    private lateinit var hands: HandTracker
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var rotation = android.view.Surface.ROTATION_90

    private var cameraReady = false
    private var cameraStarted = false
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

        head = HeadTracker(this) { rotation }
        hands = HandTracker(this)
        analysisExecutor.execute { hands.init() }

        root = FrameLayout(this)
        setContentView(root)

        renderer = MRRenderer(this, head, hands)
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
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { glView.queueEvent { renderer.recenter() }; toast("Recentered"); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { showSettings(); return true }
            override fun onDown(e: MotionEvent) = true
        })
        glView.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev) }

        startInvalidateLoop()

        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter { !has(it) }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (!has(Manifest.permission.CAMERA)) toast("Camera permission is needed for passthrough")
        maybeStartCamera()
    }

    /** Called by the renderer once its camera texture exists. */
    fun onCameraTextureReady() {
        cameraReady = true
        cameraStarted = false
        maybeStartCamera()
    }

    private fun maybeStartCamera() {
        if (!cameraReady || cameraStarted || !has(Manifest.permission.CAMERA)) return
        cameraStarted = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val exec = ContextCompat.getMainExecutor(this)

            val previewSel = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val preview = Preview.Builder().setResolutionSelector(previewSel).build()
            preview.setSurfaceProvider(exec) { req ->
                val st = renderer.camST ?: run { req.willNotProvideSurface(); return@setSurfaceProvider }
                st.setDefaultBufferSize(req.resolution.width, req.resolution.height)
                renderer.camW = req.resolution.width
                renderer.camH = req.resolution.height
                req.setTransformationInfoListener(exec) { info -> renderer.camRotation = info.rotationDegrees }
                val surface = android.view.Surface(st)
                req.provideSurface(surface, exec) { surface.release() }
            }

            val analysisSel = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(640, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(analysisSel)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { hands.analyze(it) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                toast("Camera error: ${e.message}")
            }

            if (!homeOpened) {
                homeOpened = true
                main.postDelayed({
                    glView.queueEvent { renderer.recenter() }
                    main.postDelayed({ openHome() }, 150)
                    if (hands.error != null) toast("Hand tracking error: ${hands.error}")
                }, 700)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // =====================================================================
    // Windows
    private fun openHome() = openWindow("file:///android_asset/home.html", home = true)

    @SuppressLint("SetJavaScriptEnabled")
    fun openWindow(url: String, home: Boolean = false) {
        val w = if (home) WebWindow(nextId++, 1400, 760, 0.70f, true)
                else WebWindow(nextId++, 1600, 1000, 0.80f, false)

        // place it in front of you, fanned out so windows don't stack
        val fwd = renderer.forwardFlat()
        val angles = floatArrayOf(-40f, 40f, -22f, 22f, -58f, 58f)
        val ang = if (home) 0f else Math.toRadians(angles[spawnN++ % angles.size].toDouble()).toFloat()
        val c = kotlin.math.cos(ang); val s = kotlin.math.sin(ang)
        val dir = floatArrayOf(fwd[0] * c + fwd[2] * s, 0f, -fwd[0] * s + fwd[2] * c)
        val dist = if (home) 1.5f else 1.45f
        w.pos = floatArrayOf(dir[0] * dist, if (home) -0.08f else 0.05f, dir[2] * dist)

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
        @JavascriptInterface fun recenter() = main.post { glView.queueEvent { renderer.recenter() }; toast("Recentered") }
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
        renderer.fovDeg = p.getFloat("fov", 70f)
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
        row("Camera FOV (°)", "fov", 45f, 95f, renderer.fovDeg) { renderer.fovDeg = it }
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
        rotation = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
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
        head.start()
        glView.onResume()
        renderer.windows.forEach { it.web.onResume() }
    }

    override fun onPause() {
        head.stop()
        glView.onPause()
        renderer.windows.forEach { it.web.onPause() }
        super.onPause()
    }

    override fun onDestroy() {
        speech?.destroy()
        renderer.windows.toList().forEach { closeWindow(it) }
        analysisExecutor.execute { hands.close() }
        analysisExecutor.shutdown()
        super.onDestroy()
    }
}
