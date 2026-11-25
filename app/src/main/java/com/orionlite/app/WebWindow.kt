package com.orionlite.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.Surface
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * Holds a WebView and redirects its drawing into a Surface,
 * which feeds an OpenGL texture that floats in the room.
 */
class WebHost(ctx: Context) : FrameLayout(ctx) {
    @Volatile var surface: Surface? = null

    init { setWillNotDraw(false) }

    override fun dispatchDraw(canvas: Canvas) {
        val s = surface ?: return
        if (!s.isValid) return
        try {
            val c = s.lockHardwareCanvas()
            try {
                c.drawColor(Color.WHITE)
                super.dispatchDraw(c)
            } finally {
                s.unlockCanvasAndPost(c)
            }
        } catch (_: Exception) { }
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        invalidate()
    }
}

class WebWindow(
    val id: Int,
    val pxW: Int,
    val pxH: Int,
    @Volatile var widthM: Float,
    val isHome: Boolean
) {
    // placement in the room (ARCore world, metres). The window faces along [normal].
    @Volatile var pos = floatArrayOf(0f, 0f, -1.5f)
    @Volatile var normal = floatArrayOf(0f, 0f, 1f)
    val heightM get() = widthM * pxH / pxW
    val barW get() = (widthM * 0.62f).coerceIn(0.3f, 0.6f)
    val barH get() = barW * BAR_PX_H / BAR_PX_W
    val barGap get() = 0.015f
    val handleSize get() = (widthM * 0.07f).coerceIn(0.045f, 0.12f)

    // theater mode: remembers where the window was
    var theater = false
    var savedPos: FloatArray? = null
    var savedNormal: FloatArray? = null
    var savedWidth = 0f

    // views (UI thread)
    lateinit var host: WebHost
    lateinit var web: WebView
    var downTime = 0L

    // GL (GL thread)
    var texId = 0
    var st: SurfaceTexture? = null
    var surface: Surface? = null
    @Volatile var frameAvailable = false
    val stMatrix = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    var barTex = 0
    @Volatile var barDirty = true
    @Volatile var title = if (isHome) "Orion Home" else "Loading…"
    @Volatile var closing = false
    @Volatile var hoverBtn: String? = null
    @Volatile var hover = 0f          // 0..1, animated highlight

    /** Window axes: x right, y up, z towards the viewer. Windows always stay upright. */
    fun basis(): Array<FloatArray> {
        var z = floatArrayOf(normal[0], 0f, normal[2])
        z = if (V.len(z) < 1e-4f) floatArrayOf(0f, 0f, 1f) else V.norm(z)
        val x = V.norm(V.cross(floatArrayOf(0f, 1f, 0f), z))
        val y = V.cross(z, x)
        return arrayOf(x, y, z)
    }

    /** Turn the window so it faces a point (your head). */
    fun faceTowards(p: FloatArray) { normal = V.sub(p, pos) }

    fun handleCenter(): FloatArray {
        val (x, y, _) = basis()
        return V.add(V.add(pos, V.scale(x, widthM / 2f)), V.scale(y, -heightM / 2f))
    }

    fun barCenter(): FloatArray {
        val (_, y, _) = basis()
        return V.add(pos, V.scale(y, -(heightM / 2f + barGap + barH / 2f)))
    }

    /** Bar button at horizontal position u (0..1). */
    fun barButton(u: Float): String = if (isHome) {
        if (u > 0.85f) "bring" else "drag"
    } else when {
        u < 0.10f -> "back"
        u < 0.20f -> "mic"
        u < 0.30f -> "keyboard"
        u > 0.88f -> "close"
        u > 0.78f -> "theater"
        else -> "drag"
    }

    fun drawBar(): Bitmap {
        val bmp = Bitmap.createBitmap(BAR_PX_W, BAR_PX_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val W = BAR_PX_W.toFloat(); val H = BAR_PX_H.toFloat()
        p.color = Color.argb(225, 22, 24, 36)
        c.drawRoundRect(RectF(0f, 0f, W, H), H / 2, H / 2, p)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = H * 0.42f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        fun cy(t: TextPaint) = H / 2 - (t.descent() + t.ascent()) / 2
        val hov = hoverBtn
        fun icon(id: String, u0: Float, u1: Float, s: String, bg: Int) {
            p.color = if (hov == id) Color.argb(200, 124, 196, 255) else bg
            c.drawRoundRect(RectF(u0 * W + 6, 8f, u1 * W - 6, H - 8), H / 2, H / 2, p)
            c.drawText(s, (u0 + u1) / 2 * W, cy(tp), tp)
        }
        if (hov == "drag") {
            p.color = Color.argb(70, 124, 196, 255)
            val (a, b) = if (isHome) 0f to 0.85f else 0.30f to 0.78f
            c.drawRoundRect(RectF(a * W + 6, 8f, b * W - 6, H - 8), H / 2, H / 2, p)
        }
        if (isHome) {
            tp.color = Color.rgb(220, 226, 240)
            c.drawText("◆ Orion Home  ·  pinch here to move", 0.425f * W, cy(tp), tp)
            tp.color = Color.WHITE
            icon("bring", 0.85f, 1f, "🎯", Color.argb(140, 61, 139, 255))
        } else {
            val btnBg = Color.argb(40, 255, 255, 255)
            icon("back", 0f, 0.10f, "◀", btnBg)
            icon("mic", 0.10f, 0.20f, "🎤", btnBg)
            icon("keyboard", 0.20f, 0.30f, "⌨", btnBg)
            icon("theater", 0.78f, 0.88f, if (theater) "⤡" else "⤢", btnBg)
            icon("close", 0.88f, 1f, "✕", Color.argb(150, 255, 80, 80))
            tp.color = Color.rgb(215, 222, 238); tp.textSize = H * 0.36f
            val t = TextUtils.ellipsize(title, tp, 0.46f * W, TextUtils.TruncateAt.END).toString()
            c.drawText(t, 0.54f * W, cy(tp), tp)
        }
        return bmp
    }

    companion object {
        const val BAR_PX_W = 1024
        const val BAR_PX_H = 88
    }
}

/** Tiny vector helpers. */
object V {
    fun add(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
    fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    fun scale(a: FloatArray, s: Float) = floatArrayOf(a[0] * s, a[1] * s, a[2] * s)
    fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    fun len(a: FloatArray) = kotlin.math.sqrt(dot(a, a))
    fun norm(a: FloatArray): FloatArray { val l = len(a).coerceAtLeast(1e-6f); return scale(a, 1f / l) }
    fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )
    /** row-major 3x3 times vector */
    fun mul(m: FloatArray, v: FloatArray) = floatArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2]
    )
}
