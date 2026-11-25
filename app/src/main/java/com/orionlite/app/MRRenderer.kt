package com.orionlite.app

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CopyOnWriteArrayList
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Draws everything: camera passthrough, floating web windows, the hand cursor,
 * one picture per eye, and the lens pre-distortion.
 */
class MRRenderer(
    private val act: MainActivity,
    private val head: HeadTracker,
    private val hands: HandTracker
) : GLSurfaceView.Renderer {

    val windows = CopyOnWriteArrayList<WebWindow>()

    // ---- settings (changed from the UI thread) ----
    @Volatile var ipd = 0.03f
    @Volatile var k1 = 0.22f
    @Volatile var lensScale = 1.15f
    @Volatile var zoom = 1.0f
    @Volatile var fovDeg = 70f

    // ---- camera ----
    var camST: SurfaceTexture? = null
    private var camTex = 0
    @Volatile private var camFrame = false
    private val camMtx = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    @Volatile var camW = 1280
    @Volatile var camH = 720
    @Volatile var camRotation = 0

    // ---- head ----
    @Volatile var yaw = 0f
    @Volatile var headM = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)   // camera -> world (row-major)

    // ---- screen / eyes ----
    private var scrW = 1; private var scrH = 1
    private var eyeW = 1; private var eyeH = 1
    private val fbo = IntArray(2); private val fboTex = IntArray(2)

    // ---- crop / projection ----
    private var cropOx = 0f; private var cropOy = 0f; private var cropRx = 1f; private var cropRy = 1f
    private var tanH = 0.7f; private var tanV = 0.4f
    private val proj = FloatArray(16)

    // ---- programs ----
    private var pCam = 0; private var pWin = 0; private var pBar = 0; private var pCursor = 0; private var pPost = 0
    private lateinit var quadFull: FloatBuffer
    private lateinit var quadUnit: FloatBuffer
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val flipY = FloatArray(16).also { Matrix.setIdentityM(it, 0); it[5] = -1f; it[13] = 1f }

    // ---- interaction ----
    private var smX = 0f; private var smY = 0f; private var smInit = false
    private var wasPinch = false
    private var cursorVisible = false
    private var cursorPos = floatArrayOf(0f, 0f, -1f)
    private var cursorPinch = false
    private class Drag(val w: WebWindow, val radius: Float, val offX: Float, val offY: Float)
    private var drag: Drag? = null
    private var touching: WebWindow? = null
    private var lastTouchU = 0f; private var lastTouchV = 0f

    // =====================================================================
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        quadFull = floatBuf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        quadUnit = floatBuf(floatArrayOf(-0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f))
        pCam = program(VS_CAM, FS_OES)
        pWin = program(VS_QUAD, FS_OES)
        pBar = program(VS_QUAD, FS_2D)
        pCursor = program(VS_QUAD, FS_CURSOR)
        pPost = program(VS_POST, FS_POST)

        camTex = oesTexture()
        camST = SurfaceTexture(camTex).apply { setOnFrameAvailableListener { camFrame = true } }
        // windows from a previous GL context need new textures
        for (w in windows) { w.texId = 0; w.barTex = 0; w.barDirty = true }
        act.runOnUiThread { act.onCameraTextureReady() }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        scrW = width; scrH = height
        eyeW = width / 2; eyeH = height
        if (fbo[0] != 0) { glDeleteFramebuffers(2, fbo, 0); glDeleteTextures(2, fboTex, 0) }
        glGenFramebuffers(2, fbo, 0); glGenTextures(2, fboTex, 0)
        for (i in 0..1) {
            glBindTexture(GL_TEXTURE_2D, fboTex[i])
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, eyeW, eyeH, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
            texParams(GL_TEXTURE_2D)
            glBindFramebuffer(GL_FRAMEBUFFER, fbo[i])
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fboTex[i], 0)
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        // --- new camera picture ---
        camST?.let { if (camFrame) { camFrame = false; it.updateTexImage(); it.getTransformMatrix(camMtx) } }

        // --- windows: create GL side, pull new frames, bars ---
        for (w in windows) {
            if (w.texId == 0) createWindowGl(w)
            if (w.frameAvailable) { w.frameAvailable = false; try { w.st?.updateTexImage(); w.st?.getTransformMatrix(w.stMatrix) } catch (_: Exception) {} }
            if (w.barDirty) uploadBar(w)
        }

        updateCrop()
        updateHead()
        interact()

        // --- render each eye ---
        val view = FloatArray(16)
        val m = headM
        Matrix.setIdentityM(view, 0)
        for (r in 0..2) for (c in 0..2) view[c * 4 + r] = m[c * 3 + r]   // inverse rotation = transpose
        Matrix.perspectiveM(proj, 0, Math.toDegrees(2.0 * atan(tanV.toDouble())).toFloat(), eyeW.toFloat() / eyeH, 0.05f, 50f)

        val sorted = windows.filter { it.texId != 0 }.sortedByDescending { V.len(it.pos) }
        for (eye in 0..1) {
            glBindFramebuffer(GL_FRAMEBUFFER, fbo[eye])
            glViewport(0, 0, eyeW, eyeH)
            glClearColor(0f, 0f, 0f, 1f); glClear(GL_COLOR_BUFFER_BIT)
            glDisable(GL_DEPTH_TEST)

            drawCamera()

            val eyeView = FloatArray(16); val t = FloatArray(16)
            Matrix.setIdentityM(t, 0); t[12] = if (eye == 0) ipd / 2f else -ipd / 2f
            Matrix.multiplyMM(eyeView, 0, t, 0, view, 0)
            val vp = FloatArray(16); Matrix.multiplyMM(vp, 0, proj, 0, eyeView, 0)

            glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
            for (w in sorted) drawWindow(w, vp)
            if (cursorVisible) drawCursor(vp)
            glDisable(GL_BLEND)
        }

        // --- lens pre-distortion, side by side ---
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, scrW, scrH)
        glClearColor(0f, 0f, 0f, 1f); glClear(GL_COLOR_BUFFER_BIT)
        glUseProgram(pPost)
        glUniform1f(glGetUniformLocation(pPost, "k1"), k1)
        glUniform1f(glGetUniformLocation(pPost, "k2"), k1 * 0.25f)
        glUniform1f(glGetUniformLocation(pPost, "scale"), lensScale)
        glUniform1f(glGetUniformLocation(pPost, "aspect"), eyeW.toFloat() / eyeH)
        for (eye in 0..1) {
            glViewport(eye * eyeW, 0, eyeW, eyeH)
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, fboTex[eye])
            glUniform1i(glGetUniformLocation(pPost, "tex"), 0)
            drawQuad(pPost, quadFull)
        }
    }

    // =====================================================================
    private fun updateCrop() {
        val upW: Float; val upH: Float
        if (camRotation % 180 == 0) { upW = camW.toFloat(); upH = camH.toFloat() } else { upW = camH.toFloat(); upH = camW.toFloat() }
        val v = upW / upH
        val a = eyeW.toFloat() / eyeH
        var rx = 1f; var ry = 1f
        if (v > a) rx = a / v else ry = v / a
        rx /= zoom; ry /= zoom
        cropRx = rx; cropRy = ry; cropOx = (1 - rx) / 2; cropOy = (1 - ry) / 2
        val h0 = Math.toRadians(fovDeg.toDouble())
        val tanH0 = tan(h0 / 2).toFloat()
        tanH = rx * tanH0
        tanV = ry * tanH0 / v
    }

    private fun updateHead() {
        val m0 = head.matrix
        val c = cos(yaw); val s = sin(yaw)
        // rotY(yaw) * m0
        val y = floatArrayOf(c, 0f, s, 0f, 1f, 0f, -s, 0f, c)
        val out = FloatArray(9)
        for (r in 0..2) for (col in 0..2) {
            out[r * 3 + col] = y[r * 3] * m0[col] + y[r * 3 + 1] * m0[3 + col] + y[r * 3 + 2] * m0[6 + col]
        }
        headM = out
    }

    /** Makes the direction you are looking now the "forward" direction. */
    fun recenter() {
        val m0 = head.matrix
        val fx = -m0[2]; val fz = -m0[8]
        val phi = atan2(-fx, -fz)
        yaw = -phi
    }

    /** Flat forward direction in the world (used to place new windows). */
    fun forwardFlat(): FloatArray {
        val m = headM
        val f = floatArrayOf(-m[2], 0f, -m[8])
        return if (V.len(f) < 1e-3f) floatArrayOf(0f, 0f, -1f) else V.norm(f)
    }

    // =====================================================================
    // Hand interaction
    private fun interact() {
        if (!hands.visible) {
            cursorVisible = false; smInit = false
            touching?.let { postTouch(it, MotionEvent.ACTION_UP, lastTouchU, lastTouchV) }
            touching = null; drag = null; wasPinch = false
            return
        }
        val nx = ((hands.x - cropOx) / cropRx) * 2f - 1f
        val ny = 1f - ((hands.y - cropOy) / cropRy) * 2f
        val pinch = hands.pinch
        val a = if (pinch) 0.35f else 0.5f
        if (!smInit) { smX = nx; smY = ny; smInit = true } else { smX += (nx - smX) * a; smY += (ny - smY) * a }
        val dir = V.norm(V.mul(headM, floatArrayOf(smX * tanH, smY * tanV, -1f)))
        val pinchStart = pinch && !wasPinch
        wasPinch = pinch
        cursorPinch = pinch
        cursorVisible = true

        // dragging a window by its bar
        drag?.let { d ->
            if (pinch) {
                val p = V.scale(dir, d.radius)
                val (x, y, _) = d.w.basis(p)
                d.w.pos = V.sub(V.sub(p, V.scale(x, d.offX)), V.scale(y, d.offY))
                cursorPos = V.scale(dir, d.radius - 0.01f)
                return
            }
            drag = null
        }

        // pressing on web content: keep sending moves (scroll / drag) until release
        touching?.let { w ->
            val h = planeHit(w, w.pos, w.widthM, w.heightM, dir)
            if (h != null) { lastTouchU = h[0]; lastTouchV = h[1]; cursorPos = V.scale(dir, h[2] - 0.005f) }
            if (pinch) postTouch(w, MotionEvent.ACTION_MOVE, lastTouchU, lastTouchV)
            else { postTouch(w, MotionEvent.ACTION_UP, lastTouchU, lastTouchV); touching = null }
            return
        }

        // what is under the cursor?
        var best: WebWindow? = null; var bestPart = ""; var bestU = 0f; var bestV = 0f; var bestT = Float.MAX_VALUE
        for (w in windows) {
            if (w.texId == 0 || w.closing) continue
            planeHit(w, w.pos, w.widthM, w.heightM, dir)?.let { h ->
                if (inside(h) && h[2] < bestT) { best = w; bestPart = "content"; bestU = h[0]; bestV = h[1]; bestT = h[2] }
            }
            planeHit(w, w.barCenter(), w.barW, w.barH, dir)?.let { h ->
                if (inside(h) && h[2] < bestT) { best = w; bestPart = "bar"; bestU = h[0]; bestV = h[1]; bestT = h[2] }
            }
        }
        cursorPos = if (best != null) V.scale(dir, bestT - 0.005f) else V.scale(dir, 1.3f)

        val w = best ?: return
        if (!pinchStart) return
        if (bestPart == "content") {
            touching = w; lastTouchU = bestU; lastTouchV = bestV
            postTouch(w, MotionEvent.ACTION_DOWN, bestU, bestV)
        } else {
            when (val b = w.barButton(bestU)) {
                "drag" -> {
                    val hit = V.scale(dir, bestT)
                    val (x, y, _) = w.basis()
                    val rel = V.sub(hit, w.pos)
                    drag = Drag(w, V.len(w.pos), V.dot(rel, x), V.dot(rel, y))
                }
                "recenter" -> { recenter(); act.runOnUiThread { act.toast("Recentered") } }
                else -> act.runOnUiThread { act.onBarButton(w, b) }
            }
        }
    }

    private fun inside(h: FloatArray) = h[0] in 0f..1f && h[1] in 0f..1f

    /** Ray from your head through [dir] against a window-shaped rectangle. Returns (u, v, distance). */
    private fun planeHit(w: WebWindow, center: FloatArray, width: Float, height: Float, dir: FloatArray): FloatArray? {
        val (x, y, z) = w.basis()
        val denom = V.dot(dir, z)
        if (abs(denom) < 1e-4f) return null
        val t = V.dot(center, z) / denom
        if (t <= 0f) return null
        val local = V.sub(V.scale(dir, t), center)
        return floatArrayOf(V.dot(local, x) / width + 0.5f, 0.5f - V.dot(local, y) / height, t)
    }

    private fun postTouch(w: WebWindow, action: Int, u: Float, v: Float) {
        val px = u.coerceIn(0f, 1f) * w.pxW
        val py = v.coerceIn(0f, 1f) * w.pxH
        act.runOnUiThread {
            if (w.closing) return@runOnUiThread
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) w.downTime = now
            val ev = MotionEvent.obtain(w.downTime, now, action, px, py, 0)
            ev.source = InputDevice.SOURCE_TOUCHSCREEN
            w.web.dispatchTouchEvent(ev)
            ev.recycle()
        }
    }

    // =====================================================================
    // Window GL resources
    private fun createWindowGl(w: WebWindow) {
        w.texId = oesTexture()
        val st = SurfaceTexture(w.texId)
        st.setDefaultBufferSize(w.pxW, w.pxH)
        st.setOnFrameAvailableListener { w.frameAvailable = true }
        val s = Surface(st)
        w.st = st; w.surface = s
        val tex = IntArray(1); glGenTextures(1, tex, 0); w.barTex = tex[0]
        w.barDirty = true
        act.runOnUiThread { w.host.surface = s; w.host.invalidate() }
    }

    fun releaseWindowGl(w: WebWindow) {
        try { w.surface?.release() } catch (_: Exception) {}
        try { w.st?.release() } catch (_: Exception) {}
        if (w.texId != 0) glDeleteTextures(1, intArrayOf(w.texId), 0)
        if (w.barTex != 0) glDeleteTextures(1, intArrayOf(w.barTex), 0)
        w.texId = 0; w.barTex = 0; w.st = null; w.surface = null
    }

    private fun uploadBar(w: WebWindow) {
        if (w.barTex == 0) return
        w.barDirty = false
        val bmp = w.drawBar()
        glBindTexture(GL_TEXTURE_2D, w.barTex)
        texParams(GL_TEXTURE_2D)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
    }

    // =====================================================================
    // Drawing
    private fun drawCamera() {
        glUseProgram(pCam)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        glUniform1i(glGetUniformLocation(pCam, "uTex"), 0)
        glUniformMatrix4fv(glGetUniformLocation(pCam, "uSt"), 1, false, camMtx, 0)
        glUniform4f(glGetUniformLocation(pCam, "uCrop"), cropOx, cropOy, cropRx, cropRy)
        glUniform1f(glGetUniformLocation(pCam, "uRot"), camRotation.toFloat())
        drawQuad(pCam, quadFull)
    }

    private fun model(center: FloatArray, b: Array<FloatArray>, sx: Float, sy: Float): FloatArray {
        val (x, y, z) = b
        return floatArrayOf(
            x[0] * sx, x[1] * sx, x[2] * sx, 0f,
            y[0] * sy, y[1] * sy, y[2] * sy, 0f,
            z[0], z[1], z[2], 0f,
            center[0], center[1], center[2], 1f
        )
    }

    private fun drawWindow(w: WebWindow, vp: FloatArray) {
        val b = w.basis()
        val mvp = FloatArray(16)
        // content
        Matrix.multiplyMM(mvp, 0, vp, 0, model(w.pos, b, w.widthM, w.heightM), 0)
        glUseProgram(pWin)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, w.texId)
        glUniform1i(glGetUniformLocation(pWin, "uTex"), 0)
        glUniformMatrix4fv(glGetUniformLocation(pWin, "uMvp"), 1, false, mvp, 0)
        glUniformMatrix4fv(glGetUniformLocation(pWin, "uSt"), 1, false, w.stMatrix, 0)
        drawQuad(pWin, quadUnit)
        // bar
        Matrix.multiplyMM(mvp, 0, vp, 0, model(w.barCenter(), b, w.barW, w.barH), 0)
        glUseProgram(pBar)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, w.barTex)
        glUniform1i(glGetUniformLocation(pBar, "uTex"), 0)
        glUniformMatrix4fv(glGetUniformLocation(pBar, "uMvp"), 1, false, mvp, 0)
        glUniformMatrix4fv(glGetUniformLocation(pBar, "uSt"), 1, false, flipY, 0)
        drawQuad(pBar, quadUnit)
    }

    private fun drawCursor(vp: FloatArray) {
        val p = cursorPos
        val size = V.len(p) * (if (cursorPinch) 0.018f else 0.026f)
        val b = arrayOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f))
        // face the viewer
        val z = V.norm(V.scale(p, -1f)); var x = V.cross(floatArrayOf(0f, 1f, 0f), z)
        if (V.len(x) > 1e-4f) { x = V.norm(x); b[0] = x; b[1] = V.cross(z, x); b[2] = z }
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, vp, 0, model(p, b, size, size), 0)
        glUseProgram(pCursor)
        glUniformMatrix4fv(glGetUniformLocation(pCursor, "uMvp"), 1, false, mvp, 0)
        glUniformMatrix4fv(glGetUniformLocation(pCursor, "uSt"), 1, false, identity, 0)
        if (cursorPinch) glUniform3f(glGetUniformLocation(pCursor, "uColor"), 0.49f, 0.77f, 1f)
        else glUniform3f(glGetUniformLocation(pCursor, "uColor"), 1f, 1f, 1f)
        drawQuad(pCursor, quadUnit)
    }

    private fun drawQuad(prog: Int, buf: FloatBuffer) {
        val loc = glGetAttribLocation(prog, "aPos")
        buf.position(0)
        glEnableVertexAttribArray(loc)
        glVertexAttribPointer(loc, 2, GL_FLOAT, false, 0, buf)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glDisableVertexAttribArray(loc)
    }

    // =====================================================================
    // GL helpers
    private fun floatBuf(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

    private fun texParams(target: Int) {
        glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(target, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    }

    private fun oesTexture(): Int {
        val t = IntArray(1); glGenTextures(1, t, 0)
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
        texParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        return t[0]
    }

    private fun shader(type: Int, src: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, src); glCompileShader(s)
        val ok = IntArray(1); glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Shader error: " + glGetShaderInfoLog(s))
        return s
    }

    private fun program(vs: String, fs: String): Int {
        val p = glCreateProgram()
        glAttachShader(p, shader(GL_VERTEX_SHADER, vs))
        glAttachShader(p, shader(GL_FRAGMENT_SHADER, fs))
        glLinkProgram(p)
        val ok = IntArray(1); glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Link error: " + glGetProgramInfoLog(p))
        return p
    }

    companion object {
        // camera background: crop/zoom in the upright picture, then undo sensor rotation
        const val VS_CAM = """
            attribute vec2 aPos;
            uniform mat4 uSt; uniform vec4 uCrop; uniform float uRot;
            varying vec2 vUv;
            void main() {
                vec2 uv = uCrop.xy + (aPos * 0.5 + 0.5) * uCrop.zw;
                vec2 b = uv;
                if (uRot > 45.0 && uRot < 135.0) b = vec2(1.0 - uv.y, uv.x);
                else if (uRot > 135.0 && uRot < 225.0) b = vec2(1.0 - uv.x, 1.0 - uv.y);
                else if (uRot > 225.0) b = vec2(uv.y, 1.0 - uv.x);
                vUv = (uSt * vec4(b, 0.0, 1.0)).xy;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }"""
        const val VS_QUAD = """
            attribute vec2 aPos;
            uniform mat4 uMvp; uniform mat4 uSt;
            varying vec2 vUv; varying vec2 vLocal;
            void main() {
                vLocal = aPos + 0.5;
                vUv = (uSt * vec4(aPos + 0.5, 0.0, 1.0)).xy;
                gl_Position = uMvp * vec4(aPos, 0.0, 1.0);
            }"""
        const val FS_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            varying vec2 vUv;
            void main() { gl_FragColor = vec4(texture2D(uTex, vUv).rgb, 1.0); }"""
        const val FS_2D = """
            precision mediump float;
            uniform sampler2D uTex;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uTex, vUv); }"""
        const val FS_CURSOR = """
            precision mediump float;
            uniform vec3 uColor;
            varying vec2 vLocal;
            void main() {
                float d = length(vLocal - 0.5) * 2.0;
                float ring = smoothstep(0.55, 0.65, d) * (1.0 - smoothstep(0.88, 1.0, d));
                float cdot = 1.0 - smoothstep(0.18, 0.28, d);
                float a = max(ring, cdot) * 0.95;
                gl_FragColor = vec4(uColor * a, a);
            }"""
        const val VS_POST = """
            attribute vec2 aPos;
            varying vec2 vUv;
            void main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }"""
        const val FS_POST = """
            precision mediump float;
            uniform sampler2D tex;
            uniform float k1; uniform float k2; uniform float scale; uniform float aspect;
            varying vec2 vUv;
            void main() {
                vec2 p = (vUv - 0.5) * 2.0; p.x *= aspect;
                float r2 = dot(p, p) / (aspect * aspect);
                vec2 d = p * (1.0 + k1 * r2 + k2 * r2 * r2) / scale; d.x /= aspect;
                vec2 uv = d * 0.5 + 0.5;
                if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
                gl_FragColor = texture2D(tex, uv);
            }"""
    }
}
