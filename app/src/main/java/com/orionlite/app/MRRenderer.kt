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
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CopyOnWriteArrayList
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Draws everything: ARCore camera passthrough, web windows anchored in the room,
 * the hand cursor, one picture per eye and the lens pre-distortion.
 */
class MRRenderer(
    private val act: MainActivity,
    private val hands: HandTracker
) : GLSurfaceView.Renderer {

    val windows = CopyOnWriteArrayList<WebWindow>()

    // ---- settings (changed from the UI thread) ----
    @Volatile var ipd = 0.03f
    @Volatile var k1 = 0.22f
    @Volatile var lensScale = 1.15f
    @Volatile var zoom = 1.0f

    // ---- ARCore ----
    @Volatile var session: Session? = null
    private var texSetFor: Session? = null
    @Volatile var geometryDirty = true
    private var camTex = 0
    private val ndcQuad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val rawTexCoords = FloatArray(8)
    private lateinit var bgTexCoords: FloatBuffer
    @Volatile var tracking = false
    private var everTracked = false

    // head pose (world), written on the GL thread
    @Volatile var headPos = floatArrayOf(0f, 0f, 0f)
    @Volatile var headRot = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)   // camera -> world, row-major

    // ---- screen / eyes ----
    private var scrW = 1; private var scrH = 1
    private var eyeW = 1; private var eyeH = 1
    private val fbo = IntArray(2); private val fboTex = IntArray(2)

    private val view = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val projRaw = FloatArray(16)
    private val proj = FloatArray(16)
    private var haveProj = false

    // ---- programs ----
    private var pBg = 0; private var pWin = 0; private var pBar = 0; private var pCursor = 0; private var pPost = 0
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

    private class Drag(val w: WebWindow, val dist: Float, val offX: Float, val offY: Float)
    private class Resize(val w: WebWindow, val w0: Float, val len0: Float)
    private class Press(val w: WebWindow, val u0: Float, val v0: Float, var scrolling: Boolean)
    private var drag: Drag? = null
    private var resize: Resize? = null
    private var press: Press? = null
    private var lastU = 0f; private var lastV = 0f

    // =====================================================================
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        quadFull = floatBuf(ndcQuad)
        quadUnit = floatBuf(floatArrayOf(-0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f))
        bgTexCoords = floatBuf(FloatArray(8))
        pBg = program(VS_BG, FS_OES)
        pWin = program(VS_QUAD, FS_OES)
        pBar = program(VS_QUAD, FS_2D)
        pCursor = program(VS_QUAD, FS_CURSOR)
        pPost = program(VS_POST, FS_POST)
        camTex = oesTexture()
        texSetFor = null
        for (w in windows) { w.texId = 0; w.barTex = 0; w.barDirty = true }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        scrW = width; scrH = height
        eyeW = width / 2; eyeH = height
        geometryDirty = true
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
        glClearColor(0f, 0f, 0f, 1f)
        val s = session
        if (s == null) { glBindFramebuffer(GL_FRAMEBUFFER, 0); glViewport(0, 0, scrW, scrH); glClear(GL_COLOR_BUFFER_BIT); return }

        // --- ARCore update ---
        val frame: Frame
        try {
            if (texSetFor !== s) { s.setCameraTextureName(camTex); texSetFor = s; geometryDirty = true }
            if (geometryDirty) { s.setDisplayGeometry(act.displayRotation, eyeW, eyeH); geometryDirty = false }
            frame = s.update()
        } catch (e: Exception) {
            glBindFramebuffer(GL_FRAMEBUFFER, 0); glViewport(0, 0, scrW, scrH); glClear(GL_COLOR_BUFFER_BIT); return
        }
        val cam = frame.camera

        // camera picture -> each eye (ARCore handles rotation and cropping for us)
        if (frame.hasDisplayGeometryChanged() || rawTexCoords.all { it == 0f }) {
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndcQuad,
                Coordinates2d.TEXTURE_NORMALIZED, rawTexCoords)
        }
        updateBgCoords()

        tracking = cam.trackingState == TrackingState.TRACKING
        if (tracking) {
            cam.getViewMatrix(view, 0)
            cam.getProjectionMatrix(projRaw, 0, 0.05f, 50f)
            haveProj = true
            val pose = cam.displayOrientedPose
            headPos = floatArrayOf(pose.tx(), pose.ty(), pose.tz())
            val ax = pose.rotateVector(floatArrayOf(1f, 0f, 0f))
            val ay = pose.rotateVector(floatArrayOf(0f, 1f, 0f))
            val az = pose.rotateVector(floatArrayOf(0f, 0f, 1f))
            headRot = floatArrayOf(ax[0], ay[0], az[0], ax[1], ay[1], az[1], ax[2], ay[2], az[2])
            if (!everTracked) { everTracked = true; act.runOnUiThread { act.onFirstTracking() } }
        }
        if (haveProj) {
            projRaw.copyInto(proj)
            val z = zoom
            for (i in intArrayOf(0, 4, 8, 12, 1, 5, 9, 13)) proj[i] = projRaw[i] * z
        }

        // camera image for hand tracking
        if (hands.ready && !hands.busy) {
            try { frame.acquireCameraImage().use { hands.submit(it) } }
            catch (_: NotYetAvailableException) {} catch (_: Exception) {}
        }

        // --- windows: GL side, new frames, bars ---
        for (w in windows) {
            if (w.texId == 0) createWindowGl(w)
            if (w.frameAvailable) { w.frameAvailable = false; try { w.st?.updateTexImage(); w.st?.getTransformMatrix(w.stMatrix) } catch (_: Exception) {} }
            if (w.barDirty) uploadBar(w)
        }

        if (haveProj) interact(frame) else cursorVisible = false

        // --- each eye ---
        val sorted = windows.filter { it.texId != 0 }.sortedByDescending { V.len(V.sub(it.pos, headPos)) }
        for (eye in 0..1) {
            glBindFramebuffer(GL_FRAMEBUFFER, fbo[eye])
            glViewport(0, 0, eyeW, eyeH)
            glClear(GL_COLOR_BUFFER_BIT)
            glDisable(GL_DEPTH_TEST)
            drawBackground()
            if (!haveProj) continue
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
        glClear(GL_COLOR_BUFFER_BIT)
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

    private fun updateBgCoords() {
        // zoom: shrink the sampled area around its centre
        var cx = 0f; var cy = 0f
        for (i in 0 until 4) { cx += rawTexCoords[i * 2]; cy += rawTexCoords[i * 2 + 1] }
        cx /= 4; cy /= 4
        val z = zoom
        val out = FloatArray(8)
        for (i in 0 until 4) {
            out[i * 2] = cx + (rawTexCoords[i * 2] - cx) / z
            out[i * 2 + 1] = cy + (rawTexCoords[i * 2 + 1] - cy) / z
        }
        bgTexCoords.position(0); bgTexCoords.put(out); bgTexCoords.position(0)
    }

    // =====================================================================
    // Placement helpers (called from the UI thread too)
    fun forwardFlat(): FloatArray {
        val m = headRot
        val f = floatArrayOf(-m[2], 0f, -m[8])
        return if (V.len(f) < 1e-3f) floatArrayOf(0f, 0f, -1f) else V.norm(f)
    }

    /** Puts a window [dist] metres in front of you, at eye height, facing you. */
    fun placeInFront(w: WebWindow, dist: Float, right: Float = 0f, up: Float = 0f) {
        val f = forwardFlat()
        val r = floatArrayOf(-f[2], 0f, f[0])
        val h = headPos
        w.pos = floatArrayOf(h[0] + f[0] * dist + r[0] * right, h[1] + up, h[2] + f[2] * dist + r[2] * right)
        w.faceTowards(floatArrayOf(h[0], w.pos[1], h[2]))
    }

    /** Moves all windows so Home is in front of you again, keeping their layout. */
    fun bringHere() {
        val home = windows.firstOrNull { it.isHome } ?: return
        val oldPos = home.pos.copyOf()
        val oldN = home.basis()[2]
        placeInFront(home, 1.5f, 0f, -0.1f)
        val newN = home.basis()[2]
        val a = atan2(newN[0], newN[2]) - atan2(oldN[0], oldN[2])
        val c = cos(a); val s = sin(a)
        fun rot(v: FloatArray) = floatArrayOf(v[0] * c + v[2] * s, v[1], -v[0] * s + v[2] * c)
        for (w in windows) {
            if (w === home) continue
            val rel = rot(V.sub(w.pos, oldPos))
            w.pos = V.add(home.pos, rel)
            w.normal = rot(w.normal)
        }
    }

    // =====================================================================
    // Hand interaction
    private fun interact(frame: Frame) {
        if (!hands.visible || !tracking) {
            cursorVisible = false; smInit = false
            endPress()
            drag = null; resize = null; wasPinch = false
            return
        }
        // hand position in the camera image -> this eye's screen position
        val ndc = FloatArray(2)
        try {
            frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, floatArrayOf(hands.x, hands.y),
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndc)
        } catch (_: Exception) { cursorVisible = false; return }
        val nx = ndc[0] * zoom; val ny = ndc[1] * zoom
        val pinch = hands.pinch
        val a = if (pinch) 0.35f else 0.5f
        if (!smInit) { smX = nx; smY = ny; smInit = true } else { smX += (nx - smX) * a; smY += (ny - smY) * a }

        // ray from your eye through that point
        val dc = floatArrayOf((smX + proj[8]) / proj[0], (smY + proj[9]) / proj[5], -1f)
        val dir = V.norm(V.mul(headRot, dc))
        val o = headPos
        val pinchStart = pinch && !wasPinch
        wasPinch = pinch
        cursorPinch = pinch
        cursorVisible = true

        // moving a window
        drag?.let { d ->
            if (pinch) {
                val p = V.add(o, V.scale(dir, d.dist))
                d.w.pos = p
                d.w.faceTowards(floatArrayOf(o[0], p[1], o[2]))
                val (x, y, _) = d.w.basis()
                d.w.pos = V.sub(V.sub(p, V.scale(x, d.offX)), V.scale(y, d.offY))
                cursorPos = V.add(o, V.scale(dir, d.dist - 0.01f))
                return
            }
            drag = null
        }

        // resizing a window
        resize?.let { r ->
            if (pinch) {
                planeHit(r.w, r.w.pos, 1f, 1f, o, dir)?.let { h ->
                    val (x, y, _) = r.w.basis()
                    val local = V.sub(V.add(o, V.scale(dir, h[2])), r.w.pos)
                    val len = hypot(V.dot(local, x), V.dot(local, y))
                    r.w.widthM = (r.w0 * len / r.len0).coerceIn(0.25f, 3.5f)
                    cursorPos = V.add(o, V.scale(dir, h[2] - 0.01f))
                }
                return
            }
            resize = null
        }

        // pressing on a page: a quick pinch = tap, pinch + move = scroll / drag
        press?.let { p ->
            planeHit(p.w, p.w.pos, p.w.widthM, p.w.heightM, o, dir)?.let { h ->
                lastU = h[0]; lastV = h[1]; cursorPos = V.add(o, V.scale(dir, h[2] - 0.005f))
            }
            if (pinch) {
                val movedPx = hypot((lastU - p.u0) * p.w.pxW, (lastV - p.v0) * p.w.pxH)
                if (!p.scrolling && movedPx > 28f) {
                    p.scrolling = true
                    postTouch(p.w, MotionEvent.ACTION_DOWN, p.u0, p.v0)
                }
                if (p.scrolling) postTouch(p.w, MotionEvent.ACTION_MOVE, lastU, lastV)
            } else endPress()
            return
        }

        // what is under the cursor?
        var best: WebWindow? = null; var bestPart = ""; var bestU = 0f; var bestV = 0f; var bestT = Float.MAX_VALUE
        for (w in windows) {
            if (w.texId == 0 || w.closing) continue
            fun test(part: String, center: FloatArray, sw: Float, sh: Float) {
                planeHit(w, center, sw, sh, o, dir)?.let { h ->
                    if (h[0] in 0f..1f && h[1] in 0f..1f && h[2] < bestT - 0.002f) {
                        best = w; bestPart = part; bestU = h[0]; bestV = h[1]; bestT = h[2]
                    }
                }
            }
            test("content", w.pos, w.widthM, w.heightM)
            test("bar", w.barCenter(), w.barW, w.barH)
            test("resize", w.handleCenter(), w.handleSize, w.handleSize)
        }
        cursorPos = if (best != null) V.add(o, V.scale(dir, bestT - 0.005f)) else V.add(o, V.scale(dir, 1.3f))

        val w = best ?: return
        if (!pinchStart) return
        when (bestPart) {
            "content" -> { press = Press(w, bestU, bestV, false); lastU = bestU; lastV = bestV }
            "resize" -> {
                val (x, y, _) = w.basis()
                val local = V.sub(V.add(o, V.scale(dir, bestT)), w.pos)
                resize = Resize(w, w.widthM, hypot(V.dot(local, x), V.dot(local, y)).coerceAtLeast(0.05f))
            }
            "bar" -> when (val b = w.barButton(bestU)) {
                "drag" -> {
                    val hit = V.add(o, V.scale(dir, bestT))
                    val (x, y, _) = w.basis()
                    val rel = V.sub(hit, w.pos)
                    drag = Drag(w, bestT, V.dot(rel, x), V.dot(rel, y))
                }
                "recenter" -> bringHere()
                else -> act.runOnUiThread { act.onBarButton(w, b) }
            }
        }
    }

    private fun endPress() {
        val p = press ?: return
        press = null
        if (p.scrolling) postTouch(p.w, MotionEvent.ACTION_UP, lastU, lastV)
        else postTap(p.w, p.u0, p.v0)
    }

    /** Ray against a window-shaped rectangle. Returns (u, v, distance) or null. */
    private fun planeHit(w: WebWindow, center: FloatArray, width: Float, height: Float, o: FloatArray, dir: FloatArray): FloatArray? {
        val (x, y, z) = w.basis()
        val denom = V.dot(dir, z)
        if (abs(denom) < 1e-4f) return null
        val t = V.dot(V.sub(center, o), z) / denom
        if (t <= 0f) return null
        val local = V.sub(V.add(o, V.scale(dir, t)), center)
        return floatArrayOf(V.dot(local, x) / width + 0.5f, 0.5f - V.dot(local, y) / height, t)
    }

    private fun postTouch(w: WebWindow, action: Int, u: Float, v: Float) {
        val px = u.coerceIn(0f, 1f) * w.pxW
        val py = v.coerceIn(0f, 1f) * w.pxH
        act.runOnUiThread {
            if (w.closing) return@runOnUiThread
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) w.downTime = now
            dispatch(w, w.downTime, now, action, px, py)
        }
    }

    private fun postTap(w: WebWindow, u: Float, v: Float) {
        val px = u.coerceIn(0f, 1f) * w.pxW
        val py = v.coerceIn(0f, 1f) * w.pxH
        act.runOnUiThread {
            if (w.closing) return@runOnUiThread
            val t = SystemClock.uptimeMillis()
            dispatch(w, t, t, MotionEvent.ACTION_DOWN, px, py)
            dispatch(w, t, t + 40, MotionEvent.ACTION_UP, px, py)
        }
    }

    private fun dispatch(w: WebWindow, down: Long, time: Long, action: Int, x: Float, y: Float) {
        val ev = MotionEvent.obtain(down, time, action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        w.web.dispatchTouchEvent(ev)
        ev.recycle()
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
    private fun drawBackground() {
        glUseProgram(pBg)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        glUniform1i(glGetUniformLocation(pBg, "uTex"), 0)
        val locT = glGetAttribLocation(pBg, "aTex")
        bgTexCoords.position(0)
        glEnableVertexAttribArray(locT)
        glVertexAttribPointer(locT, 2, GL_FLOAT, false, 0, bgTexCoords)
        drawQuad(pBg, quadFull)
        glDisableVertexAttribArray(locT)
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
        Matrix.multiplyMM(mvp, 0, vp, 0, model(w.pos, b, w.widthM, w.heightM), 0)
        glUseProgram(pWin)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, w.texId)
        glUniform1i(glGetUniformLocation(pWin, "uTex"), 0)
        glUniformMatrix4fv(glGetUniformLocation(pWin, "uMvp"), 1, false, mvp, 0)
        glUniformMatrix4fv(glGetUniformLocation(pWin, "uSt"), 1, false, w.stMatrix, 0)
        drawQuad(pWin, quadUnit)

        Matrix.multiplyMM(mvp, 0, vp, 0, model(w.barCenter(), b, w.barW, w.barH), 0)
        glUseProgram(pBar)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, w.barTex)
        glUniform1i(glGetUniformLocation(pBar, "uTex"), 0)
        glUniformMatrix4fv(glGetUniformLocation(pBar, "uMvp"), 1, false, mvp, 0)
        glUniformMatrix4fv(glGetUniformLocation(pBar, "uSt"), 1, false, flipY, 0)
        drawQuad(pBar, quadUnit)

        // resize handle (bottom-right corner)
        Matrix.multiplyMM(mvp, 0, vp, 0, model(w.handleCenter(), b, w.handleSize, w.handleSize), 0)
        glUseProgram(pCursor)
        glUniformMatrix4fv(glGetUniformLocation(pCursor, "uMvp"), 1, false, mvp, 0)
        glUniformMatrix4fv(glGetUniformLocation(pCursor, "uSt"), 1, false, identity, 0)
        glUniform3f(glGetUniformLocation(pCursor, "uColor"), 0.49f, 0.77f, 1f)
        drawQuad(pCursor, quadUnit)
    }

    private fun drawCursor(vp: FloatArray) {
        val p = cursorPos
        val size = V.len(V.sub(p, headPos)) * (if (cursorPinch) 0.018f else 0.026f)
        val z = V.norm(V.sub(headPos, p))
        var x = V.cross(floatArrayOf(0f, 1f, 0f), z)
        val b = if (V.len(x) > 1e-4f) { x = V.norm(x); arrayOf(x, V.cross(z, x), z) }
                else arrayOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f))
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
        const val VS_BG = """
            attribute vec2 aPos; attribute vec2 aTex;
            varying vec2 vUv;
            void main() { vUv = aTex; gl_Position = vec4(aPos, 0.0, 1.0); }"""
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
