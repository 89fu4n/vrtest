package com.orionlite.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
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
import com.google.ar.core.Pose
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
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
import kotlin.math.pow
import kotlin.math.sin

/**
 * Draws everything: ARCore camera passthrough (or a virtual environment), web windows
 * anchored in the room, the cursor, a status line, and (in headset mode) one picture
 * per eye with lens pre-distortion.
 */
class MRRenderer(
    private val act: MainActivity,
    private val hands: HandTracker,
    private val gyro: HeadTracker
) : GLSurfaceView.Renderer {

    val windows = CopyOnWriteArrayList<WebWindow>()

    // ---- settings (changed from the UI thread) ----
    @Volatile var headset = true          // true = split screen for a VR viewer, false = normal hand-held view
    @Volatile var ipd = 0.03f
    @Volatile var k1 = 0.22f
    @Volatile var lensScale = 1.15f
    @Volatile var zoom = 1.0f
    @Volatile var env = 0                 // 0 passthrough, 1 dimmed, 2 space
    @Volatile var showHud = true
    @Volatile var holdToSummon = true

    // ---- touch input (hand-held mode), written by the UI thread ----
    @Volatile var touchX = 0f
    @Volatile var touchY = 0f
    @Volatile var touchDown = false
    @Volatile var touchActive = false
    @Volatile var touchLatch = false      // makes sure very quick taps are not missed

    // ---- messages for the status line ----
    @Volatile private var msg = ""
    @Volatile private var msgUntil = 0L
    fun message(m: String, ms: Long = 2500) { msg = m; msgUntil = SystemClock.uptimeMillis() + ms }

    // ---- ARCore ----
    @Volatile var session: Session? = null
    private var texSetFor: Session? = null
    private var appliedRotation = -1
    private var appliedW = 0; private var appliedH = 0
    private var camTex = 0
    private val ndcQuad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val rawTexCoords = FloatArray(8)
    private lateinit var bgTexCoords: FloatBuffer
    @Volatile var tracking = false
    private var gyroBackup = false          // ARCore paused, gyro keeps windows in place
    private var arRotLast = FloatArray(9)
    private var gyroLast = FloatArray(9)
    private var lastHandSubmit = 0L
    private var trackingInfo = "starting"
    private var everTracked = false

    // head pose (world), written on the GL thread
    @Volatile var headPos = floatArrayOf(0f, 0f, 0f)
    @Volatile var headRot = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)   // camera -> world, row-major

    // ---- screen / eyes ----
    private var scrW = 1; private var scrH = 1
    private var eyeW = 1; private var eyeH = 1
    private var layoutHeadset = true
    private val fbo = IntArray(2); private val fboTex = IntArray(2)

    private val view = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val projRaw = FloatArray(16)
    private val proj = FloatArray(16)
    private var haveProj = false

    // ---- programs ----
    private var pBg = 0; private var pSpace = 0; private var pWin = 0; private var pBar = 0
    private var pCursor = 0; private var pPost = 0
    private lateinit var quadFull: FloatBuffer
    private lateinit var quadUnit: FloatBuffer
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val flipY = FloatArray(16).also { Matrix.setIdentityM(it, 0); it[5] = -1f; it[13] = 1f }

    // ---- status line ----
    private var hudTex = 0
    private var hudText = ""
    private var hudTime = 0L
    private var frames = 0; private var fps = 0f; private var fpsTime = 0L

    // ---- interaction ----
    private var smX = 0f; private var smY = 0f; private var smInit = false
    private var wasDown = false
    private var lastSource = ""
    private var cursorVisible = false
    private var cursorPos = floatArrayOf(0f, 0f, -1f)
    private var cursorDown = false
    private var cursorProgress = 0f

    private class Drag(val w: WebWindow, val dist: Float, val offX: Float, val offY: Float, val size0: Float)
    private class Resize(val w: WebWindow, val w0: Float, val len0: Float)
    private class Press(val w: WebWindow, val u0: Float, val v0: Float, var scrolling: Boolean)
    private class Hold(val start: Long, val x: Float, val y: Float)
    private var drag: Drag? = null
    private var resize: Resize? = null
    private var press: Press? = null
    private var hold: Hold? = null
    private var lastU = 0f; private var lastV = 0f
    private var hovered: WebWindow? = null

    // =====================================================================
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        quadFull = floatBuf(ndcQuad)
        quadUnit = floatBuf(floatArrayOf(-0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f))
        bgTexCoords = floatBuf(FloatArray(8))
        pBg = program(VS_BG, FS_BG)
        pSpace = program(VS_POST, FS_SPACE)
        pWin = program(VS_QUAD, FS_WIN)
        pBar = program(VS_QUAD, FS_2D)
        pCursor = program(VS_QUAD, FS_CURSOR)
        pPost = program(VS_POST, FS_POST)
        camTex = oesTexture()
        hudTex = IntArray(1).also { glGenTextures(1, it, 0) }[0]
        hudText = ""
        texSetFor = null
        appliedRotation = -1
        for (w in windows) { w.texId = 0; w.barTex = 0; w.barDirty = true }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        scrW = width; scrH = height
        layoutHeadset = !headset   // force rebuild
    }

    private fun rebuildLayout() {
        layoutHeadset = headset
        eyeW = if (headset) scrW / 2 else scrW
        eyeH = scrH
        if (fbo[0] != 0) { glDeleteFramebuffers(2, fbo, 0); glDeleteTextures(2, fboTex, 0); fbo[0] = 0 }
        if (headset) {
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
    }

    override fun onDrawFrame(gl: GL10?) {
        if (layoutHeadset != headset) rebuildLayout()
        glClearColor(0f, 0f, 0f, 1f)
        countFps()
        val s = session
        if (s == null) { glBindFramebuffer(GL_FRAMEBUFFER, 0); glViewport(0, 0, scrW, scrH); glClear(GL_COLOR_BUFFER_BIT); return }

        // --- ARCore update ---
        val frame: Frame
        try {
            if (texSetFor !== s) { s.setCameraTextureName(camTex); texSetFor = s; appliedRotation = -1 }
            val rot = act.displayRotation
            if (rot != appliedRotation || eyeW != appliedW || eyeH != appliedH) {
                s.setDisplayGeometry(rot, eyeW, eyeH)
                appliedRotation = rot; appliedW = eyeW; appliedH = eyeH
            }
            frame = s.update()
        } catch (e: Exception) {
            trackingInfo = "ARCore: ${e.javaClass.simpleName}"
            glBindFramebuffer(GL_FRAMEBUFFER, 0); glViewport(0, 0, scrW, scrH); glClear(GL_COLOR_BUFFER_BIT); return
        }
        val cam = frame.camera

        // camera picture -> view (ARCore handles rotation and cropping)
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
            arRotLast = headRot
            gyroLast = gyro.matrix
            gyroBackup = false
            if (!everTracked) { everTracked = true; act.runOnUiThread { act.onFirstTracking() } }
        } else if (everTracked && gyro.hasData) {
            // ARCore lost tracking: keep the last position, turn the view with the gyro
            val g0 = gyroLast; val g = gyro.matrix
            val delta = FloatArray(9)   // g0^T * g  (rotation since the last good frame, in camera space)
            for (r in 0..2) for (c in 0..2) delta[r * 3 + c] = g0[r] * g[c] + g0[3 + r] * g[3 + c] + g0[6 + r] * g[6 + c]
            val rot = FloatArray(9)
            for (r in 0..2) for (c in 0..2) rot[r * 3 + c] = arRotLast[r * 3] * delta[c] + arRotLast[r * 3 + 1] * delta[3 + c] + arRotLast[r * 3 + 2] * delta[6 + c]
            headRot = rot
            val p = headPos
            Matrix.setIdentityM(view, 0)
            for (r in 0..2) {
                for (c in 0..2) view[c * 4 + r] = rot[c * 3 + r]
                view[12 + r] = -(rot[r] * p[0] + rot[3 + r] * p[1] + rot[6 + r] * p[2])
            }
            gyroBackup = true
        }
        trackingInfo = when {
            tracking -> "AR ✓"
            gyroBackup -> "AR paused (gyro on): " + reason(cam.trackingFailureReason)
            cam.trackingState == TrackingState.PAUSED -> "AR paused: " + reason(cam.trackingFailureReason)
            else -> "AR stopped"
        }
        if (haveProj) {
            projRaw.copyInto(proj)
            val z = zoom
            for (i in intArrayOf(0, 4, 8, 12, 1, 5, 9, 13)) proj[i] = projRaw[i] * z
        }

        // camera image for hand tracking
        val nowMs = SystemClock.uptimeMillis()
        if (hands.ready && !hands.busy && nowMs - lastHandSubmit >= 50) {
            lastHandSubmit = nowMs
            try { frame.acquireCameraImage().use { hands.submit(it) } }
            catch (_: NotYetAvailableException) {} catch (_: Exception) {}
        }

        // --- windows: GL side, new frames, bars ---
        for (w in windows) {
            if (w.texId == 0) createWindowGl(w)
            if (w.frameAvailable) { w.frameAvailable = false; try { w.st?.updateTexImage(); w.st?.getTransformMatrix(w.stMatrix) } catch (_: Exception) {} }
            if (w.barDirty) uploadBar(w)
            val target = if (w === hovered) 1f else 0f
            w.hover += (target - w.hover) * 0.2f
        }

        if (tracking) updateAnchors(s)
        if (haveProj) interact(frame) else cursorVisible = false
        updateHud()

        // --- draw ---
        val sorted = windows.filter { it.texId != 0 }.sortedByDescending { V.len(V.sub(it.pos, headPos)) }
        val eyes = if (headset) 2 else 1
        for (eye in 0 until eyes) {
            if (headset) { glBindFramebuffer(GL_FRAMEBUFFER, fbo[eye]); glViewport(0, 0, eyeW, eyeH) }
            else { glBindFramebuffer(GL_FRAMEBUFFER, 0); glViewport(0, 0, scrW, scrH) }
            glClear(GL_COLOR_BUFFER_BIT)
            glDisable(GL_DEPTH_TEST)
            drawBackground()
            glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
            if (haveProj) {
                val eyeView = FloatArray(16); val t = FloatArray(16)
                Matrix.setIdentityM(t, 0)
                if (headset) t[12] = if (eye == 0) ipd / 2f else -ipd / 2f
                Matrix.multiplyMM(eyeView, 0, t, 0, view, 0)
                val vp = FloatArray(16); Matrix.multiplyMM(vp, 0, proj, 0, eyeView, 0)
                for (w in sorted) drawWindow(w, vp)
                if (cursorVisible) drawCursor(vp)
            }
            if (showHud || SystemClock.uptimeMillis() < msgUntil) drawHud()
            glDisable(GL_BLEND)
        }

        // --- headset: lens pre-distortion, side by side ---
        if (headset) {
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
    }

    /**
     * ARCore keeps refining its map of the room, which shifts world coordinates a little.
     * Anchors follow those corrections, so windows stay glued to the same spot in the room.
     */
    private fun updateAnchors(s: Session) {
        for (w in windows) {
            if (w.closing) continue
            val busy = drag?.w === w || resize?.w === w
            if (w.moved && !busy) {
                w.moved = false
                try {
                    w.anchor?.detach()
                    val n = w.normal
                    val yaw = atan2(n[0], n[2])
                    val q = floatArrayOf(0f, sin(yaw / 2f), 0f, cos(yaw / 2f))
                    w.anchor = s.createAnchor(Pose(w.pos.copyOf(), q))
                } catch (_: Exception) { w.anchor = null; w.moved = true }
            } else if (!w.moved) {
                val a = w.anchor ?: continue
                if (a.trackingState == TrackingState.TRACKING) {
                    val pose = a.pose
                    w.applyAnchor(floatArrayOf(pose.tx(), pose.ty(), pose.tz()), pose.rotateVector(floatArrayOf(0f, 0f, 1f)))
                }
            }
        }
    }

    fun detachAnchor(w: WebWindow) { try { w.anchor?.detach() } catch (_: Exception) {}; w.anchor = null }

    private fun reason(r: TrackingFailureReason?) = when (r) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "too dark"
        TrackingFailureReason.EXCESSIVE_MOTION -> "moving too fast"
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "point at a detailed area"
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "camera busy"
        else -> "move the phone slowly"
    }

    private fun countFps() {
        frames++
        val now = SystemClock.uptimeMillis()
        if (now - fpsTime >= 1000) { fps = frames * 1000f / (now - fpsTime); frames = 0; fpsTime = now }
    }

    private fun updateBgCoords() {
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
    // Placement helpers
    fun forwardFlat(): FloatArray {
        val m = headRot
        val f = floatArrayOf(-m[2], 0f, -m[8])
        return if (V.len(f) < 1e-3f) floatArrayOf(0f, 0f, -1f) else V.norm(f)
    }

    /** Puts a window [dist] metres in front of you, facing you. */
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
            w.pos = V.add(home.pos, rot(V.sub(w.pos, oldPos)))
            w.normal = rot(w.normal)
            w.theater = false
        }
        message("Windows moved in front of you")
    }

    /** Big screen far away, like a cinema. Toggle again to put it back. */
    fun toggleTheater(w: WebWindow) {
        if (!w.theater) {
            w.savedPos = w.pos.copyOf(); w.savedNormal = w.normal.copyOf(); w.savedWidth = w.widthM
            placeInFront(w, 3.0f, 0f, 0.25f)
            w.widthM = 2.6f
            w.theater = true
        } else {
            w.savedPos?.let { w.pos = it }; w.savedNormal?.let { w.normal = it }
            if (w.savedWidth > 0f) w.widthM = w.savedWidth
            w.theater = false
        }
        w.barDirty = true
    }

    fun resizeFront(factor: Float) {
        val w = windows.filter { !it.isHome }.minByOrNull { V.len(V.sub(it.pos, headPos)) } ?: return
        w.widthM = (w.widthM * factor).coerceIn(0.25f, 3.5f)
    }

    // =====================================================================
    // Pointer interaction (hand pinch, or finger on the screen in hand-held mode)
    private fun interact(frame: Frame) {
        // 1) where is the pointer and is it pressed?
        val useTouch = !headset && touchActive
        var nx: Float; var ny: Float; val down: Boolean
        if (useTouch) {
            nx = touchX / scrW * 2f - 1f
            ny = 1f - touchY / scrH * 2f
            down = touchDown || touchLatch
            touchLatch = false
            if (!down) touchActive = false
            if (lastSource != "touch") { smInit = false; lastSource = "touch" }
        } else {
            if (!hands.visible || !(tracking || gyroBackup)) {
                cursorVisible = false; smInit = false
                hovered = null; setHoverBtn(null, null)
                endPress(); drag = null; resize = null; hold = null; wasDown = false
                return
            }
            val ndc = FloatArray(2)
            try {
                frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, floatArrayOf(hands.x, hands.y),
                    Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndc)
            } catch (_: Exception) { cursorVisible = false; return }
            nx = ndc[0] * zoom; ny = ndc[1] * zoom
            down = hands.pinch
            if (lastSource != "hand") { smInit = false; lastSource = "hand" }
        }
        if (useTouch) { smX = nx; smY = ny; smInit = true }
        else {
            val a = if (down) 0.35f else 0.5f
            if (!smInit) { smX = nx; smY = ny; smInit = true } else { smX += (nx - smX) * a; smY += (ny - smY) * a }
        }

        // 2) ray from your eye through that point
        val dc = floatArrayOf((smX + proj[8]) / proj[0], (smY + proj[9]) / proj[5], -1f)
        val dir = V.norm(V.mul(headRot, dc))
        val o = headPos
        val pressStart = down && !wasDown
        wasDown = down
        cursorDown = down
        cursorVisible = !useTouch || down
        cursorProgress = 0f

        // 3) ongoing actions
        drag?.let { d ->
            if (down) {
                // push / pull: move your hand away from you to push the window back
                val dist = if (useTouch || d.size0 <= 0f) d.dist
                           else (d.dist * (d.size0 / hands.size.coerceAtLeast(0.01f)).pow(2)).coerceIn(0.35f, 8f)
                val p = V.add(o, V.scale(dir, dist))
                d.w.pos = p
                d.w.faceTowards(floatArrayOf(o[0], p[1], o[2]))
                val (x, y, _) = d.w.basis()
                d.w.pos = V.sub(V.sub(p, V.scale(x, d.offX)), V.scale(y, d.offY))
                d.w.theater = false
                cursorPos = V.add(o, V.scale(dir, dist - 0.01f))
                return
            }
            drag = null
        }
        resize?.let { r ->
            if (down) {
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
        press?.let { p ->
            planeHit(p.w, p.w.pos, p.w.widthM, p.w.heightM, o, dir)?.let { h ->
                lastU = h[0]; lastV = h[1]; cursorPos = V.add(o, V.scale(dir, h[2] - 0.005f))
            }
            if (down) {
                val movedPx = hypot((lastU - p.u0) * p.w.pxW, (lastV - p.v0) * p.w.pxH)
                if (!p.scrolling && movedPx > 28f) {
                    p.scrolling = true
                    postTouch(p.w, MotionEvent.ACTION_DOWN, p.u0, p.v0)
                }
                if (p.scrolling) postTouch(p.w, MotionEvent.ACTION_MOVE, lastU, lastV)
            } else endPress()
            return
        }
        hold?.let { hd ->
            if (down) {
                cursorPos = V.add(o, V.scale(dir, 1.3f))
                val moved = hypot(smX - hd.x, smY - hd.y)
                val t = (SystemClock.uptimeMillis() - hd.start) / 1500f
                if (moved > 0.08f) hold = null
                else if (t >= 1f) { hold = null; bringHere() }
                else cursorProgress = t
                return
            }
            hold = null
        }

        // 4) what is under the pointer?
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
        hovered = best
        val hb = best
        setHoverBtn(hb, if (hb != null && bestPart == "bar") hb.barButton(bestU) else null)

        if (!pressStart) return
        val w = best
        if (w == null) {
            if (!useTouch && holdToSummon) hold = Hold(SystemClock.uptimeMillis(), smX, smY)   // pinch & hold still on empty space = bring Home
            return
        }
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
                    drag = Drag(w, bestT, V.dot(rel, x), V.dot(rel, y), if (useTouch) 0f else hands.size)
                }
                "bring" -> bringHere()
                "theater" -> toggleTheater(w)
                else -> act.runOnUiThread { act.onBarButton(w, b) }
            }
        }
    }

    private var hoverBtnWindow: WebWindow? = null
    private fun setHoverBtn(w: WebWindow?, id: String?) {
        val old = hoverBtnWindow
        if (old != null && (old !== w || old.hoverBtn != id)) { old.hoverBtn = null; old.barDirty = true }
        if (w != null && w.hoverBtn != id) { w.hoverBtn = id; w.barDirty = true }
        hoverBtnWindow = if (id != null) w else null
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
            if (action == MotionEvent.ACTION_DOWN) { w.downTime = now; w.web.requestFocus() }
            dispatch(w, w.downTime, now, action, px, py)
        }
    }

    private fun postTap(w: WebWindow, u: Float, v: Float) {
        val px = u.coerceIn(0f, 1f) * w.pxW
        val py = v.coerceIn(0f, 1f) * w.pxH
        act.runOnUiThread {
            if (w.closing) return@runOnUiThread
            val t = SystemClock.uptimeMillis()
            w.web.requestFocus()
            dispatch(w, t, t, MotionEvent.ACTION_DOWN, px, py)
            w.web.postDelayed({ if (!w.closing) dispatch(w, t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, px, py) }, 60)
        }
    }

    private fun dispatch(w: WebWindow, down: Long, time: Long, action: Int, x: Float, y: Float) {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        val ev = MotionEvent.obtain(down, time, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0)
        w.web.dispatchTouchEvent(ev)
        ev.recycle()
    }

    // =====================================================================
    // Status line
    private fun updateHud() {
        val now = SystemClock.uptimeMillis()
        if (now - hudTime < 300) return
        hudTime = now
        val text = if (now < msgUntil) msg else {
            val hand = when {
                hands.error != null -> "hand error"
                !hands.ready -> "hand loading"
                hands.visible && hands.pinch -> "✋ pinch"
                hands.visible -> "✋ seen"
                else -> "no hand"
            }
            val rot = when (act.displayRotation) { Surface.ROTATION_0 -> 0; Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; else -> 270 }
            "$trackingInfo · $hand · ${fps.toInt()} fps · rot $rot · ${scrW}x$scrH"
        }
        if (text == hudText) return
        hudText = text
        val bmp = Bitmap.createBitmap(1024, 64, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 30f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER
        }
        val tw = (tp.measureText(text) + 40f).coerceAtMost(1020f)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 10, 12, 20) }
        c.drawRoundRect(RectF(512 - tw / 2, 6f, 512 + tw / 2, 58f), 26f, 26f, bg)
        c.drawText(text, 512f, 32f - (tp.descent() + tp.ascent()) / 2, tp)
        glBindTexture(GL_TEXTURE_2D, hudTex)
        texParams(GL_TEXTURE_2D)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
    }

    private fun drawHud() {
        val sx = if (headset) 1.3f else 1.0f
        val sy = sx * (eyeW.toFloat() / eyeH) / 16f
        val m = FloatArray(16); Matrix.setIdentityM(m, 0)
        m[0] = sx; m[5] = sy; m[13] = if (headset) 0.55f else 0.85f
        glUseProgram(pBar)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, hudTex)
        glUniform1i(glGetUniformLocation(pBar, "uTex"), 0)
        glUniformMatrix4fv(glGetUniformLocation(pBar, "uMvp"), 1, false, m, 0)
        glUniformMatrix4fv(glGetUniformLocation(pBar, "uSt"), 1, false, flipY, 0)
        drawQuad(pBar, quadUnit)
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
        detachAnchor(w)
        try { w.surface?.release() } catch (_: Exception) {}
        try { w.st?.release() } catch (_: Exception) {}
        if (w.texId != 0) glDeleteTextures(1, intArrayOf(w.texId), 0)
        if (w.barTex != 0) glDeleteTextures(1, intArrayOf(w.barTex), 0)
        w.texId = 0; w.barTex = 0; w.st = null; w.surface = null
        if (hovered === w) hovered = null
        if (hoverBtnWindow === w) hoverBtnWindow = null
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
        if (env == 2 && haveProj) {
            // virtual space environment, fixed in the world
            glUseProgram(pSpace)
            val r = headRot
            val colMajor = floatArrayOf(r[0], r[3], r[6], r[1], r[4], r[7], r[2], r[5], r[8])
            glUniformMatrix3fv(glGetUniformLocation(pSpace, "uRot"), 1, false, colMajor, 0)
            glUniform4f(glGetUniformLocation(pSpace, "uP"), proj[0], proj[5], proj[8], proj[9])
            drawQuad(pSpace, quadFull)
            return
        }
        glUseProgram(pBg)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        glUniform1i(glGetUniformLocation(pBg, "uTex"), 0)
        glUniform1f(glGetUniformLocation(pBg, "uDim"), if (env == 1) 0.3f else 1f)
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
        glUniform2f(glGetUniformLocation(pWin, "uSize"), w.widthM, w.heightM)
        glUniform1f(glGetUniformLocation(pWin, "uHover"), w.hover)
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
        glUniform1f(glGetUniformLocation(pCursor, "uProgress"), 0f)
        drawQuad(pCursor, quadUnit)
    }

    private fun drawCursor(vp: FloatArray) {
        val p = cursorPos
        val size = V.len(V.sub(p, headPos)) * (if (cursorDown) 0.02f else 0.028f) * (1f + cursorProgress)
        val z = V.norm(V.sub(headPos, p))
        var x = V.cross(floatArrayOf(0f, 1f, 0f), z)
        val b = if (V.len(x) > 1e-4f) { x = V.norm(x); arrayOf(x, V.cross(z, x), z) }
                else arrayOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f))
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, vp, 0, model(p, b, size, size), 0)
        glUseProgram(pCursor)
        glUniformMatrix4fv(glGetUniformLocation(pCursor, "uMvp"), 1, false, mvp, 0)
        glUniformMatrix4fv(glGetUniformLocation(pCursor, "uSt"), 1, false, identity, 0)
        if (cursorDown) glUniform3f(glGetUniformLocation(pCursor, "uColor"), 0.49f, 0.77f, 1f)
        else glUniform3f(glGetUniformLocation(pCursor, "uColor"), 1f, 1f, 1f)
        glUniform1f(glGetUniformLocation(pCursor, "uProgress"), cursorProgress)
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
        const val FS_BG = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            uniform float uDim;
            varying vec2 vUv;
            void main() { gl_FragColor = vec4(texture2D(uTex, vUv).rgb * uDim, 1.0); }"""
        const val VS_QUAD = """
            attribute vec2 aPos;
            uniform mat4 uMvp; uniform mat4 uSt;
            varying vec2 vUv; varying vec2 vLocal;
            void main() {
                vLocal = aPos + 0.5;
                vUv = (uSt * vec4(aPos + 0.5, 0.0, 1.0)).xy;
                gl_Position = uMvp * vec4(aPos, 0.0, 1.0);
            }"""
        // web window: rounded corners + soft glow on hover
        const val FS_WIN = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            uniform vec2 uSize; uniform float uHover;
            varying vec2 vUv; varying vec2 vLocal;
            void main() {
                float r = 0.025;
                vec2 p = (vLocal - 0.5) * uSize;
                vec2 q = abs(p) - (uSize * 0.5 - r);
                float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
                float a = 1.0 - smoothstep(-0.0015, 0.0015, d);
                vec3 c = texture2D(uTex, vUv).rgb;
                float edge = smoothstep(-0.01, -0.002, d) * uHover;
                c = mix(c, vec3(0.55, 0.8, 1.0), edge * 0.7) * (1.0 + 0.04 * uHover);
                gl_FragColor = vec4(c * a, a);
            }"""
        const val FS_2D = """
            precision mediump float;
            uniform sampler2D uTex;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uTex, vUv); }"""
        // cursor ring; uProgress draws the "hold to bring Home" arc
        const val FS_CURSOR = """
            precision mediump float;
            uniform vec3 uColor; uniform float uProgress;
            varying vec2 vLocal;
            void main() {
                vec2 v = vLocal - 0.5;
                float d = length(v) * 2.0;
                float ring = smoothstep(0.55, 0.65, d) * (1.0 - smoothstep(0.88, 1.0, d));
                float cdot = 1.0 - smoothstep(0.18, 0.28, d);
                float ang = (atan(v.x, v.y) + 3.14159) / 6.28318;
                float arc = (uProgress > 0.0 && ang < uProgress) ? ring : 0.0;
                vec3 col = mix(uColor, vec3(0.49, 0.77, 1.0), arc);
                float a = max(ring, cdot) * 0.95;
                gl_FragColor = vec4(col * a, a);
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
        // "Space" environment: night sky with stars, fixed in the world
        const val FS_SPACE = """
            precision highp float;
            uniform mat3 uRot; uniform vec4 uP;
            varying vec2 vUv;
            float hash(vec3 p) { return fract(sin(dot(p, vec3(12.9898, 78.233, 37.719))) * 43758.5453); }
            void main() {
                vec2 ndc = vUv * 2.0 - 1.0;
                vec3 d = normalize(uRot * vec3((ndc.x + uP.z) / uP.x, (ndc.y + uP.w) / uP.y, -1.0));
                float h = d.y * 0.5 + 0.5;
                vec3 col = mix(vec3(0.01, 0.01, 0.03), vec3(0.06, 0.04, 0.16), h);
                col += vec3(0.25, 0.12, 0.35) * pow(1.0 - abs(d.y), 8.0) * 0.5;
                vec3 g = floor(d * 160.0);
                float s = hash(g);
                col += step(0.996, s) * vec3(0.9) * (0.5 + 0.5 * hash(g + 1.0));
                if (d.y < -0.15) {
                    vec2 f = d.xz / -d.y;
                    vec2 grid = abs(fract(f * 1.0) - 0.5);
                    float line = 1.0 - smoothstep(0.0, 0.03, min(grid.x, grid.y));
                    col += vec3(0.2, 0.35, 0.8) * line * 0.25 * (1.0 - smoothstep(-0.5, -0.15, d.y));
                }
                gl_FragColor = vec4(col, 1.0);
            }"""
    }
}
