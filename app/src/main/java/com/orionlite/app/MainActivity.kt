package com.orionlite.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
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
import android.view.Surface
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import org.json.JSONObject
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: MRRenderer
    private lateinit var hands: HandTracker
    private val main = Handler(Looper.getMainLooper())

    /** Rotation given to ARCore. Auto unless overridden in Settings. */
    @Volatile var displayRotation = Surface.ROTATION_90
    private var rotationOverride = -1     // -1 = auto

    private var session: Session? = null
    private var installRequested = false
    private var homeOpened = false
    private var nextId = 1
    private var spawnN = 0
    private var speech: SpeechRecognizer? = null

    private val prefs by lazy { getSharedPreferences("orion", MODE_PRIVATE) }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) { updateRotation() }
    }

    // =====================================================================
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        hands = HandTracker(this)
        hands.init()

        root = FrameLayout(this)
        setContentView(root)

        renderer = MRRenderer(this, hands)
        loadSettings()
        applyOrientation()

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setZOrderOnTop(true)          // the 3D view covers the hidden web views
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        root.addView(glView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Headset mode: tap = bring windows here, double tap = settings.
        // Hand-held mode: your finger works like the pinch cursor.
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { glView.queueEvent { renderer.bringHere() }; return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { showSettings(); return true }
            override fun onDown(e: MotionEvent) = true
        })
        glView.setOnTouchListener { _, ev ->
            if (renderer.headset) return@setOnTouchListener gestures.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    renderer.touchX = ev.x; renderer.touchY = ev.y
                    renderer.touchDown = true; renderer.touchLatch = true; renderer.touchActive = true
                }
                MotionEvent.ACTION_MOVE -> { renderer.touchX = ev.x; renderer.touchY = ev.y }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { renderer.touchX = ev.x; renderer.touchY = ev.y; renderer.touchDown = false }
            }
            true
        }

        startInvalidateLoop()
        if (!prefs.contains("headset")) main.postDelayed({ askMode() }, 400)
    }

    private fun askMode() {
        AlertDialog.Builder(this)
            .setTitle("How are you using Orion Lite?")
            .setMessage("In a VR viewer: split screen for both eyes, use pinch.\n\nHand-held: normal screen, use your finger or pinch. Good for testing.")
            .setPositiveButton("In a VR viewer") { _, _ -> setHeadset(true) }
            .setNegativeButton("Hand-held") { _, _ -> setHeadset(false) }
            .setCancelable(false)
            .setOnDismissListener { goImmersive() }
            .show()
    }

    private fun setHeadset(on: Boolean) {
        prefs.edit().putBoolean("headset", on).apply()
        renderer.headset = on
        applyOrientation()
        renderer.message(if (on) "Headset mode" else "Hand-held mode: tap with your finger")
    }

    private fun applyOrientation() {
        requestedOrientation = if (renderer.headset) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                               else ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        updateRotation()
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
        renderer.message(if (hands.error != null) "Hand tracking error: ${hands.error}"
                         else if (renderer.headset) "Ready · pinch to tap · pinch & hold on empty space = bring Home"
                         else "Ready · tap windows with your finger")
    }

    // =====================================================================
    // Windows
    private fun openHome() = openWindow("file:///android_asset/home.html", home = true)

    @SuppressLint("SetJavaScriptEnabled")
    fun openWindow(url: String, home: Boolean = false) {
        val w = if (home) WebWindow(nextId++, 1500, 860, 0.72f, true)
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
            isFocusable = true
            isFocusableInTouchMode = true
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
            "mic" -> listen { text -> w.web.loadUrl(toUrl(text)); renderer.message("🔎 $text") }
            "keyboard" -> typeInto(w)
        }
    }

    inner class HomeBridge {
        @JavascriptInterface fun open(url: String) { main.post { openWindow(url) } }
        @JavascriptInterface fun recenter() { main.post { glView.queueEvent { renderer.bringHere() } } }
        @JavascriptInterface fun closeAll() { main.post { this@MainActivity.closeAll() } }
        @JavascriptInterface fun settings() { main.post { showSettings() } }
        @JavascriptInterface fun voice() { main.post { askOrion() } }
        @JavascriptInterface fun env() { main.post { cycleEnv() } }
    }

    private fun cycleEnv() {
        val e = (renderer.env + 1) % 3
        renderer.env = e
        prefs.edit().putInt("env", e).apply()
        renderer.message(arrayOf("Environment: your room", "Environment: dimmed room", "Environment: space")[e])
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
    // Voice
    private val apps = mapOf(
        "youtube" to "https://m.youtube.com", "instagram" to "https://www.instagram.com",
        "google" to "https://www.google.com", "browser" to "https://www.google.com",
        "maps" to "https://www.google.com/maps", "wikipedia" to "https://en.m.wikipedia.org",
        "reddit" to "https://www.reddit.com", "twitter" to "https://x.com", "x" to "https://x.com",
        "chatgpt" to "https://chatgpt.com", "claude" to "https://claude.ai", "tiktok" to "https://www.tiktok.com",
        "facebook" to "https://m.facebook.com", "twitch" to "https://m.twitch.tv", "gmail" to "https://mail.google.com"
    )

    /** "Ask Orion": open youtube · search cats · close all · bring here · bigger · smaller · space · room · dim */
    private fun askOrion() = listen { said ->
        val t = said.lowercase().trim()
        fun has(vararg w: String) = w.any { t.contains(it) }
        when {
            has("close all", "close everything") -> { closeAll(); renderer.message("Closed all windows") }
            has("bring", "come here", "recenter", "center") -> glView.queueEvent { renderer.bringHere() }
            has("bigger", "larger", "zoom in") -> { glView.queueEvent { renderer.resizeFront(1.3f) }; renderer.message("Bigger") }
            has("smaller", "zoom out") -> { glView.queueEvent { renderer.resizeFront(1f / 1.3f) }; renderer.message("Smaller") }
            has("space", "stars") -> { renderer.env = 2; renderer.message("Environment: space") }
            has("dim", "dark") -> { renderer.env = 1; renderer.message("Environment: dimmed room") }
            has("room", "passthrough", "normal") -> { renderer.env = 0; renderer.message("Environment: your room") }
            t.startsWith("search ") || t.startsWith("google ") -> {
                val q = t.substringAfter(' ')
                openWindow("https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8"))
            }
            else -> {
                val name = apps.keys.sortedByDescending { it.length }.firstOrNull { k -> Regex("\\b${Regex.escape(k)}\\b").containsMatchIn(t) }
                if (name != null) { openWindow(apps.getValue(name)); renderer.message("Opening $name") }
                else openWindow(toUrl(said))
            }
        }
    }

    private fun listen(onText: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { renderer.message("Speech recognition not available"); return }
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 2); return
        }
        speech?.destroy()
        renderer.message("🎤 Listening…", 8000)
        speech = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle) {
                    val text = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (text.isNullOrBlank()) { renderer.message("Didn't catch that"); return }
                    renderer.message("“$text”")
                    onText(text)
                }
                override fun onPartialResults(b: Bundle) {
                    b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { renderer.message("🎤 $it", 8000) }
                }
                override fun onError(error: Int) { renderer.message("Didn't catch that") }
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
        renderer.headset = prefs.getBoolean("headset", true)
        renderer.ipd = prefs.getFloat("ipd", 0.03f)
        renderer.k1 = prefs.getFloat("k1", 0.22f)
        renderer.lensScale = prefs.getFloat("scale", 1.15f)
        renderer.zoom = prefs.getFloat("zoom", 1.0f)
        renderer.env = prefs.getInt("env", 0)
        renderer.showHud = prefs.getBoolean("hud", true)
        rotationOverride = prefs.getInt("rotation", -1)
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 8) }

        // mode
        box.addView(TextView(this).apply { text = "Mode" })
        val mode = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbVr = RadioButton(this).apply { text = "VR viewer"; id = 101 }
        val rbFlat = RadioButton(this).apply { text = "Hand-held"; id = 102 }
        mode.addView(rbVr); mode.addView(rbFlat)
        mode.check(if (renderer.headset) 101 else 102)
        mode.setOnCheckedChangeListener { _, id -> setHeadset(id == 101) }
        box.addView(mode)

        // camera rotation fix
        box.addView(TextView(this).apply { text = "Camera rotation (use if the picture is turned)" })
        val rot = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf("Auto" to -1, "0°" to 0, "90°" to 1, "180°" to 2, "270°" to 3).forEachIndexed { i, (label, v) ->
            rot.addView(RadioButton(this).apply { text = label; id = 200 + i; tag = v })
        }
        rot.check(200 + listOf(-1, 0, 1, 2, 3).indexOf(rotationOverride).coerceAtLeast(0))
        rot.setOnCheckedChangeListener { g, id ->
            rotationOverride = (g.findViewById<RadioButton>(id).tag as Int)
            prefs.edit().putInt("rotation", rotationOverride).apply()
            updateRotation()
        }
        box.addView(rot)

        box.addView(CheckBox(this).apply {
            text = "Show status line"; isChecked = renderer.showHud
            setOnCheckedChangeListener { _, c -> renderer.showHud = c; prefs.edit().putBoolean("hud", c).apply() }
        })

        fun row(label: String, key: String, min: Float, max: Float, cur: Float, apply: (Float) -> Unit) {
            val tv = TextView(this)
            val sb = SeekBar(this).apply { this.max = 100; progress = ((cur - min) / (max - min) * 100).toInt() }
            fun value() = min + (max - min) * sb.progress / 100f
            tv.text = "$label: ${"%.2f".format(cur)}"
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, pr: Int, fromUser: Boolean) {
                    val v = value(); tv.text = "$label: ${"%.2f".format(v)}"; apply(v); prefs.edit().putFloat(key, v).apply()
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
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done", null)
            .setNeutralButton("Environment") { _, _ -> cycleEnv() }
            .setOnDismissListener { goImmersive() }
            .show()
    }

    // =====================================================================
    fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun updateRotation() {
        val real = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        var r = if (rotationOverride in 0..3) rotationOverride else real
        // safety net: in headset mode the screen is always landscape
        if (rotationOverride !in 0..3 && ::renderer.isInitialized && renderer.headset &&
            (r == Surface.ROTATION_0 || r == Surface.ROTATION_180)) r = Surface.ROTATION_90
        displayRotation = r
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
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, main)
        updateRotation()
        startAr()
        glView.onResume()
        renderer.windows.forEach { it.web.onResume() }
    }

    override fun onPause() {
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
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
